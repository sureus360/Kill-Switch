package de.killswitch.app;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

final class FritzBoxClient {
    private static final long ACCESS_CHANGE_TIMEOUT_MS = 20_000;
    private static final long ACCESS_CHANGE_POLL_MS = 750;

    private final String baseUrl;
    private final DigestHttpClient http;
    private Service hostsService;
    private Service hostFilterService;

    FritzBoxClient(SecureSettings.Settings settings) throws FritzException {
        baseUrl = normalizeBaseUrl(settings.host);
        http = new DigestHttpClient(settings.username, settings.password);
    }

    List<NetworkDevice> loadDevices() throws Exception {
        discoverServices();
        Document countResponse = soap(hostsService, "GetHostNumberOfEntries", Collections.emptyMap());
        int count = Math.min(512, parseInt(text(countResponse, "NewHostNumberOfEntries")));
        List<NetworkDevice> devices = new ArrayList<>();

        for (int index = 0; index < count; index++) {
            try {
                Document response = soap(
                        hostsService,
                        "GetGenericHostEntry",
                        Collections.singletonMap("NewIndex", Integer.toString(index))
                );
                String ip = text(response, "NewIPAddress");
                if (TextTools.isBlank(ip)) {
                    continue;
                }
                boolean blocked = false;
                try {
                    blocked = getAccessState(ip).blocked;
                } catch (Exception ignored) {
                    // Some router-owned and legacy entries cannot expose a filter state.
                }
                devices.add(new NetworkDevice(
                        text(response, "NewHostName"),
                        ip,
                        text(response, "NewMACAddress"),
                        text(response, "NewInterfaceType"),
                        "1".equals(text(response, "NewActive")),
                        blocked
                ));
            } catch (Exception ignored) {
                // Entries may disappear while the router list is being read.
            }
        }

        devices.sort(Comparator
                .comparing((NetworkDevice device) -> !device.active)
                .thenComparing(device -> device.name.toLowerCase(Locale.GERMAN)));
        return devices;
    }

    void setBlocked(NetworkDevice device, boolean blocked) throws Exception {
        discoverServices();
        if (TextTools.isBlank(device.ipAddress)) {
            throw new FritzException("Das Geraet hat keine IPv4-Adresse.");
        }
        Map<String, String> arguments = new LinkedHashMap<>();
        arguments.put("NewIPv4Address", device.ipAddress);
        arguments.put("NewDisallow", blocked ? "1" : "0");
        soap(
                hostFilterService,
                "DisallowWANAccessByIP",
                arguments
        );

        long deadline = System.currentTimeMillis() + ACCESS_CHANGE_TIMEOUT_MS;
        AccessState lastState = null;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(ACCESS_CHANGE_POLL_MS);
            lastState = getAccessState(device.ipAddress);
            if (lastState.disallowed == blocked
                    && (blocked ? lastState.blocked : !lastState.blocked)) {
                return;
            }
        }

