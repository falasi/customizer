package burp.theme;

/**
 * Stands in for wherever Burp's own palette comes from - its theme json, an addon, a class
 * the extension has no reason to know about. The point is that Burp's look and feel can see
 * these colours while it is installed, and the extension cannot reach them from the classes
 * it discovers, which is why a reference like {@code $ColorPalette.colorSeparator} resolves
 * for Burp and not for a theme.
 */
public class BurpColors {
}
