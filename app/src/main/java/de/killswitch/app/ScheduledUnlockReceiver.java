package de.killswitch.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class ScheduledUnlockReceiver extends BroadcastReceiver {
    private static final String TAG = "KillSwitchUnlock";
    private static final long RETRY_DELAY_MS = 5 * 60 * 1000;

    @Override
    public void onReceive(Context context, Intent intent) {
        String deviceKey = intent.getStringExtra("deviceKey");
        if (TextTools.isBlank(deviceKey)) {
            return;
        }
        if (!ScheduledUnlockJobService.enqueue(context, deviceKey)) {
            Log.w(TAG, "Could not enqueue scheduled unlock job; retrying alarm");
            UnlockScheduler.retry(context, deviceKey, RETRY_DELAY_MS);
        }
    }
}