        if (lastState != null && !blocked && !lastState.disallowed && lastState.blocked) {
            throw new FritzException(
                    "Die manuelle Sperre wurde aufgehoben, aber das Zugangsprofil "
                            + "der FRITZ!Box blockiert das Geraet weiterhin."
            );
        }
        throw new FritzException(
                blocked
                        ? "Die FRITZ!Box hat die Internetsperre nach 20 Sekunden nicht bestaetigt."
                        : "Die FRITZ!Box hat die Freigabe nach 20 Sekunden nicht bestaetigt."
        );
    }

    private AccessState getAccessState(String ipAddress) throws Exception {
        Document access = soap(
                hostFilterService,
                "GetWANAccessByIP",
                Collections.singletonMap("NewIPv4Address", ipAddress)
        );
        String wanAccess = text(access, "NewWANAccess");
        if ("error".equalsIgnoreCase(wanAccess)) {
            throw new FritzException("Die FRITZ!Box konnte den WAN-Status noch nicht ermitteln.");
        }
        return new AccessState(
                "1".equals(text(access, "NewDisallow"))
                        || "true".equalsIgnoreCase(text(access, "NewDisallow")),
                "denied".equalsIgnoreCase(wanAccess)
        );
    }

    private void discoverServices() throws Exception {
        if (hostsService != null && hostFilterService != null) {
            return;
        }
        Document description = parse(http.get(baseUrl + "/tr64desc.xml"));
        NodeList serviceNodes = description.getElementsByTagNameNS("*", "service");
        for (int index = 0; index < serviceNodes.getLength(); index++) {
            Element serviceElement = (Element) serviceNodes.item(index);
            String type = childText(serviceElement, "serviceType");
            String controlUrl = childText(serviceElement, "controlURL");
            Service service = new Service(type, absoluteUrl(controlUrl));
            if (type.contains(":Hosts:")) {
                hostsService = service;
            } else if (type.contains("X_AVM-DE_HostFilter")) {
                hostFilterService = service;
            }
        }
        if (hostsService == null || hostFilterService == null) {
            throw new FritzException(
                    "Die benoetigten TR-064-Dienste wurden nicht gefunden. "
                            + "Aktiviere den Zugriff fuer Anwendungen in der FRITZ!Box."
            );
        }
    }

    private Document soap(Service service, String action, Map<String, String> arguments) throws Exception {
        StringBuilder argumentXml = new StringBuilder();
        for (Map.Entry<String, String> entry : new LinkedHashMap<>(arguments).entrySet()) {
            argumentXml.append('<').append(entry.getKey()).append('>')
                    .append(escape(entry.getValue()))
                    .append("</").append(entry.getKey()).append('>');
        }
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:" + action + " xmlns:u=\"" + service.type + "\">"
                + argumentXml
                + "</u:" + action + "></s:Body></s:Envelope>";
        Document response = parse(http.post(service.controlUrl, service.type + "#" + action, body));
        String errorCode = text(response, "errorCode");
        if (!TextTools.isBlank(errorCode)) {
            throw new FritzException(actionFailure(errorCode, text(response, "errorDescription")));
        }
        return response;
    }

    private String absoluteUrl(String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        return baseUrl + (path.startsWith("/") ? path : "/" + path);
    }

    private static String normalizeBaseUrl(String value) throws FritzException {
        try {
            String candidate = value.trim();
            if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) {
                candidate = "http://" + candidate;
            }
            URI uri = URI.create(candidate);
            String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
            String host = uri.getHost();
            if (TextTools.isBlank(host)) {
                throw new IllegalArgumentException("host");
            }
            int port = uri.getPort() < 0 ? ("https".equals(scheme) ? 49443 : 49000) : uri.getPort();
            return scheme + "://" + host + ":" + port;
        } catch (Exception error) {
            throw new FritzException("Ungueltige Router-Adresse.", error);
        }
    }

    private static Document parse(String xml) throws Exception {
        String normalized = xml.toUpperCase(Locale.US);
        if (normalized.contains("<!DOCTYPE") || normalized.contains("<!ENTITY")) {
            throw new FritzException("Unsichere XML-Antwort von der FRITZ!Box abgelehnt.");
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        setFeatureIfSupported(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setFeatureIfSupported(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeatureIfSupported(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeatureIfSupported(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        setFeatureIfSupported(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setExpandEntityReferences(false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private static void setFeatureIfSupported(
            DocumentBuilderFactory factory,
            String feature,
            boolean enabled
    ) {
        try {
            factory.setFeature(feature, enabled);
        } catch (ParserConfigurationException | AbstractMethodError | UnsupportedOperationException ignored) {
            // Android XML parser support differs by OS version. The explicit
            // DOCTYPE check and EntityResolver above remain the safety net.
        }
    }

    private static String text(Document document, String tag) {
        NodeList nodes = document.getElementsByTagNameNS("*", tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }

    private static String childText(Element element, String tag) {
        NodeList children = element.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (tag.equals(child.getLocalName()) || tag.equals(child.getNodeName())) {
                return child.getTextContent().trim();
            }
        }
        return "";
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String actionFailure(String code, String description) {
        switch (code) {
            case "501":
                return "Die Internetsperre ist im IP-Client-/Bridge-Modus der FRITZ!Box nicht verfuegbar.";
            case "714":
                return "Das Geraet wurde unter dieser IPv4-Adresse nicht mehr gefunden. Bitte neu scannen.";
            case "880":
                return "Dieses Geraet darf von der FRITZ!Box nicht gesperrt werden. "
                        + "FRITZ!OS- und Powerline-Geraete sind ausgenommen.";
            case "402":
                return "Die FRITZ!Box hat die Sperranfrage als ungueltig abgelehnt.";
            case "820":
                return "Die FRITZ!Box meldet einen internen Fehler bei der Sperraktion.";
            default:
                return "FRITZ!Box-Fehler " + code
                        + (TextTools.isBlank(description) ? "" : ": " + description);
        }
    }

    private static final class AccessState {
        final boolean disallowed;
        final boolean blocked;

        AccessState(boolean disallowed, boolean blocked) {
            this.disallowed = disallowed;
            this.blocked = blocked;
        }
    }

    private static final class Service {
        final String type;
        final String controlUrl;

        Service(String type, String controlUrl) {
            this.type = type;
            this.controlUrl = controlUrl;
        }
    }
}
