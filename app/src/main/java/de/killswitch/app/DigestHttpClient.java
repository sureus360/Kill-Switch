package de.killswitch.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DigestHttpClient {
    private final String username;
    private final String password;
    private final SecureRandom random = new SecureRandom();
    private String cachedChallenge;
    private String lastNonce;
    private int nonceCount;

    DigestHttpClient(String username, String password) {
        this.username = username;
        this.password = password;
    }

    String get(String url) throws Exception {
        return request("GET", url, null, null);
    }

    String post(String url, String soapAction, String body) throws Exception {
        return request("POST", url, soapAction, body);
    }

    private String request(String method, String url, String soapAction, String body) throws Exception {
        String preemptiveAuthorization = cachedChallenge == null
                ? null
                : createAuthorization(method, new URL(url), cachedChallenge);
        Response first = execute(method, url, soapAction, body, preemptiveAuthorization);
        if (first.code >= 200 && first.code < 300) {
            return first.body;
        }
        if (first.code != HttpURLConnection.HTTP_UNAUTHORIZED) {
            throw new FritzException(soapFault(first.code, first.body, actionName(soapAction)));
        }

        String challenge = first.authenticate;
        if (challenge == null || !challenge.toLowerCase(Locale.US).startsWith("digest")) {
            throw new FritzException("Die FRITZ!Box bietet keine Digest-Anmeldung an.");
        }
        cachedChallenge = challenge;
        String authorization = createAuthorization(method, new URL(url), challenge);
        Response second = execute(method, url, soapAction, body, authorization);
        if (second.code == HttpURLConnection.HTTP_UNAUTHORIZED
                || second.code == HttpURLConnection.HTTP_FORBIDDEN) {
            throw new FritzException(
                    "Die FRITZ!Box hat die Anmeldung oder Schreibaktion abgelehnt. "
                            + "Pruefe Benutzername, Passwort und die App-/Einstellungsrechte "
                            + "des FRITZ!Box-Benutzers."
            );
        }
        if (second.code < 200 || second.code >= 300) {
            throw new FritzException(soapFault(second.code, second.body, actionName(soapAction)));
        }
        return second.body;
    }

    private Response execute(
            String method,
            String url,
            String soapAction,
            String body,
            String authorization
    ) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(15000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "text/xml, application/xml, */*");
        connection.setRequestProperty("Connection", "close");
        if (authorization != null) {
            connection.setRequestProperty("Authorization", authorization);
        }
        if (soapAction != null) {
            connection.setRequestProperty("SOAPAction", "\"" + soapAction + "\"");
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        }
        if (body != null) {
            connection.setDoOutput(true);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
        }

        int code = connection.getResponseCode();
        InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String responseBody = read(stream);
        String authenticate = connection.getHeaderField("WWW-Authenticate");
        connection.disconnect();
        return new Response(code, responseBody, authenticate);
    }

    private String createAuthorization(String method, URL url, String challenge) throws Exception {
        Map<String, String> params = parseChallenge(challenge.substring(6));
        String realm = params.getOrDefault("realm", "");
        String nonce = params.getOrDefault("nonce", "");
        String qop = params.getOrDefault("qop", "");
        if (qop.contains(",")) {
            qop = "auth";
        }
        String uri = url.getPath().isEmpty() ? "/" : url.getPath();
        if (url.getQuery() != null) {
            uri += "?" + url.getQuery();
        }
        if (!nonce.equals(lastNonce)) {
            lastNonce = nonce;
            nonceCount = 0;
        }
        String nc = String.format(Locale.US, "%08x", ++nonceCount);
        String cnonce = randomHex(16);
        String ha1 = md5(username + ":" + realm + ":" + password);
        String ha2 = md5(method + ":" + uri);
        String response = TextTools.isBlank(qop)
                ? md5(ha1 + ":" + nonce + ":" + ha2)
                : md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);

        StringBuilder header = new StringBuilder("Digest ");
        append(header, "username", username, true);
        append(header, "realm", realm, true);
        append(header, "nonce", nonce, true);
        append(header, "uri", uri, true);
        append(header, "response", response, true);
        if (!TextTools.isBlank(qop)) {
            append(header, "qop", qop, false);
            append(header, "nc", nc, false);
            append(header, "cnonce", cnonce, true);
        }
        if (params.containsKey("opaque")) {
            append(header, "opaque", params.get("opaque"), true);
        }
        if (params.containsKey("algorithm")) {
            append(header, "algorithm", params.get("algorithm"), false);
        }
        return header.substring(0, header.length() - 2);
    }

    private static Map<String, String> parseChallenge(String value) {
        Map<String, String> result = new HashMap<>();
        int index = 0;
        while (index < value.length()) {
            while (index < value.length() && (value.charAt(index) == ',' || Character.isWhitespace(value.charAt(index)))) {
                index++;
            }
            int equals = value.indexOf('=', index);
            if (equals < 0) {
                break;
            }
            String key = value.substring(index, equals).trim().toLowerCase(Locale.US);
            index = equals + 1;
            String parsed;
            if (index < value.length() && value.charAt(index) == '"') {
                int end = ++index;
                while (end < value.length() && value.charAt(end) != '"') {
                    end++;
                }
                parsed = value.substring(index, end);
                index = end + 1;
            } else {
                int end = value.indexOf(',', index);
                if (end < 0) {
                    end = value.length();
                }
                parsed = value.substring(index, end).trim();
                index = end;
            }
            result.put(key, parsed);
        }
        return result;
    }

    private static void append(StringBuilder builder, String key, String value, boolean quote) {
        builder.append(key).append('=');
        if (quote) {
            builder.append('"').append(value.replace("\"", "\\\"")).append('"');
        } else {
            builder.append(value);
        }
        builder.append(", ");
    }

    private static String md5(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5")
                .digest(value.getBytes(StandardCharsets.ISO_8859_1));
        StringBuilder result = new StringBuilder();
        for (byte item : digest) {
            result.append(String.format(Locale.US, "%02x", item & 0xff));
        }
        return result.toString();
    }

    private String randomHex(int byteCount) {
        byte[] bytes = new byte[byteCount];
        random.nextBytes(bytes);
        StringBuilder result = new StringBuilder();
        for (byte item : bytes) {
            result.append(String.format(Locale.US, "%02x", item & 0xff));
        }
        return result.toString();
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

    private static String soapFault(int httpCode, String body, String action) {
        String code = xmlTagText(body, "errorCode");
        String description = xmlTagText(body, "errorDescription");
        if (!TextTools.isBlank(code)) {
            switch (code) {
                case "401":
                    return unsupportedActionMessage(action);
                case "606":
                    return "FRITZ!Box-Fehler 606: Action Not Authorized. "
                            + "Der eingetragene FRITZ!Box-Benutzer darf diese Sperraktion nicht ausführen. "
                            + "Wähle in [ CONFIG ] einen Benutzer mit App-Rechten oder gib diesem Benutzer "
                            + "in der FRITZ!Box die nötigen Rechte.";
                case "501":
                    return "FRITZ!Box-Fehler 501: Internetsperre im IP-Client-/Bridge-Modus nicht verfügbar.";
                case "714":
                    return "FRITZ!Box-Fehler 714: Gerät unter dieser IPv4-Adresse nicht gefunden. Bitte neu scannen.";
                case "880":
                    return "FRITZ!Box-Fehler 880: Dieses FRITZ!OS-/Powerline-Gerät darf nicht gesperrt werden.";
                case "402":
                    return "FRITZ!Box-Fehler 402: Ungültige Sperranfrage.";
                case "820":
                    return "FRITZ!Box-Fehler 820: Interner Fehler der FRITZ!Box.";
                default:
                    break;
            }
        }
        if (!TextTools.isBlank(code) || !TextTools.isBlank(description)) {
            return "FRITZ!Box-Fehler " + code
                    + (TextTools.isBlank(description) ? "" : ": " + description);
        }
        return "HTTP " + httpCode + ": " + compact(body);
    }

    private static String actionName(String soapAction) {
        if (TextTools.isBlank(soapAction)) {
            return "";
        }
        int hash = soapAction.lastIndexOf('#');
        if (hash < 0 || hash == soapAction.length() - 1) {
            return "";
        }
        return soapAction.substring(hash + 1);
    }

    private static String unsupportedActionMessage(String action) {
        String actionText = TextTools.isBlank(action)
                ? "die benötigte TR-064-HostFilter-Aktion"
                : "die TR-064-Aktion " + action;
        return "FRITZ!Box-Fehler 401: Invalid Action. Diese FRITZ!Box/Firmware stellt "
                + actionText
                + " nicht bereit. Aktualisiere FRITZ!OS oder nutze eine FRITZ!Box, deren "
                + "X_AVM-DE_HostFilter-Dienst DisallowWANAccessByIP und GetWANAccessByIP anbietet.";
    }

    private static String xmlTagText(String xml, String localName) {
        if (TextTools.isBlank(xml)) {
            return "";
        }
        Pattern pattern = Pattern.compile(
                "<(?:[A-Za-z0-9_.-]+:)?" + Pattern.quote(localName)
                        + "(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_.-]+:)?"
                        + Pattern.quote(localName) + ">",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        Matcher matcher = pattern.matcher(xml);
        return matcher.find() ? matcher.group(1).replaceAll("\\s+", " ").trim() : "";
    }

    private static String compact(String value) {
        if (TextTools.isBlank(value)) {
            return "Keine Antwort";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() > 180 ? compact.substring(0, 180) + "..." : compact;
    }

    private static final class Response {
        final int code;
        final String body;
        final String authenticate;

        Response(int code, String body, String authenticate) {
            this.code = code;
            this.body = body;
            this.authenticate = authenticate;
        }
    }
}
