package de.killswitch.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ScheduledUnlockJobService extends JobService {
    private static final String TAG = "KillSwitchUnlock";
    private static final String EXTRA_DEVICE_KEY = "deviceKey";
    private static final long RETRY_DELAY_MS = 5 * 60 * 1000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    static boolean enqueue(Context context, String deviceKey) {
        PersistableBundle extras = new PersistableBundle();
        extras.putString(EXTRA_DEVICE_KEY, deviceKey);
        JobInfo job = new JobInfo.Builder(
                100_000 + (deviceKey.hashCode() & 0x3fffffff),
                new ComponentName(context, ScheduledUnlockJobService.class)
        ).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(RETRY_DELAY_MS, JobInfo.BACKOFF_POLICY_LINEAR)
                .setExtras(extras)
                .build();
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS;
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        executor.execute(() -> {
            boolean retry = false;
            String deviceKey = params.getExtras().getString(EXTRA_DEVICE_KEY, "");
            try {
                UnlockScheduler.Entry entry = UnlockScheduler.get(this, deviceKey);
                if (entry == null) {
                    return;
                }
                SecureSettings.Settings settings = new SecureSettings(this).load();
                if (!settings.isComplete()) {
                    throw new FritzException("Keine Router-Zugangsdaten fuer geplante Freigabe.");
                }

                new FritzBoxClient(settings).setBlocked(entry.device(), false);
                UnlockScheduler.cancel(this, deviceKey);
                Log.i(TAG, "Scheduled unlock completed for " + deviceKey);
            } catch (Exception error) {
                retry = true;
                Log.w(TAG, "Scheduled unlock failed; retrying", error);
            } finally {
                jobFinished(params, retry);
            }
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
