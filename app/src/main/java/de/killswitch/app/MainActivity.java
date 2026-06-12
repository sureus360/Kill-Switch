package de.killswitch.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
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

        content.addView(buildFooter());
        return root;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView title = label("KILL SWITCH", 28, GREEN);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setLetterSpacing(0.12f);
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

        TextView notice = label("WAN-ZUGRIFF // NUR LOKALES NETZ", 10, MUTED);
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
        String title = blocked ? "Internetzugriff sperren?" : "Internetzugriff freigeben?";
        String message = device.name + "\n" + device.ipAddress;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("ABBRECHEN", null)
                .setPositiveButton(blocked ? "SPERREN" : "FREIGEBEN", (dialog, which) -> {
                    setBusy(true, blocked ? "● SPERRE ZIEL..." : "● ENTSPERRE ZIEL...");
                    SecureSettings.Settings settings = secureSettings.load();
                    executor.execute(() -> {
                        try {
                            new FritzBoxClient(settings).setBlocked(device, blocked);
                            runOnUiThread(() -> {
                                replaceDevice(device, device.withBlocked(blocked));
                                setBusy(false, "● ONLINE");
                                Toast.makeText(
                                        this,
                                        blocked
                                                ? "Von der FRITZ!Box bestaetigt: Internetzugriff gesperrt"
                                                : "Von der FRITZ!Box bestaetigt: Internetzugriff freigegeben",
                                        Toast.LENGTH_SHORT
                                ).show();
                            });
                        } catch (Exception error) {
                            runOnUiThread(() -> {
                                setBusy(false, "● AKTION FEHLGESCHLAGEN");
                                showError("AKTION FEHLGESCHLAGEN", error);
                            });
                        }
                    });
                })
                .show();
    }

    private void replaceDevice(NetworkDevice oldDevice, NetworkDevice replacement) {
        int index = allDevices.indexOf(oldDevice);
        if (index >= 0) {
            allDevices.set(index, replacement);
        }
        applyFilter();
    }

    private void showSettingsDialog() {
        SecureSettings.Settings current = secureSettings.load();
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), 0);

        TextView hint = label(
                "Lokale TR-064-Verbindung. Das Lesen kann auch ohne Schreibrecht "
                        + "funktionieren. Zum Sperren braucht der Benutzer App-/Einstellungsrechte.",
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
                .setTitle("VERBINDUNG KONFIGURIEREN")
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

    private void updateFilterButtons() {
        allFilter.setBackground(panelDrawable(showFavoritesOnly ? GREEN_MUTED : GREEN, 1, dp(4), PANEL));
        favoritesFilter.setBackground(panelDrawable(showFavoritesOnly ? GREEN : GREEN_MUTED, 1, dp(4), PANEL));
    }

    private void setBusy(boolean isBusy, String statusText) {
        busy = isBusy;
        progress.setVisibility(isBusy ? View.VISIBLE : View.GONE);
        status.setText(statusText);
        status.setTextColor(statusText.contains("ONLINE") ? GREEN : statusText.contains("FEHL") ? RED : GREEN_MUTED);
    }

    private void showError(Exception error) {
        showError("VERBINDUNGSFEHLER", error);
    }

    private void showError(String title, Exception error) {
        String message = error.getMessage();
        if (TextTools.isBlank(message)) {
            message = error.getClass().getSimpleName();
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message + "\n\nPrüfe WLAN, Zugangsdaten und den aktivierten "
                        + "Zugriff für Anwendungen in der FRITZ!Box.")
                .setPositiveButton("OK", null)
                .show();
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
            row.name.setText(device.name);
            row.meta.setText(device.ipAddress + "  //  "
                    + (TextTools.isBlank(device.macAddress) ? "MAC UNBEKANNT" : device.macAddress));
            String link = TextTools.isBlank(device.interfaceType)
                    ? "NETZWERK"
                    : device.interfaceType.toUpperCase(Locale.GERMAN);
            row.state.setText((device.active ? "● AKTIV" : "○ OFFLINE")
                    + "  //  " + link
                    + "  //  " + (device.blocked ? "WAN GESPERRT" : "WAN FREI"));
            row.state.setTextColor(device.blocked ? RED : device.active ? GREEN : MUTED);
            row.favorite.setText(favorites.contains(device.key()) ? "★" : "☆");
            row.favorite.setTextColor(favorites.contains(device.key()) ? GREEN : GREEN_MUTED);
            row.favorite.setOnClickListener(view -> toggleFavorite(device));
            row.block.setText(device.blocked ? "ENTSPERREN" : "SPERREN");
            row.block.setTextColor(device.blocked ? GREEN : RED);
            row.block.setBackground(panelDrawable(device.blocked ? GREEN_MUTED : RED, 1, dp(4), PANEL));
            row.block.setEnabled(!busy && !TextTools.isBlank(device.ipAddress));
            row.block.setOnClickListener(view -> changeBlocked(device, !device.blocked));
            row.root.setBackground(panelDrawable(
                    device.blocked ? RED : device.active ? GREEN_MUTED : Color.rgb(35, 65, 44),
                    1,
                    dp(5),
                    device.active ? PANEL_ACTIVE : PANEL
            ));
            return convertView;
        }

        private Row createRow() {
            LinearLayout root = new LinearLayout(MainActivity.this);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(dp(12), dp(10), dp(8), dp(10));
            root.setMinimumHeight(dp(92));

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
            root.addView(block, new LinearLayout.LayoutParams(dp(112), dp(44)));
            return new Row(root, name, meta, state, favorite, block);
        }
    }

    private static final class Row {
        final LinearLayout root;
        final TextView name;
        final TextView meta;
        final TextView state;
        final Button favorite;
        final Button block;

        Row(
                LinearLayout root,
                TextView name,
                TextView meta,
                TextView state,
                Button favorite,
                Button block
        ) {
            this.root = root;
            this.name = name;
            this.meta = meta;
            this.state = state;
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
