/*
 *******************************************************************************
 * Adapted from:
 *
 *   Android U2F USB Bridge
 *   (c) 2016 Ledger
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *   limitations under the License.
 ********************************************************************************/

package to.crp.android.u2f;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbRequest;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class U2FTransportAndroidHID {

    private static final String TAG = "okd-transAndHID";

    private static final byte CMD_INIT = (byte) 0x86;
    private static final byte CMD_MSG = (byte) 0x83;

    private static final int HID_BUFFER_SIZE = 64;

    private final UsbDeviceConnection connection;
    private final UsbEndpoint in;
    private final UsbEndpoint out;
    private final U2FHelper helper;
    private final byte[] transferBuffer;
    private final SecureRandom secureRandom;

    public U2FTransportAndroidHID(final UsbDeviceConnection connection, final UsbEndpoint in,
                                  final UsbEndpoint out) {
        this.connection = connection;
        this.in = in;
        this.out = out;
        transferBuffer = new byte[HID_BUFFER_SIZE];
        helper = new U2FHelper();
        secureRandom = new SecureRandom();

        if (in == null) Log.e(TAG, "IN endpoint is null!");
        if (out == null) Log.e(TAG, "OUT endpoint is null!");
    }

    /**
     * Initialize the HID transport.
     *
     * @throws IOException Thrown on initialization error.
     */
    public void init() throws IOException {
        Log.d(TAG, "Initializing HID transport...");

        final byte nonce[] = new byte[8];
        secureRandom.nextBytes(nonce);
        final byte[] response = exchange(CMD_INIT, nonce);
        Log.d(TAG, "Received response to CMD_INIT.");

        final byte[] readNonce = new byte[8];
        System.arraycopy(response, 0, readNonce, 0, 8);
        if (!Arrays.equals(nonce, readNonce)) {
            throw new IOException("Invalid channel initialization.");
        }
        final int channel = ((response[8] & 0xff) << 24) | ((response[9] & 0xff) << 16) | (
                (response[10] & 0xff) << 8) | (response[11] & 0xff);
        helper.setChannel(channel);

        Log.d(TAG, "HID transport initialized.");
    }

    /**
     * Send a {@link #CMD_MSG} payload request and get a response.
     *
     * @param payload payload data
     * @return The response payload.
     * @throws IOException Thrown on error sending/receiving data.
     */
    public byte[] exchange(final byte[] payload) throws IOException {
        return exchange(CMD_MSG, payload);
    }

    /**
     * Send a request and get a response.
     *
     * @param cmdId   command identifier
     * @param payload payload data
     * @return The response payload.
     * @throws IOException Thrown on error sending/receiving data.
     */
    private byte[] exchange(final byte cmdId, final byte[] payload) throws IOException { // need
        // timeout on the exchange
        //Log.d(TAG, "=> " + Dump.dump(command));

        final byte[] toSend = helper.wrapCommandAPDU(cmdId, payload, HID_BUFFER_SIZE);

        final UsbRequest requestWrite = new UsbRequest();
        final Callable<Void> writeTask = () -> {
            if (!requestWrite.initialize(connection, out)) {
                throw new IOException("Request could not be opened!");
            }

            // break send data into multiple HID packets
            int offset = 0;
            while (offset != toSend.length) {
                int blockSize = (toSend.length - offset > HID_BUFFER_SIZE ? HID_BUFFER_SIZE :
                        toSend.length - offset);

                System.arraycopy(toSend, offset, transferBuffer, 0, blockSize);

                //Log.d(TAG, "wire => " + Dump.dump(transferBuffer));
                if (!requestWrite.queue(ByteBuffer.wrap(transferBuffer), HID_BUFFER_SIZE)) {
                    throw new IOException("Request could not be queued.");
                }
                connection.requestWait(); // blocking
                offset += blockSize;
            }

            return null;
        };

        final ExecutorService ex = Executors.newSingleThreadExecutor();
        final Future<Void> f = ex.submit(writeTask);
        try {
            f.get(1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            requestWrite.cancel();
            throw new IOException("Error writing data!", e);
        } finally {
            requestWrite.close();
        }

        final UsbRequest requestRead = new UsbRequest();
        final Callable<byte[]> readTask = () -> {
            if (!requestRead.initialize(connection, in)) {
                throw new IOException("Read request could not be opened!");
            }

            // read in HID_BUFFER_SIZE packets from the USB device until we have a valid
            // packet set
            final ByteBuffer responseBuffer =
                    ByteBuffer.allocate(HID_BUFFER_SIZE); // store current read cycle
            final ByteArrayOutputStream response =
                    new ByteArrayOutputStream();     // stream built across read cycles

            final long start = System.currentTimeMillis();
            do {
                responseBuffer.clear();
                if (!requestRead.queue(responseBuffer,
                        HID_BUFFER_SIZE)) {      // read length HID_BUFFER_SIZE into response
                    // buffer
                    throw new IOException("Error queuing request!");
                }
                connection.requestWait();   // blocking

                responseBuffer.rewind();
                responseBuffer.get(transferBuffer, 0, HID_BUFFER_SIZE);

                response.write(transferBuffer, 0, HID_BUFFER_SIZE);

                try {
                    helper.verifyBuffer(response.toByteArray(), HID_BUFFER_SIZE);
                    break;
                } catch (Exception e) {
                    Log.w(TAG, e.getMessage());
                }
            } while (true);
            Log.d(TAG, "Received all data in " + (System.currentTimeMillis() - start) + " ms.");

            return response.toByteArray();
        };

        final Future<byte[]> f2 = ex.submit(readTask);
        try {
            final byte[] r = f2.get(30 * 1000, TimeUnit.MILLISECONDS);
            return helper.unwrapResponseAPDU(cmdId, r, HID_BUFFER_SIZE);
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            requestRead.cancel();
            throw new IOException("Error reading response.", e);
        } finally {
            requestRead.close();
        }
    }
}
