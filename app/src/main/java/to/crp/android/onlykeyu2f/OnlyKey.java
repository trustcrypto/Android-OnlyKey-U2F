package to.crp.android.onlykeyu2f;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.support.annotation.Nullable;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import to.crp.android.u2f.U2FContext;
import to.crp.android.u2f.U2FTransportAndroidHID;

/**
 * Class representing an attached OnlyKey.
 */
class OnlyKey {

    private static final String TAG = "okd-key";

    private static final int READ_TIMEOUT = 3*1000;

    private static final int OK_HID_INTERFACE = 1;
    private static final int OK_HID_INT_IN = 0;
    private static final int OK_HID_INT_OUT = 1;

    /**
     * OnlyKey message header.
     */
    private static final byte[] header = new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255};

    private static final byte MSG_SET_TIME = (byte) 228;

    // begin U2F constants
    private static final int FIDO_CLA = 0x00;
    private static final int FIDO_INS_AUTH = 0x02;
    private static final int FIDO_INS_REGISTER = 0x01;
    private static final int FIDO_P1_SIGN = 0x03;

    private static final int SW_OK = 0x9000;
    private static final int SW_USER_PRESENCE_REQUIRED = 0x6985;

    private final List<OKListener> listeners = new CopyOnWriteArrayList<>();

    private final String serial;
    private final UsbInterface intf;
    private final UsbDeviceConnection conn;
    private final UsbEndpoint epIn;
    private final UsbEndpoint epOut;

    private boolean initialized = false;
    private int unlockcount = 0;

    private final LinkedBlockingQueue<U2FContext> u2fRequests = new LinkedBlockingQueue<>();

    private final U2FTransportAndroidHID u2fTransport;

    /**
     * Kick off time set to determine if key is unlocked and usable.
     */
    private final RunnableImpl initializer = new RunnableImpl() {
        @Override
        public void run() {
            try {
                sendOKSETTIME();

                // assume we've received all messages if we get a read timeout
                boolean done = false;

                while (!done && !isCancelledOrInterrupted()) {
                    //Log.d(TAG, "Waiting for message.");
                    try {
                        byte[] in = read();
                        done = handleMessage(in);
                    } catch (IOException ioe) {
                        break;
                    }
                }

                //Log.d(TAG, "Done processing messages.");

                // kick off processing any U2F requests
                doU2fProcessing();

                //Log.d(TAG, "initializer done. Cancelled? " + isCancelledOrInterrupted());
            } catch (IOException ioe) {
                Log.w(TAG, ioe.getMessage(), ioe);
                notifyListeners(new OKEvent(OnlyKey.this, OKEvent.OKEType.ERROR, ioe));
            }
        }
    };

    /**
     * Create a new OnlyKey.
     *
     * @param conn  The USB device connection.
     * @param epIn  The IN USB endpoint.
     * @param epOut The OUT USB endpoint.
     */
    private OnlyKey(final String serial, final UsbInterface intf, final UsbDeviceConnection conn,
                    final UsbEndpoint epIn,
                    final UsbEndpoint epOut) {
        this.intf = intf;
        this.serial = serial;
        this.conn = conn;
        this.epIn = epIn;
        this.epOut = epOut;
        this.u2fTransport = new U2FTransportAndroidHID(conn, epIn, epOut);
    }

    /**
     * Add a listener to be notified of OnlyKey events.
     *
     * @param listener The listener to add.
     */
    void addListener(final OKListener listener) {
        listeners.add(listener);
    }

    /**
     * Add a U2F context to be processed by this OnlyKey.
     *
     * @param u2fContext The U2F context.
     */
    void addU2fRequest(final U2FContext u2fContext) {
        u2fRequests.add(u2fContext);
    }

    /**
     * Notify listeners on OnlyKey event.
     *
     * @param event The event object.
     */
    private void notifyListeners(final OKEvent event) {
        for (final OKListener l : listeners) {
            switch (event.getType()) {
                case DONE:
                    l.okDone(event);
                    break;
                case ERROR:
                    l.okError(event);
                    break;
                case MSG:
                    l.okMessage(event);
                    break;
                case SET_INITIALIZED:
                    l.okSetInitialized(event);
                    break;
                case SET_LOCKED:
                    l.okSetLocked(event);
                    break;
                case SET_TIME:
                    l.okSetTime(event);
                    break;
                case U2F_RESPONSE:
                    l.u2fResponse(event);
                    break;
                default:
                    throw new RuntimeException("Unknown event type!");
            }
        }
    }

    /**
     * Get a new {@link OnlyKey}. Must call {@link #init()}.
     *
     * @param device  The OnlyKey USB device.
     * @param manager The USBManager.
     * @throws IOException Thrown on error configuring the OnlyKey USB device.
     */
    static OnlyKey getOnlyKey(final UsbDevice device, final UsbManager manager) throws IOException {
        // get interface
        if (device.getInterfaceCount() < OK_HID_INTERFACE)
            throw new IOException("USB device does not have any interfaces!");

        // get HID input endpoint
        final UsbInterface intHID = device.getInterface(OK_HID_INTERFACE);
        if (intHID.getEndpointCount() < Math.max(OnlyKey.OK_HID_INT_IN, OnlyKey.OK_HID_INT_OUT)) {
            throw new IOException(
                    "OK HID int " + OK_HID_INTERFACE + " doesn't have two endpoints!");
        }
        final UsbEndpoint epIn = intHID.getEndpoint(OnlyKey.OK_HID_INT_IN);
        if (epIn.getType() != UsbConstants.USB_ENDPOINT_XFER_INT) {
            throw new IOException("Endpoint is not type INTERRUPT!");
        }
//        Log.d(TAG, "hid int " + intHID.getId() + " IN Endpoint: " + epIn.getEndpointNumber() +
// "," +
//                "" + "" + " address: " + epIn.getAddress() + " Direction: " + (epIn.getDirection
//                () == UsbConstants.USB_DIR_IN ? "In" : "Out"));

        // get HID output endpoint
        final UsbEndpoint epOut = intHID.getEndpoint(OnlyKey.OK_HID_INT_OUT);
        if (epOut.getType() != UsbConstants.USB_ENDPOINT_XFER_INT) {
            throw new IOException("Endpoint is not type INTERRUPT");
        }
//        Log.d(TAG, "hid int " + intHID.getId() + " OUT Endpoint: " + epOut.getEndpointNumber() +
//                ", address: " + epOut.getAddress() + " Direction: " + (epOut.getDirection() ==
//                UsbConstants.USB_DIR_IN ? "In" : "Out"));

        // get connection
        final UsbDeviceConnection connection = manager.openDevice(device);
        if (connection == null) throw new IOException("Could not open connection to USB device!");

        connection.claimInterface(intHID, true);

        final String serial = device.getSerialNumber() == null ? "N/A" : device.getSerialNumber();

        return new OnlyKey(serial, intHID, connection, epIn, epOut);
    }

    /**
     * @param afterU2fProcessing Finishing after U2F processing?
     */
    private void doFinish(final boolean afterU2fProcessing) {
        initializer.cancel();

        conn.releaseInterface(intf);
        conn.close();

        notifyListeners(new OKEvent(OnlyKey.this, OKEvent.OKEType.DONE, afterU2fProcessing));
    }

    /**
     * Kick off time set to determine if key is unlocked and usable.
     */
    final void init() {
        new Thread(initializer, "initializer").start();
    }

    /**
     * Process received data and notify as appropriate.
     *
     * @param data Data received from the OnlyKey.
     * @return TRUE when message indicates no further messages will be received.
     */
    private boolean handleMessage(final byte[] data) {
        String msg = new String(data, 0, data.length, StandardCharsets.UTF_8);
        msg = msg.trim();

        Log.d(TAG, "Received: " + msg);

        switch (msg) {
            case "UNINITIALIZED":
            case "INITIALIZED":
                setInitialized(true);
                break;
            default:
                break;
        }

        if (msg.contains("UNLOCKED")) {
            if (++unlockcount == 2) {
                final String[] s = msg.split("UNLOCKED");
                if (s.length == 2) {
                    final String ver = msg.split("UNLOCKED")[1].trim();
                    if (!ver.isEmpty()) {
                        Log.d(TAG, "OK version: " + ver);
                    }
                }

                setInitialized(true);
                setLocked(false);
                return true;
            }
        } else if (msg.contains("LOCKED")) {
            setLocked(true);
        }

        return false;
    }

    /**
     * Blocks until register or sign operation is complete.
     *
     * @throws IOException Thrown on operation error.
     */
    void doU2fProcessing() throws IOException {
        if (u2fRequests.size() > 0) {
            Log.d(TAG, "SN: " + serial + ": Processing " + u2fRequests.size() + " U2F request(s).");
        } else {
            Log.d(TAG, "SN: " + serial + ": No U2F requests to process.");
            doFinish(false);
            return;
        }

        u2fTransport.init();

        while (true) {
            final U2FContext context = u2fRequests.poll();

            if (context == null) {
                break;
            }

            final byte[] response =
                    context.isSign() ? processSign(context) : processRegister(context);

            final String responseString = U2FContext.createU2FResponse(context, response);
            notifyListeners(new OKEvent(this, OKEvent.OKEType.U2F_RESPONSE, responseString));
            doFinish(true);
        }
        Log.d(TAG, "Done processing U2F context queue.");
    }

    /**
     * Process a U2F signing context.
     * <p>
     * Blocks until the signing operation is complete.
     *
     * @return The last response payload to the sign request.
     * @throws IOException Thrown when there is an error with the signing process.
     */
    private byte[] processSign(final U2FContext u2fContext) throws IOException { // NEEDS TIMEOUT
        Log.d(TAG, "Processing a U2F sign request.");

        Log.d(TAG, "Have " + u2fContext.getKeyHandles().size() + " key handles.");

        try {
            byte[] response = null;

            choiceLoop:
            for (final byte[] keyHandle : u2fContext.getKeyHandles()) {
                // create a sign request until the response indicates responseOK
                while (true) {
                    final ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    final int msgLength = 32 + 32 + 1 + keyHandle.length;
                    bos.write(FIDO_CLA);
                    bos.write(FIDO_INS_AUTH);
                    bos.write(FIDO_P1_SIGN);
                    bos.write(0x00); // p2
                    bos.write(0x00); // extended length
                    bos.write(msgLength >> 8);
                    bos.write(msgLength & 0xff);
                    final MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    bos.write(digest.digest(
                            U2FContext.createClientData(u2fContext).getBytes("UTF-8")));
                    bos.write(digest.digest(u2fContext.getAppId().getBytes("UTF-8")));
                    bos.write(keyHandle.length);
                    bos.write(keyHandle);
                    bos.write(0x00);
                    bos.write(0x00);
                    final byte[] authApdu = bos.toByteArray();
                    response = u2fTransport.exchange(authApdu);

                    if (response.length < 2) {
                        Log.w(TAG, "Received response length: " + response.length);

                        if (response.length == 1) {
                            Log.w(TAG, Integer.toString(response[0] & 0xff));
                            return response;
                        }
                        Thread.sleep(500);
                    } else {
                        final int status = getStatus(response);

                        if (status == SW_OK) {
                            Log.d(TAG, "SW_OK");
                            u2fContext.setChosenKeyHandle(keyHandle);
                            break choiceLoop;
                        }

                        if (status == SW_USER_PRESENCE_REQUIRED) {
                            Log.d(TAG, "SW_USER_PRESENCE_REQ");
                            // waiting for user to select U2F slot (Yubico page)
                            Thread.sleep(1000);
                        } else {
                            Log.w(TAG, "Unhandled status: " + Integer.toHexString(status));
                            break;
                        }
                    }
                }
            }

            return response;
        } catch (IOException | NoSuchAlgorithmException | InterruptedException ioe) {
            throw new IOException("Error during signing operation.", ioe);
        }
    }

    /**
     * Process a U2F registration context.
     * <p>
     * Blocks until registration operation is complete.
     *
     * @return The last response to the register request.
     * @throws IOException Thrown when there is an error with the signing process.
     */
    private byte[] processRegister(final U2FContext u2fContext) throws IOException {
        Log.d(TAG, "Processing a U2F register request.");
        try {
            byte[] response;
            for (; ; ) {
                Log.d(TAG, "Processing register u2fContext.");

                final ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int msgLength = 32 + 32;
                bos.write(FIDO_CLA);
                bos.write(FIDO_INS_REGISTER);
                bos.write(0x00); // p1
                bos.write(0x00); // p2
                bos.write(0x00); // extended length
                bos.write(msgLength >> 8);
                bos.write(msgLength & 0xff);
                final MessageDigest digest = MessageDigest.getInstance("SHA-256");
                bos.write(digest.digest(U2FContext.createClientData(u2fContext).getBytes("UTF-8")));
                bos.write(digest.digest(u2fContext.getAppId().getBytes("UTF-8")));
                bos.write(0x00);
                bos.write(0x00);
                final byte[] authApdu = bos.toByteArray();

                response = u2fTransport.exchange(authApdu); // auth application protocol data unit

                if (isResponseOK(response)) {
                    break;
                }
                if (isResponseBusy(response)) {
                    Thread.sleep(200);
                } else {
                    response = null;
                    break;
                }
            }
            return response;
        } catch (IOException | NoSuchAlgorithmException | InterruptedException ioe) {
            throw new IOException("Error during signing operation.", ioe);
        }
    }

    private static int getStatus(final @Nullable byte[] payload) {
        if (payload == null)
            throw new IllegalArgumentException("byte[] cannot be NULL.");

        if (payload.length < 2)
            throw new IllegalArgumentException("Payload size must be >= 2.");

        return ((payload[payload.length - 2] & 0xff) << 8) | (payload[payload.length - 1] & 0xff);
    }

    /**
     * Does response indicate no error?
     *
     * @param response Response to validate.
     * @return FALSE if response is NULL or length < 2
     */
    private static boolean isResponseOK(final @Nullable byte[] response) {
        return getStatus(response) == SW_OK;
    }

    private static boolean isResponseBusy(final @Nullable byte[] response) {
        return getStatus(response) == SW_USER_PRESENCE_REQUIRED;
    }

    private void setInitialized(final boolean value) {
        if (initialized != value) {
            initialized = value;
            notifyListeners(new OKEvent(this, OKEvent.OKEType.SET_INITIALIZED, value));
        }
    }

    private void setLocked(final boolean value) {
        notifyListeners(new OKEvent(this, OKEvent.OKEType.SET_LOCKED, value));
    }

    private byte[] read() throws IOException {
        final UsbRequest requestRead = new UsbRequest();

        final Callable<byte[]> c = () -> {
            if (!requestRead.initialize(conn, epIn))
                throw new IOException("Read request could not be opened.");

            final ByteBuffer responseBuffer = ByteBuffer.allocate(epIn.getMaxPacketSize());
            if (!requestRead.queue(responseBuffer, epIn.getMaxPacketSize()))
                throw new IOException("Error queuing request!");

            final UsbRequest r = conn.requestWait(); // blocking
            if (r == null)
                throw new IOException("Error receiving data (returned NULL).");

            if (!r.equals(requestRead)) {
                final int recIntNum = r.getEndpoint().getEndpointNumber();
                if (recIntNum != epIn.getEndpointNumber()) {
                    Log.w(TAG,
                            "initializer: Incoming from int that's not epIn. (" + recIntNum +
                                    ").");
                }
            }

            return responseBuffer.array();
        };

        final ExecutorService ex = Executors.newSingleThreadExecutor();
        final Future<byte[]> f = ex.submit(c);

        try {
            return f.get(READ_TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            requestRead.cancel();
            throw new IOException("Error reading data.", e);
        } finally {
            f.cancel(true);
            requestRead.close();
        }
    }

    /**
     * Send bytes to the OnlyKey with a timeout of 1 second.
     *
     * @param toSend Byte sequence to send.
     * @throws IOException Thrown on error sending the byte array.
     */
    private synchronized void send(final byte[] toSend) throws IOException {
        final UsbRequest requestWrite = new UsbRequest();

        final Callable<Void> c = () -> {
            if (!requestWrite.initialize(conn, epOut)) {
                throw new IOException("Request could not initialize out request!");
            }

            final ByteBuffer b = ByteBuffer.allocate(epOut.getMaxPacketSize());

            b.put(toSend);

            if (!requestWrite.queue(b, epOut.getMaxPacketSize())) {
                throw new IOException("Error queuing request!");
            }

            final UsbRequest r = conn.requestWait();

            if (r == null) {
                throw new IOException("Error sending data. Returned request is null.");
            } else if (!r.equals(requestWrite)) {
                final int recIntNum = r.getEndpoint().getEndpointNumber();
                if (recIntNum != epOut.getEndpointNumber()) {
                    Log.w(TAG, "Completed request not from epOut. (" + recIntNum + ").");
                }
            }

            return null;
        };

        final ExecutorService ex = Executors.newSingleThreadExecutor();

        final Future<Void> f = ex.submit(c);
        try {
            f.get(1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            requestWrite.cancel();
            throw new IOException("Error sending data.", e);
        } finally {
            f.cancel(true);
            requestWrite.close();
        }
    }

    /**
     * Sends the time-set message using the current system time.
     *
     * @throws IOException Thrown on I/O error sending the message.
     */
    private void sendOKSETTIME() throws IOException {
        // create packet
        final byte[] toSend = new byte[9];
        // add header
        System.arraycopy(header, 0, toSend, 0, header.length);
        // set message
        toSend[4] = MSG_SET_TIME;
        // copy in time
        System.arraycopy(getTime(), 0, toSend, 5, 4);

        send(toSend);

        Log.d(TAG, "Set time on OnlyKey.");
        //Log.d(TAG, "Set time with " + bytesToHex(toSend));
    }

    /**
     * @return Current epoch type as 4 bytes, big endian
     */
    private byte[] getTime() {
        final int unixTime = (int) (System.currentTimeMillis() / 1000);
        return new byte[]{(byte) (unixTime >> 24), (byte) (unixTime >> 16), (byte) (unixTime >> 8),
                (byte) unixTime};
    }

//    private final static char[] hexArray = "0123456789ABCDEF".toCharArray();
//
//    private static String bytesToHex(byte[] bytes) {
//        char[] hexChars = new char[bytes.length * 2];
//        for (int j = 0; j < bytes.length; j++) {
//            int v = bytes[j] & 0xFF;
//            hexChars[j * 2] = hexArray[v >>> 4];
//            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
//        }
//        return new String(hexChars);
//    }
}
