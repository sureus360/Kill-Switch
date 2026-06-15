package de.killswitch.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class UnlockScheduler {
    private static final String PREFS = "kill_switch_unlock_schedules";
    private static final String ACTION_UNLOCK = "de.killswitch.app.ACTION_SCHEDULED_UNLOCK";

    private UnlockScheduler() {
    }

    static void schedule(Context context, NetworkDevice device, long unlockAt) throws Exception {
        JSONObject json = new JSONObject();
        json.put("key", device.key());
        json.put("name", device.name);
        json.put("ip", device.ipAddress);
        json.put("mac", device.macAddress);
        json.put("interface", device.interfaceType);
        json.put("unlockAt", unlockAt);
        preferences(context).edit().putString(device.key(), json.toString()).apply();
        scheduleAlarm(context, device.key(), unlockAt);
    }

    static void cancel(Context context, String deviceKey) {
        preferences(context).edit().remove(deviceKey).apply();
        alarmManager(context).cancel(pendingIntent(context, deviceKey));
    }

    static long getUnlockAt(Context context, String deviceKey) {
        Entry entry = get(context, deviceKey);
        return entry == null ? 0 : entry.unlockAt;
    }

    static Entry get(Context context, String deviceKey) {
        String raw = preferences(context).getString(deviceKey, "");
        if (TextTools.isBlank(raw)) {
            return null;
        }
        try {
            JSONObject json = new JSONObject(raw);
            return new Entry(
                    json.optString("key", deviceKey),
                    json.optString("name"),
                    json.optString("ip"),
                    json.optString("mac"),
                    json.optString("interface"),
                    json.optLong("unlockAt")
            );
        } catch (Exception ignored) {
            cancel(context, deviceKey);
            return null;
        }
    }

    static void rescheduleAll(Context context) {
        for (Map.Entry<String, ?> value : preferences(context).getAll().entrySet()) {
            Entry entry = get(context, value.getKey());
            if (entry != null) {
                scheduleAlarm(context, entry.key, Math.max(System.currentTimeMillis() + 1000, entry.unlockAt));
            }
        }
    }

    static void retry(Context context, String deviceKey, long delayMillis) {
        if (get(context, deviceKey) != null) {
            scheduleAlarm(context, deviceKey, System.currentTimeMillis() + delayMillis);
        }
    }

    static boolean canScheduleExact(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        return alarmManager(context).canScheduleExactAlarms();
    }

    static List<Entry> entries(Context context) {
        List<Entry> result = new ArrayList<>();
        for (String key : preferences(context).getAll().keySet()) {
            Entry entry = get(context, key);
            if (entry != null) {
                result.add(entry);
            }
        }
        return result;
    }

    private static void scheduleAlarm(Context context, String deviceKey, long triggerAt) {
        AlarmManager manager = alarmManager(context);
        PendingIntent pendingIntent = pendingIntent(context, deviceKey);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
        }
    }

    private static PendingIntent pendingIntent(Context context, String deviceKey) {
        Intent intent = new Intent(context, ScheduledUnlockReceiver.class);
        intent.setAction(ACTION_UNLOCK);
        intent.setData(Uri.parse("killswitch://unlock/" + Uri.encode(deviceKey)));
        intent.putExtra("deviceKey", deviceKey);
        return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static AlarmManager alarmManager(Context context) {
        return (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    }

    static final class Entry {
        final String key;
        final String name;
        final String ipAddress;
        final String macAddress;
        final String interfaceType;
        final long unlockAt;

        Entry(
                String key,
                String name,
                String ipAddress,
                String macAddress,
                String interfaceType,
                long unlockAt
        ) {
            this.key = key;
            this.name = name;
            this.ipAddress = ipAddress;
            this.macAddress = macAddress;
            this.interfaceType = interfaceType;
            this.unlockAt = unlockAt;
        }

        NetworkDevice device() {
            return new NetworkDevice(name, ipAddress, macAddress, interfaceType, false, true);
        }
    }
}
