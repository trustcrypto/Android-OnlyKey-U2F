package to.crp.android.onlykeyu2f;

import android.app.IntentService;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.annotation.Nullable;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.util.Map;

import to.crp.android.u2f.U2FContext;

public final class OKService extends IntentService {

    private static final String TAG = "okd-service";

    private static final String ACTION_USB_PERMISSION = "to.crp.android.onlykeyu2f.USB_PERMISSION";
    private static final String ACTION_GOOGLE =
            "com.google.android.apps.authenticator.AUTHENTICATE";

    private static final String TAG_REQUEST = "request";

    private final IBinder binder = new LocalBinder();

    private Context appContext;
    private UsbManager manager;
    private ServiceCallback callback;

    private UsbDevice d;
    private boolean needsInit = false;

    private final BroadcastReceiver bReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) throw new RuntimeException("Received NULL intent.");

            if (ACTION_USB_PERMISSION.equalsIgnoreCase(intent.getAction())) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    Log.d(TAG, "Permission granted.");

//                    final UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
//
//                    if (dev == null) throw new RuntimeException(
//                            "Expect non-null UsbDevice from permission intent!");

                    try {
                        addOnlyKey();
                    } catch (IOException ioe) {
                        handleError(ioe);
                    }
                } else {
                    handleError(new Exception(getString(R.string.err_perm_denied)));
                }
            }
        }
    };

    private final OKListener okListener = new OKListener() {
        @Override
        public void okDone(final OKEvent event) {
            Log.d(TAG, "Done. " + (event.getBoolVal() ? "U2F processing completed." :
                    "Time was set."));

            if (callback != null)
                callback.resultData("");

            stopSelf();
        }

        @Override
        public void okError(final OKEvent event) {
            handleError(event.getException());
        }

        @Override
        public void okMessage(final OKEvent event) {
            toast(event.getStringVal());
        }

        @Override
        public void okSetInitialized(final OKEvent event) {
            final boolean initialized = event.getBoolVal();
            final String msg = initialized ? getString(R.string.msg_waiting_for_unlock) : getString(
                    R.string.msg_setup_required);

            Log.d(TAG, (initialized ? "Initialized; " + "Waiting for unlock." :
                    "Not initialized; Setup required."));
            toast(msg);
        }

        @Override
        public void okSetLocked(final OKEvent event) {
            final boolean locked = event.getBoolVal();
            final String msg = locked ? getString(R.string.msg_dev_locked) :
                    getString(R.string.msg_dev_unlocked);

            Log.d(TAG, (locked ? " Locked." : "Unlocked" + "."));
            toast(msg);
        }

        @Override
        public void okSetTime(final OKEvent event) {
            Log.d(TAG, "OK time set.");
        }

        @Override
        public void u2fResponse(final OKEvent event) {
            if (callback != null)
                callback.resultData(event.getStringVal());
        }
    };

    private @Nullable OnlyKey key;

    private U2FContext u2fContext;

    public OKService() {
        super("OKService");
    }

    private void addOnlyKey() throws IOException {
        final OnlyKey k = OnlyKey.getOnlyKey(d, manager);
        k.addListener(okListener);

        if (u2fContext != null)
            k.addU2fRequest(u2fContext);

        if (needsInit)
            k.init();
        else
            k.doU2fProcessing();

        key = k;
    }

    private void handleError(final Exception e) {
        toastLong(getString(R.string.msg_error) + ": " + e.getMessage());
        Log.e(TAG, e.getMessage(), e);

        if (callback != null) {
            callback.resultData("");
        }

        stopSelf();
    }

    private void toastLong(final String msg) {
        toast(msg, false);
    }

    private void toast(final String msg) {
        toast(msg, true);
    }

    private void toast(final String msg, final boolean isShort) {
        new Handler(Looper.getMainLooper()).post(
                () -> Toast
                        .makeText(appContext, msg, isShort ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG)
                        .show());
    }

    private void permissionsCheck() throws IOException {
        if (manager.hasPermission(d)) {
            Log.d(TAG, "Have permission.");
            addOnlyKey();
        } else {
            Log.d(TAG, "Requesting permission.");
            final PendingIntent mPermissionIntent = PendingIntent
                    .getBroadcast(getApplicationContext(), 0, new Intent(ACTION_USB_PERMISSION), 0);
            manager.requestPermission(d, mPermissionIntent);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(bReceiver);
    }

    @Override
    protected void onHandleIntent(final Intent intent) {
        appContext = getBaseContext();
        manager = (UsbManager) getSystemService(Context.USB_SERVICE);

        final IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        registerReceiver(bReceiver, filter);

        final Intent origIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT);
        final String action = origIntent.getAction();

        try {
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equalsIgnoreCase(action)) {
                d = origIntent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                needsInit = true;

                Log.d(TAG, "Attached.");
                toast(getString(R.string.msg_ok_attached));

                permissionsCheck();

            } else if (ACTION_GOOGLE.equalsIgnoreCase(action)) {
                Log.d(TAG, "Received U2F request.");
                final String request = origIntent.getStringExtra(TAG_REQUEST);
                if (request == null) {
                    Log.e(TAG, "Authenticator intent missing U2F request data.");
                    stopSelf();
                    return;
                }

                final U2FContext u2fContext = U2FContext.parseU2FContext(request);

                if (key == null) {
                    final Map<String, UsbDevice> devs = manager.getDeviceList();
                    if (devs.isEmpty())
                        throw new IOException("No OnlyKey present!");

                    if (devs.size() > 1)
                        throw new IOException("No support for multiple OnlyKeys attached at once!");

                    Log.d(TAG, "Found an already-connected OnlyKey.");

                    d = devs.values().iterator().next();

                    this.u2fContext = u2fContext;

                    permissionsCheck(); // creates OnlyKeys on permission granted intent
                } else {
                    key.addU2fRequest(u2fContext);
                    Log.w(TAG, "Added u2fContext.");
                }
            } else {
                Log.e(TAG, "Unhandled intent action: " + origIntent.getAction());
            }
        } catch (Exception e) {
            handleError(e);
        }
    }

    /**
     * Set callback for receiving result of the U2F request.
     *
     * @param callback The callback object.
     */
    public void setCallback(final @Nullable ServiceCallback callback) {
        this.callback = callback;
    }

    class LocalBinder extends Binder {
        OKService getService() {
            // return this instance so clients can call public methods
            return OKService.this;
        }
    }
}
