package de.killswitch.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecureSettings {
    private static final String PREFS = "kill_switch_settings";
    private static final String KEY_ALIAS = "kill_switch_router_credentials";
    private static final String KEY_HOST = "host";
    private static final String KEY_SECRET = "secret";

    private final SharedPreferences preferences;

    SecureSettings(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    Settings load() {
        String host = preferences.getString(KEY_HOST, "fritz.box");
        String encrypted = preferences.getString(KEY_SECRET, "");
        if (TextTools.isBlank(encrypted)) {
            return new Settings(host, "", "");
        }
        try {
            byte[] packed = Base64.decode(encrypted, Base64.NO_WRAP);
            byte[] iv = new byte[12];
            byte[] payload = new byte[packed.length - iv.length];
            System.arraycopy(packed, 0, iv, 0, iv.length);
            System.arraycopy(packed, iv.length, payload, 0, payload.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            JSONObject json = new JSONObject(new String(
                    cipher.doFinal(payload),
                    StandardCharsets.UTF_8
            ));
            return new Settings(host, json.optString("username"), json.optString("password"));
        } catch (Exception ignored) {
            return new Settings(host, "", "");
        }
    }

    void save(Settings settings) throws Exception {
        JSONObject json = new JSONObject();
        json.put("username", settings.username);
        json.put("password", settings.password);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(json.toString().getBytes(StandardCharsets.UTF_8));
        byte[] packed = new byte[cipher.getIV().length + encrypted.length];
        System.arraycopy(cipher.getIV(), 0, packed, 0, cipher.getIV().length);
        System.arraycopy(encrypted, 0, packed, cipher.getIV().length, encrypted.length);

        preferences.edit()
                .putString(KEY_HOST, settings.host)
                .putString(KEY_SECRET, Base64.encodeToString(packed, Base64.NO_WRAP))
                .apply();
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
        );
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }

    static final class Settings {
        final String host;
        final String username;
        final String password;

        Settings(String host, String username, String password) {
            this.host = TextTools.isBlank(host) ? "fritz.box" : host.trim();
            this.username = username == null ? "" : username.trim();
            this.password = password == null ? "" : password;
        }

        boolean isComplete() {
            return !TextTools.isBlank(host)
                    && !TextTools.isBlank(username)
                    && !TextTools.isBlank(password);
        }
    }
}
