package de.killswitch.app;

final class FritzException extends Exception {
    FritzException(String message) {
        super(message);
    }

    FritzException(String message, Throwable cause) {
        super(message, cause);
    }
}
