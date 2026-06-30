package de.killswitch.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class BackendClient {
    private final String baseUrl;
    private final String token;

    BackendClient(SecureSettings.Settings settings) throws FritzException {
        baseUrl = normalizeBaseUrl(settings.backendUrl);
        token = settings.backendToken;
    }

    List<NetworkDevice> loadDevices() throws Exception {
        JSONObject response = request("GET", "/devices", null);
        JSONArray items = response.optJSONArray("devices");
        List<NetworkDevice> devices = new ArrayList<>();
        if (items == null) {
            return devices;
        }
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.getJSONObject(index);
            devices.add(new NetworkDevice(
                    item.optString("name"),
                    item.optString("ipAddress"),
                    item.optString("macAddress"),
                    item.optString("interfaceType"),
                    item.optBoolean("active"),
                    item.optBoolean("blocked"),
                    item.optLong("unlockAt")
            ));
        }
        return devices;
    }

    ActionResult setBlocked(List<NetworkDevice> devices, boolean blocked, long unlockAt) throws Exception {
        JSONObject body = new JSONObject();
        JSONArray keys = new JSONArray();
        for (NetworkDevice device : devices) {
            keys.put(device.key());
        }
        body.put("keys", keys);
        body.put("blocked", blocked);
        if (unlockAt > 0) {
            body.put("unlockAt", unlockAt);
        }

        JSONObject response = request("POST", "/devices/actions", body);
        List<String> succeeded = strings(response.optJSONArray("succeededKeys"));
        List<String> failures = strings(response.optJSONArray("failures"));
        return new ActionResult(succeeded, failures);
    }

    private JSONObject request(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(30000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Connection", "close");
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
        }

        int code = connection.getResponseCode();
        InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String response = read(stream);
        connection.disconnect();
        if (code < 200 || code >= 300) {
            throw new FritzException(backendError(code, response));
        }
        if (TextTools.isBlank(response)) {
            return new JSONObject();
        }
        return new JSONObject(response);
    }

    private static List<String> strings(JSONArray array) {
        List<String> result = new ArrayList<>();
        if (array == null) {
            return result;
        }
        for (int index = 0; index < array.length(); index++) {
            result.add(array.optString(index));
        }
        return result;
    }

    private static String backendError(int code, String response) {
        try {
            JSONObject json = new JSONObject(response);
            String error = json.optString("error");
            if (!TextTools.isBlank(error)) {
                return "Backend-Fehler " + code + ": " + error;
            }
        } catch (Exception ignored) {
            // Fall back to compact raw response.
        }
        return "Backend-Fehler " + code + ": " + compact(response);
    }

    private static String normalizeBaseUrl(String value) throws FritzException {
        try {
            String candidate = value.trim();
            if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) {
                candidate = "http://" + candidate;
            }
            URI uri = URI.create(candidate);
            if (TextTools.isBlank(uri.getHost())) {
                throw new IllegalArgumentException("host");
            }
            while (candidate.endsWith("/")) {
                candidate = candidate.substring(0, candidate.length() - 1);
            }
            return candidate;
        } catch (Exception error) {
            throw new FritzException("Ungueltige Backend-Adresse.", error);
        }
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String compact(String value) {
        if (TextTools.isBlank(value)) {
            return "Keine Antwort";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() > 180 ? compact.substring(0, 180) + "..." : compact;
    }

    static final class ActionResult {
        final List<String> succeededKeys;
        final List<String> failures;

        ActionResult(List<String> succeededKeys, List<String> failures) {
            this.succeededKeys = succeededKeys;
            this.failures = failures;
        }
    }
}
