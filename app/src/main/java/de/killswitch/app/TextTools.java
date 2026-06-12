package de.killswitch.app;

final class TextTools {
    private TextTools() {
    }

    static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
