package de.killswitch.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String APP_NAME = "Router Kill Switch";
    private static final String APP_TITLE = "ROUTER KILL SWITCH";
    private static final int BG = Color.rgb(3, 7, 5);
    private static final int PANEL = Color.rgb(8, 18, 12);
    private static final int PANEL_ACTIVE = Color.rgb(10, 31, 18);
    private static final int GREEN = Color.rgb(76, 255, 138);
    private static final int GREEN_MUTED = Color.rgb(72, 150, 98);
    private static final int RED = Color.rgb(255, 75, 96);
    private static final int TEXT = Color.rgb(208, 235, 216);
    private static final int MUTED = Color.rgb(116, 151, 126);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<NetworkDevice> allDevices = new ArrayList<>();
    private final Set<String> favorites = new HashSet<>();
    private final Set<String> selectedDeviceKeys = new HashSet<>();

    private SecureSettings secureSettings;
    private SharedPreferences appPreferences;
    private DeviceAdapter adapter;
    private ListView listView;
    private EditText search;
    private TextView status;
    private TextView summary;
    private TextView empty;
    private ProgressBar progress;
    private Button favoritesFilter;
    private Button allFilter;
    private LinearLayout bulkBar;
    private TextView bulkCount;
    private Button bulkBlock;
    private Button bulkUnblock;
    private Button bulkClear;
    private boolean showFavoritesOnly;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        secureSettings = new SecureSettings(this);
        appPreferences = getSharedPreferences("kill_switch_ui", MODE_PRIVATE);
        favorites.addAll(appPreferences.getStringSet("favorites", Collections.emptySet()));
        setContentView(buildInterface());

        SecureSettings.Settings settings = secureSettings.load();
        if (settings.isComplete()) {
            refreshDevices();
        } else {
            status.setText("● SETUP ERFORDERLICH");
            empty.setText("Keine Router-Zugangsdaten\nTippe oben rechts auf [ CONFIG ]");
            showSettingsDialog();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        UnlockScheduler.rescheduleAll(this);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private View buildInterface() {
        ScanlineLayout root = new ScanlineLayout(this);
        root.setBackgroundColor(BG);
        root.setPadding(dp(14), dp(12), dp(14), dp(10));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                    dp(14) + insets.getSystemWindowInsetLeft(),
                    dp(12) + insets.getSystemWindowInsetTop(),
                    dp(14) + insets.getSystemWindowInsetRight(),
                    dp(10) + insets.getSystemWindowInsetBottom()
            );
            return insets;
        });

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, match());

        content.addView(buildHeader());

        TextView command = label("> scan /home/network --live", 12, GREEN_MUTED);
        command.setPadding(dp(2), dp(8), 0, dp(4));
        content.addView(command);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Gerät, IP oder MAC suchen...");
        search.setHintTextColor(MUTED);
        search.setTextColor(TEXT);
        search.setTextSize(14);
        search.setTypeface(Typeface.MONOSPACE);
        search.setPadding(dp(14), 0, dp(14), 0);
        search.setBackground(panelDrawable(GREEN_MUTED, 1, dp(5), Color.rgb(5, 13, 8)));
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        LinearLayout.LayoutParams searchParams = wide(dp(48));
        searchParams.setMargins(0, dp(4), 0, dp(9));
        content.addView(search, searchParams);
        search.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                applyFilter();
            }
        });

        content.addView(buildFilters());

        FrameLayout listContainer = new FrameLayout(this);
        listContainer.setBackground(panelDrawable(Color.rgb(24, 61, 35), 1, dp(5), Color.TRANSPARENT));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        );
        listParams.setMargins(0, dp(8), 0, dp(8));
        content.addView(listContainer, listParams);

        listView = new ListView(this);
        listView.setDividerHeight(dp(7));
        listView.setDivider(null);
        listView.setPadding(dp(7), dp(7), dp(7), dp(7));
        listView.setClipToPadding(false);
        adapter = new DeviceAdapter();
        listView.setAdapter(adapter);
        listContainer.addView(listView, match());

        empty = label("Noch keine Geräte geladen", 14, MUTED);
        empty.setGravity(Gravity.CENTER);
        empty.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        listContainer.addView(empty, match());
        listView.setEmptyView(empty);

        content.addView(buildBulkBar());
        content.addView(buildFooter());
        return root;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView title = label(APP_TITLE, 22, GREEN);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setLetterSpacing(0.08f);
        titles.addView(title);

        status = label("● OFFLINE", 11, RED);
        status.setPadding(dp(2), dp(1), 0, 0);
        titles.addView(status);

        Button config = actionButton("[ CONFIG ]", GREEN_MUTED);
        config.setOnClickListener(view -> showSettingsDialog());
        header.addView(config, new LinearLayout.LayoutParams(dp(104), dp(42)));
        return header;
    }

    private View buildFilters() {
        LinearLayout filters = new LinearLayout(this);
        filters.setGravity(Gravity.CENTER_VERTICAL);

        allFilter = actionButton("ALLE", GREEN);
        allFilter.setOnClickListener(view -> {
            showFavoritesOnly = false;
            updateFilterButtons();
            applyFilter();
        });
        filters.addView(allFilter, new LinearLayout.LayoutParams(dp(82), dp(38)));

        favoritesFilter = actionButton("★ FAVORITEN", GREEN_MUTED);
        favoritesFilter.setOnClickListener(view -> {
            showFavoritesOnly = true;
            updateFilterButtons();
            applyFilter();
        });
        LinearLayout.LayoutParams favoriteParams = new LinearLayout.LayoutParams(dp(138), dp(38));
        favoriteParams.setMargins(dp(8), 0, 0, 0);
        filters.addView(favoritesFilter, favoriteParams);

        summary = label("0 ZIELE", 11, MUTED);
        summary.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        filters.addView(summary, new LinearLayout.LayoutParams(0, dp(38), 1));
        return filters;
    }

    private View buildFooter() {
        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);

        TextView notice = label("WAN-ZUGRIFF // NUR LOKALES NETZ // v" + appVersionName(), 10, MUTED);
        footer.addView(notice, new LinearLayout.LayoutParams(0, dp(42), 1));
        notice.setGravity(Gravity.CENTER_VERTICAL);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(GREEN));
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(28), dp(28));
        progressParams.setMargins(0, 0, dp(8), 0);
        footer.addView(progress, progressParams);

        Button refresh = actionButton("↻ SCAN", GREEN);
        refresh.setOnClickListener(view -> refreshDevices());
        footer.addView(refresh, new LinearLayout.LayoutParams(dp(102), dp(42)));
        return footer;
    }

    private View buildBulkBar() {
        bulkBar = new LinearLayout(this);
        bulkBar.setGravity(Gravity.CENTER_VERTICAL);
        bulkBar.setPadding(dp(6), dp(4), dp(6), dp(4));
        bulkBar.setBackground(panelDrawable(GREEN, 1, dp(5), PANEL_ACTIVE));
        bulkBar.setVisibility(View.GONE);

        bulkCount = label("0 AUSGEWÄHLT", 11, GREEN);
        bulkCount.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        bulkBar.addView(bulkCount, new LinearLayout.LayoutParams(0, dp(42), 1));
        bulkCount.setGravity(Gravity.CENTER_VERTICAL);

        bulkBlock = actionButton("SPERREN", RED);
        bulkBlock.setOnClickListener(view -> changeBlocked(selectedDevicesForAction(true), true));
        bulkBar.addView(bulkBlock, new LinearLayout.LayoutParams(dp(94), dp(42)));

        bulkUnblock = actionButton("FREIGEBEN", GREEN);
        bulkUnblock.setOnClickListener(view -> changeBlocked(selectedDevicesForAction(false), false));
        LinearLayout.LayoutParams unblockParams = new LinearLayout.LayoutParams(dp(102), dp(42));
        unblockParams.setMargins(dp(5), 0, 0, 0);
        bulkBar.addView(bulkUnblock, unblockParams);

        bulkClear = actionButton("×", GREEN_MUTED);
        bulkClear.setTextSize(20);
        bulkClear.setOnClickListener(view -> {
            selectedDeviceKeys.clear();
            applyFilter();
        });
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        clearParams.setMargins(dp(5), 0, 0, 0);
        bulkBar.addView(bulkClear, clearParams);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50)
        );
        params.setMargins(0, 0, 0, dp(7));
        bulkBar.setLayoutParams(params);
        return bulkBar;
    }

    private void refreshDevices() {
        if (busy) {
            return;
        }
        SecureSettings.Settings settings = secureSettings.load();
        if (!settings.isComplete()) {
            showSettingsDialog();
            return;
        }

        setBusy(true, "● VERBINDE...");
        executor.execute(() -> {
            try {
                List<NetworkDevice> loaded = new FritzBoxClient(settings).loadDevices();
                runOnUiThread(() -> {
                    allDevices.clear();
                    allDevices.addAll(loaded);
                    Set<String> currentKeys = new HashSet<>();
                    for (NetworkDevice device : loaded) {
                        currentKeys.add(device.key());
                        if (!device.blocked && UnlockScheduler.getUnlockAt(this, device.key()) > 0) {
                            UnlockScheduler.cancel(this, device.key());
                        }
                    }
                    selectedDeviceKeys.retainAll(currentKeys);
                    applyFilter();
                    setBusy(false, "● ONLINE // "
                            + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()));
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false, "● VERBINDUNG FEHLGESCHLAGEN");
                    showError(error);
                });
            }
        });
    }

    private void changeBlocked(NetworkDevice device, boolean blocked) {
        changeBlocked(Collections.singletonList(device), blocked);
    }

    private void changeBlocked(List<NetworkDevice> requestedDevices, boolean blocked) {
        List<NetworkDevice> devices = new ArrayList<>();
        for (NetworkDevice device : requestedDevices) {
            if (device.blocked != blocked) {
                devices.add(device);
            }
        }
        if (devices.isEmpty()) {
            Toast.makeText(
                    this,
                    blocked ? "Alle ausgewählten Geräte sind bereits gesperrt."
                            : "Alle ausgewählten Geräte sind bereits freigegeben.",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }
        if (blocked) {
            showBlockTimingDialog(devices);
        } else {
            confirmBlockedChange(devices, false, 0);
        }
    }

    private void showBlockTimingDialog(List<NetworkDevice> devices) {
        String[] options = {
                "OHNE TIMER",
                "DAUER IN MINUTEN...",
                "BIS UHRZEIT..."
        };
        new AlertDialog.Builder(this)
                .setTitle("SPERRDAUER WÄHLEN")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        confirmBlockedChange(devices, true, 0);
                    } else if (which == 1) {
                        showDurationDialog(devices);
                    } else {
                        showUnlockTimeDialog(devices);
                    }
                })
                .setNegativeButton("ABBRECHEN", null)
                .show();
    }

    private void showDurationDialog(List<NetworkDevice> devices) {
        EditText minutes = dialogField("Dauer in Minuten", "60", false);
        minutes.setInputType(InputType.TYPE_CLASS_NUMBER);
        minutes.setSelectAllOnFocus(true);
        int padding = dp(24);
        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setPadding(padding, 0, padding, 0);
        wrapper.addView(minutes, match());

        new AlertDialog.Builder(this)
                .setTitle("SPERRDAUER")
                .setView(wrapper)
                .setNegativeButton("ABBRECHEN", null)
                .setPositiveButton("WEITER", (dialog, which) -> {
                    try {
                        long durationMinutes = Long.parseLong(minutes.getText().toString());
                        if (durationMinutes < 1 || durationMinutes > 525600) {
                            throw new NumberFormatException();
                        }
                        confirmBlockedChange(
                                devices,
                                true,
                                System.currentTimeMillis() + durationMinutes * 60_000
                        );
                    } catch (NumberFormatException error) {
                        Toast.makeText(this, "Bitte eine gültige Minutenzahl eingeben.", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void showUnlockTimeDialog(List<NetworkDevice> devices) {
        Calendar initial = Calendar.getInstance();
        initial.add(Calendar.HOUR_OF_DAY, 1);
        new TimePickerDialog(
                this,
                (view, hourOfDay, minute) -> {
                    Calendar unlock = Calendar.getInstance();
                    unlock.set(Calendar.HOUR_OF_DAY, hourOfDay);
                    unlock.set(Calendar.MINUTE, minute);
                    unlock.set(Calendar.SECOND, 0);
                    unlock.set(Calendar.MILLISECOND, 0);
                    if (unlock.getTimeInMillis() <= System.currentTimeMillis()) {
                        unlock.add(Calendar.DAY_OF_YEAR, 1);
                    }
                    confirmBlockedChange(devices, true, unlock.getTimeInMillis());
                },
                initial.get(Calendar.HOUR_OF_DAY),
                initial.get(Calendar.MINUTE),
                true
        ).show();
    }

    private void confirmBlockedChange(List<NetworkDevice> devices, boolean blocked, long unlockAt) {
        String title = blocked ? "Internetzugriff sperren?" : "Internetzugriff freigeben?";
        StringBuilder message = new StringBuilder();
        message.append(devices.size()).append(devices.size() == 1 ? " Gerät" : " Geräte");
        for (int index = 0; index < Math.min(devices.size(), 5); index++) {
            message.append("\n").append(devices.get(index).name);
        }
        if (devices.size() > 5) {
            message.append("\n...");
        }
        if (unlockAt > 0) {
            message.append("\n\nAutomatische Freigabe: ").append(formatUnlockAt(unlockAt));
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message.toString())
                .setNegativeButton("ABBRECHEN", null)
                .setPositiveButton(blocked ? "SPERREN" : "FREIGEBEN", (dialog, which) ->
                        applyBlockedChange(devices, blocked, unlockAt))
                .show();
    }

    private void applyBlockedChange(List<NetworkDevice> devices, boolean blocked, long unlockAt) {
        setBusy(true, blocked ? "● SPERRE ZIELE..." : "● ENTSPERRE ZIELE...");
        SecureSettings.Settings settings = secureSettings.load();
        executor.execute(() -> {
            List<String> succeeded = new ArrayList<>();
            List<String> failures = new ArrayList<>();
            try {
                FritzBoxClient client = new FritzBoxClient(settings);
                for (NetworkDevice device : devices) {
                    try {
                        client.setBlocked(device, blocked);
                        succeeded.add(device.key());
                        try {
                            if (blocked && unlockAt > 0) {
                                UnlockScheduler.schedule(this, device, unlockAt);
                            } else {
                                UnlockScheduler.cancel(this, device.key());
                            }
                        } catch (Exception scheduleError) {
                            failures.add(device.name + ": Timer konnte nicht gespeichert werden");
                        }
                    } catch (Exception error) {
                        failures.add(device.name + ": " + safeMessage(error));
                    }
                }
            } catch (Exception error) {
                failures.add(safeMessage(error));
            }

            runOnUiThread(() -> {
                for (String key : succeeded) {
                    replaceDevice(key, blocked);
                    selectedDeviceKeys.remove(key);
                }
                setBusy(false, failures.isEmpty() ? "● ONLINE" : "● AKTION TEILWEISE FEHLGESCHLAGEN");
                applyFilter();
                if (!succeeded.isEmpty()) {
                    Toast.makeText(
                            this,
                            "Von der FRITZ!Box bestätigt: " + succeeded.size()
                                    + (blocked ? " gesperrt" : " freigegeben"),
                            Toast.LENGTH_LONG
                    ).show();
                }
                if (blocked && unlockAt > 0 && !succeeded.isEmpty()) {
                    requestExactAlarmAccessIfNeeded();
                }
                if (!failures.isEmpty()) {
                    showError("AKTION FEHLGESCHLAGEN", new FritzException(joinFailures(failures)));
                }
            });
        });
    }

    private List<NetworkDevice> selectedDevicesForAction(boolean blocked) {
        List<NetworkDevice> result = new ArrayList<>();
        for (NetworkDevice device : allDevices) {
            if (selectedDeviceKeys.contains(device.key()) && device.blocked != blocked) {
                result.add(device);
            }
        }
        return result;
    }

    private void replaceDevice(String key, boolean blocked) {
        for (int index = 0; index < allDevices.size(); index++) {
            NetworkDevice device = allDevices.get(index);
            if (device.key().equals(key)) {
                allDevices.set(index, device.withBlocked(blocked));
                return;
            }
        }
    }

    private void requestExactAlarmAccessIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || UnlockScheduler.canScheduleExact(this)
                || appPreferences.getBoolean("exact_alarm_prompted", false)) {
            return;
        }
        appPreferences.edit().putBoolean("exact_alarm_prompted", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("PÜNKTLICHE FREIGABE")
                .setMessage("Android kann Timer im Energiesparmodus verzögern. Erlaube exakte Alarme, "
                        + "damit Geräte möglichst pünktlich freigegeben werden.")
                .setNegativeButton("SPÄTER", null)
                .setPositiveButton("EINSTELLUNGEN", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                })
                .show();
    }

    private String formatUnlockAt(long unlockAt) {
        return new SimpleDateFormat("dd.MM. HH:mm", Locale.GERMAN).format(new Date(unlockAt));
    }

    private String safeMessage(Exception error) {
        return TextTools.isBlank(error.getMessage()) ? error.getClass().getSimpleName() : error.getMessage();
    }

    private String joinFailures(List<String> failures) {
        StringBuilder message = new StringBuilder();
        for (int index = 0; index < Math.min(failures.size(), 5); index++) {
            if (index > 0) {
                message.append('\n');
            }
            message.append(failures.get(index));
        }
        if (failures.size() > 5) {
            message.append("\n...");
        }
        return message.toString();
    }

    private void showSettingsDialog() {
        SecureSettings.Settings current = secureSettings.load();
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), 0);

        TextView hint = label(
                "Lokale TR-064-Verbindung. Trage hier exakt den FRITZ!Box-Benutzernamen ein, "
                        + "der die App verwenden soll. Die App muss nicht zwingend in der "
                        + "FRITZ!Box-App-Liste auftauchen; zum Sperren braucht dieser Benutzer App-Rechte.",
                12,
                Color.DKGRAY
        );
        hint.setPadding(0, 0, 0, dp(8));
        form.addView(hint);

        EditText host = dialogField("Router-Adresse", current.host, false);
        EditText username = dialogField("Benutzername", current.username, false);
        EditText password = dialogField("Passwort", current.password, true);
        form.addView(host, wide(dp(54)));
        form.addView(username, wide(dp(54)));
        form.addView(password, wide(dp(54)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(APP_NAME + " v" + appVersionName())
                .setView(form)
                .setNegativeButton("ABBRECHEN", null)
                .setPositiveButton("SPEICHERN + SCAN", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    SecureSettings.Settings settings = new SecureSettings.Settings(
                            host.getText().toString(),
                            username.getText().toString(),
                            password.getText().toString()
                    );
                    if (!settings.isComplete()) {
                        Toast.makeText(this, "Bitte alle Felder ausfüllen.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        secureSettings.save(settings);
                        dialog.dismiss();
                        refreshDevices();
                    } catch (Exception error) {
                        showError(error);
                    }
                }));
        dialog.show();
    }

    private EditText dialogField(String hint, String value, boolean password) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value);
        field.setSingleLine(true);
        field.setSelectAllOnFocus(password);
        field.setInputType(password
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT);
        return field;
    }

    private void applyFilter() {
        String query = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.GERMAN);
        List<NetworkDevice> filtered = new ArrayList<>();
        for (NetworkDevice device : allDevices) {
            boolean matchesFavorite = !showFavoritesOnly || favorites.contains(device.key());
            boolean matchesSearch = TextTools.isBlank(query)
                    || device.name.toLowerCase(Locale.GERMAN).contains(query)
                    || device.ipAddress.toLowerCase(Locale.GERMAN).contains(query)
                    || device.macAddress.toLowerCase(Locale.GERMAN).contains(query);
            if (matchesFavorite && matchesSearch) {
                filtered.add(device);
            }
        }
        adapter.setDevices(filtered);
        updateBulkBar();

        int online = 0;
        int blocked = 0;
        for (NetworkDevice device : allDevices) {
            if (device.active) {
                online++;
            }
            if (device.blocked) {
                blocked++;
            }
        }
        summary.setText(allDevices.size() + " ZIELE // " + online + " AKTIV // " + blocked + " GESPERRT");
        empty.setText(showFavoritesOnly
                ? "Keine Favoriten gefunden"
                : "Keine Geräte gefunden");
    }

    private void toggleFavorite(NetworkDevice device) {
        if (!favorites.add(device.key())) {
            favorites.remove(device.key());
        }
        appPreferences.edit().putStringSet("favorites", new HashSet<>(favorites)).apply();
        applyFilter();
    }

    private void toggleSelected(NetworkDevice device) {
        if (!selectedDeviceKeys.add(device.key())) {
            selectedDeviceKeys.remove(device.key());
        }
        applyFilter();
    }

    private void updateBulkBar() {
        if (bulkBar == null) {
            return;
        }
        int count = selectedDeviceKeys.size();
        bulkBar.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        bulkCount.setText(count + " AUSGEWÄHLT");
        bulkBlock.setEnabled(!busy && !selectedDevicesForAction(true).isEmpty());
        bulkUnblock.setEnabled(!busy && !selectedDevicesForAction(false).isEmpty());
        bulkClear.setEnabled(!busy);
    }

    private void updateFilterButtons() {
        allFilter.setBackground(panelDrawable(showFavoritesOnly ? GREEN_MUTED : GREEN, 1, dp(4), PANEL));
        favoritesFilter.setBackground(panelDrawable(showFavoritesOnly ? GREEN : GREEN_MUTED, 1, dp(4), PANEL));
    }

    private void setBusy(boolean isBusy, String statusText) {
        busy = isBusy;
        progress.setVisibility(isBusy ? View.VISIBLE : View.GONE);
        status.setText(statusText);
        status.setTextColor(statusText.contains("ONLINE") ? GREEN : statusText.contains("FEHL") ? RED : GREEN_MUTED);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateBulkBar();
    }

    private void showError(Exception error) {
        showError("VERBINDUNGSFEHLER", error);
    }

    private void showError(String title, Exception error) {
        String message = error.getMessage();
        if (TextTools.isBlank(message)) {
            message = error.getClass().getSimpleName();
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message + "\n\nPrüfe WLAN, Zugangsdaten und den aktivierten "
                        + "Zugriff für Anwendungen in der FRITZ!Box.")
                .setPositiveButton("OK", null);
        if (looksLikeUserRightsProblem(message)) {
            builder.setNegativeButton("CONFIG", (dialog, which) -> showSettingsDialog());
        }
        builder.show();
    }

    private boolean looksLikeUserRightsProblem(String message) {
        String normalized = message.toLowerCase(Locale.GERMAN);
        return normalized.contains("action not authorized")
                || normalized.contains("606")
                || normalized.contains("benutzer")
                || normalized.contains("app-rechte")
                || normalized.contains("einstellungsrechte");
    }

    private String appVersionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "?" : info.versionName;
        } catch (Exception ignored) {
            return "?";
        }
    }

    private Button actionButton(String text, int borderColor) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(borderColor);
        button.setTextSize(11);
        button.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        button.setAllCaps(false);
        button.setPadding(dp(5), 0, dp(5), 0);
        button.setBackground(panelDrawable(borderColor, 1, dp(4), PANEL));
        return button;
    }

    private TextView label(String text, int sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setTypeface(Typeface.MONOSPACE);
        return view;
    }

    private GradientDrawable panelDrawable(int strokeColor, int strokeDp, int radius, int fillColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(strokeDp), strokeColor);
        return drawable;
    }

    private LinearLayout.LayoutParams wide(int height) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class DeviceAdapter extends BaseAdapter {
        private final List<NetworkDevice> devices = new ArrayList<>();

        void setDevices(List<NetworkDevice> replacement) {
            devices.clear();
            devices.addAll(replacement);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return devices.size();
        }

        @Override
        public NetworkDevice getItem(int position) {
            return devices.get(position);
        }

        @Override
        public long getItemId(int position) {
            return getItem(position).key().hashCode();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row row;
            if (convertView instanceof LinearLayout && convertView.getTag() instanceof Row) {
                row = (Row) convertView.getTag();
            } else {
                row = createRow();
                convertView = row.root;
                convertView.setTag(row);
            }

            NetworkDevice device = getItem(position);
            boolean selected = selectedDeviceKeys.contains(device.key());
            row.name.setText(device.name);
            row.meta.setText(device.ipAddress + "  //  "
                    + (TextTools.isBlank(device.macAddress) ? "MAC UNBEKANNT" : device.macAddress));
            String link = TextTools.isBlank(device.interfaceType)
                    ? "NETZWERK"
                    : device.interfaceType.toUpperCase(Locale.GERMAN);
            long unlockAt = UnlockScheduler.getUnlockAt(MainActivity.this, device.key());
            row.state.setText((device.active ? "● AKTIV" : "○ OFFLINE")
                    + "  //  " + link
                    + "  //  " + (device.blocked ? "WAN GESPERRT" : "WAN FREI")
                    + (unlockAt > 0 ? "  //  AUTO-FREI " + formatUnlockAt(unlockAt) : ""));
            row.state.setTextColor(device.blocked ? RED : device.active ? GREEN : MUTED);
            row.select.setText(selected ? "■" : "□");
            row.select.setTextColor(selected ? GREEN : GREEN_MUTED);
            row.select.setOnClickListener(view -> toggleSelected(device));
            row.favorite.setText(favorites.contains(device.key()) ? "★" : "☆");
            row.favorite.setTextColor(favorites.contains(device.key()) ? GREEN : GREEN_MUTED);
            row.favorite.setOnClickListener(view -> toggleFavorite(device));
            row.block.setText(device.blocked ? "ENTSPERREN" : "SPERREN");
            row.block.setTextColor(device.blocked ? GREEN : RED);
            row.block.setBackground(panelDrawable(device.blocked ? GREEN_MUTED : RED, 1, dp(4), PANEL));
            row.block.setEnabled(!busy && !TextTools.isBlank(device.ipAddress));
            row.block.setOnClickListener(view -> changeBlocked(device, !device.blocked));
            row.root.setBackground(panelDrawable(
                    selected ? GREEN : device.blocked ? RED : device.active ? GREEN_MUTED : Color.rgb(35, 65, 44),
                    1,
                    dp(5),
                    selected ? Color.rgb(12, 42, 23) : device.active ? PANEL_ACTIVE : PANEL
            ));
            return convertView;
        }

        private Row createRow() {
            LinearLayout root = new LinearLayout(MainActivity.this);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(dp(12), dp(10), dp(8), dp(10));
            root.setMinimumHeight(dp(92));

            Button select = actionButton("□", GREEN_MUTED);
            select.setTextSize(18);
            LinearLayout.LayoutParams selectParams = new LinearLayout.LayoutParams(dp(38), dp(44));
            selectParams.setMargins(0, 0, dp(5), 0);
            root.addView(select, selectParams);

            LinearLayout info = new LinearLayout(MainActivity.this);
            info.setOrientation(LinearLayout.VERTICAL);
            root.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            TextView name = label("", 15, TEXT);
            name.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            name.setSingleLine(true);
            info.addView(name);

            TextView meta = label("", 10, MUTED);
            meta.setSingleLine(true);
            meta.setPadding(0, dp(4), 0, dp(3));
            info.addView(meta);

            TextView state = label("", 10, GREEN);
            state.setSingleLine(true);
            info.addView(state);

            Button favorite = actionButton("☆", GREEN_MUTED);
            favorite.setTextSize(22);
            LinearLayout.LayoutParams favoriteParams = new LinearLayout.LayoutParams(dp(46), dp(44));
            favoriteParams.setMargins(dp(6), 0, dp(6), 0);
            root.addView(favorite, favoriteParams);

            Button block = actionButton("SPERREN", RED);
            root.addView(block, new LinearLayout.LayoutParams(dp(104), dp(44)));
            return new Row(root, name, meta, state, select, favorite, block);
        }
    }

    private static final class Row {
        final LinearLayout root;
        final TextView name;
        final TextView meta;
        final TextView state;
        final Button select;
        final Button favorite;
        final Button block;

        Row(
                LinearLayout root,
                TextView name,
                TextView meta,
                TextView state,
                Button select,
                Button favorite,
                Button block
        ) {
            this.root = root;
            this.name = name;
            this.meta = meta;
            this.state = state;
            this.select = select;
            this.favorite = favorite;
            this.block = block;
        }
    }

    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence text, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence text, int start, int before, int count) {
        }
    }

    private static final class ScanlineLayout extends FrameLayout {
        private final Paint paint = new Paint();

        ScanlineLayout(Context context) {
            super(context);
            setWillNotDraw(false);
            paint.setColor(Color.argb(14, 76, 255, 138));
            paint.setStrokeWidth(1);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            for (int y = 0; y < getHeight(); y += 6) {
                canvas.drawLine(0, y, getWidth(), y, paint);
            }
        }
    }
}
