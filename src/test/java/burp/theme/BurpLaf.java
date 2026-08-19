package burp.theme;

/**
 * Stand-in for Burp's own look and feel class.
 * <p>
 * FlatLaf only uses the look and feel classes to locate the matching {@code .properties}
 * resource next to them, so the tests do not need Burp itself - they need a class named
 * {@code burp.theme.BurpLaf} whose {@code BurpLaf.properties} has the same shape as Burp's.
 * That shape is what breaks third party themes: Burp's look and feel is itself built from an
 * IntelliJ theme json, so its properties reference named colours from that json
 * ({@code $ColorPalette.*}) which do not exist when another theme supplies the defaults.
 */
public class BurpLaf {
}
