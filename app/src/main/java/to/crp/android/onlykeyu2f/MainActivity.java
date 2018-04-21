package to.crp.android.onlykeyu2f;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.makeText;

/**
 * App to set the time on an OnlyKey when it is inserted into the Android device.
 */
public class MainActivity extends Activity implements ServiceCallback {

    private static final String TAG = "okd-main";

    private OKService myService;

    private boolean bound = false;

    private static final String TAG_RESULT_DATA = "resultData";

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(final ComponentName name, final IBinder service) {
            // cast the Ibinder and get OKService instance
            final OKService.LocalBinder binder = (OKService.LocalBinder) service;

            myService = binder.getService();
            bound = true;
            myService.setCallback(MainActivity.this); // register
        }

        @Override
        public void onServiceDisconnected(final ComponentName name) {
            bound = false;
        }
    };

    private void unbind() {
        if (bound) {
            myService.setCallback(null);
            unbindService(serviceConnection);
            bound = false;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        Log.d(TAG, "onStart()");

        // don't launch manually
        final String action = getIntent().getAction() == null ? "" : getIntent().getAction();
        if (action.equals("android.intent.action.MAIN")) {
            toast(getString(R.string.launch_err));
            Log.d(TAG, getString(R.string.launch_err));
            finishAndRemoveTask();
            return;
        }

        Intent intent = new Intent(this, OKService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);

        intent = new Intent(this, OKService.class);
        intent.putExtra(Intent.EXTRA_INTENT, getIntent());

        startService(intent);

        moveTaskToBack(false);
    }

    @Override
    public void resultData(final String resultData) {
        if (resultData.isEmpty())
            Log.d(TAG, "Finished processing.");
        else {
            getIntent().putExtra(TAG_RESULT_DATA, resultData);
            setResult(Activity.RESULT_OK, getIntent());
            Log.d(TAG, "Set result intent. Finished processing.");
        }

        unbind();

        finishAndRemoveTask();
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);

        Log.d(TAG, "onNewIntent(): " + intent.getAction());

        startService(intent);

        moveTaskToBack(false);
    }

    private void toast(final String msg) {
        runOnUiThread(() -> makeText(getApplicationContext(), msg, LENGTH_LONG).show());
    }
}
