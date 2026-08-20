package com.coreyd97.burpcustomizer;

/**
 * Thrown when a theme could not be loaded or applied.
 * The message is written for the user and is safe to show in a dialog,
 * the cause carries the technical detail for the extension error log.
 */
public class ThemeLoadException extends Exception {

    public ThemeLoadException(String userMessage) {
        super(userMessage);
    }

    public ThemeLoadException(String userMessage, Throwable cause) {
        super(userMessage, cause);
    }
}
