package de.killswitch.app;

final class NetworkDevice {
    final String name;
    final String ipAddress;
    final String macAddress;
    final String interfaceType;
    final boolean active;
    final boolean blocked;

    NetworkDevice(
            String name,
            String ipAddress,
            String macAddress,
            String interfaceType,
            boolean active,
            boolean blocked
    ) {
        this.name = TextTools.isBlank(name) ? "UNBEKANNTES GERAET" : name;
        this.ipAddress = ipAddress == null ? "" : ipAddress;
        this.macAddress = macAddress == null ? "" : macAddress;
        this.interfaceType = interfaceType == null ? "" : interfaceType;
        this.active = active;
        this.blocked = blocked;
    }

    String key() {
        return TextTools.isBlank(macAddress) ? ipAddress : macAddress;
    }

    NetworkDevice withBlocked(boolean newBlocked) {
        return new NetworkDevice(name, ipAddress, macAddress, interfaceType, active, newBlocked);
    }
}
