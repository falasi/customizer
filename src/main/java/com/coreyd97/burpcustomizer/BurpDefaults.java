package com.coreyd97.burpcustomizer;

import javax.swing.*;
import javax.swing.plaf.ColorUIResource;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves Burp's own UI defaults against the theme being applied.
 * <p>
 * Burp's components read a large number of private keys ({@code Burp.*}, {@code Colors.*},
 * {@code ColourPalette.*}, {@code DesignSystemPalette.*}, ...). Two things can go wrong when a
 * theme replaces Burp's look and feel: a value may fail to resolve, because Burp's properties
 * reference colours only Burp's own theme defines, or a key may be missing entirely, because
 * the class its defaults live in could not be found. Either way the key has to end up with a
 * sensible colour or Burp paints that component with whatever it falls back to.
 * <p>
 * The order is always the same:
 * <pre>
 * Burp's own default -&gt; the active theme's equivalent -&gt; the value Burp had before theming
 * </pre>
 * The theme comes first deliberately. Falling back to Burp's own colour keeps its branding -
 * orange chrome in a Catppuccin window - so it is only used when the theme offers nothing at
 * all. The theme's equivalent is chosen from the key's name: a key called
 * {@code ...focusColor} gets the theme's accent, {@code ...errorForeground} gets the theme's
 * error colour, and so on.
 */
final class BurpDefaults {

    /**
     * The namespaces Burp uses for its own UI defaults.
     */
    private static final String[] KEY_PREFIXES = {
            "Burp.", "BurpPalette.", "ColourPalette.", "ColorPalette.", "DesignSystemPalette.",
            "Colors.", "PortSwigger.",
    };

    /**
     * Alpha value marking a colour which stands in for a Burp value that could not be
     * resolved. FlatLaf has to be given something parseable while it is loading Burp's
     * properties, long before the theme's own colours exist; the marked values are replaced
     * with the theme's equivalents once they do. Colour functions preserve alpha, so a value
     * derived from a placeholder is still recognisable.
     */
    private static final int UNRESOLVED_ALPHA = 3;
    private static final String UNRESOLVED_PLACEHOLDER = "#fe01fd03";

    /**
     * WCAG AA contrast for normal text.
     */
    private static final double MINIMUM_CONTRAST = 4.5;

    /**
     * Above this HSB saturation a colour is treated as deliberately coloured rather than as a
     * point on Burp's grey ramp.
     */
    private static final float CHROMATIC_SATURATION = 0.18f;

    private static final int MAX_LOGGED_KEYS = 40;

    private final Map<String, Color> colors;
    private final Map<String, Object> otherValues;
    private final Color sourceBackground;
    private final Color sourceForeground;

    private BurpDefaults(Map<String, Color> colors, Map<String, Object> otherValues,
                         Color sourceBackground, Color sourceForeground) {
        this.colors = colors;
        this.otherValues = otherValues;
        this.sourceBackground = sourceBackground;
        this.sourceForeground = sourceForeground;
    }

    static BurpDefaults none() {
        return new BurpDefaults(Collections.emptyMap(), Collections.emptyMap(), null, null);
    }

    /**
     * The value FlatLaf is given for a Burp property it cannot resolve, so that loading Burp's
     * defaults succeeds and the real decision can be made once the theme is built.
     */
    static String unresolvedPlaceholder() {
        return UNRESOLVED_PLACEHOLDER;
    }

    /**
     * Captures Burp's own UI defaults from the currently installed look and feel. Must be
     * called while Burp's look and feel is still the installed one, i.e. during extension
     * initialisation and before any theme is applied.
     */
    static BurpDefaults captureFromInstalledLookAndFeel() {
        UIDefaults defaults;
        try {
            defaults = UIManager.getLookAndFeelDefaults();
        } catch (RuntimeException e) {
            BurpCustomizer.logError("Could not read Burp's UI defaults.", e);
            return none();
        }
        if (defaults == null) return none();

        Map<String, Color> colors = new LinkedHashMap<>();
        Map<String, Object> otherValues = new LinkedHashMap<>();
        //Snapshot the keys first: reading a lazy value replaces it in the same table.
        for (Object key : new ArrayList<>(defaults.keySet())) {
            if (!(key instanceof String name) || !isBurpKey(name)) continue;

            Object value;
            try {
                value = defaults.get(name);
            } catch (RuntimeException e) {
                continue; //A value we cannot resolve is a value we cannot carry over.
            }

            if (value instanceof Color color) colors.put(name, color);
            else if (isCopyable(value)) otherValues.put(name, value);
        }

        return new BurpDefaults(colors, otherValues,
                readColor(defaults, "Panel.background", "control", "Table.background"),
                readColor(defaults, "Panel.foreground", "Label.foreground", "controlText"));
    }

    int size() {
        return colors.size() + otherValues.size();
    }

    /**
     * Gives every Burp key which did not survive theming - unresolved or missing - a value
     * from the theme, and reports what it had to do.
     *
     * @return how many keys were given a value
     */
    int applyTo(UIDefaults themeDefaults) {
        ThemePalette palette = new ThemePalette(themeDefaults);
        List<String> report = new ArrayList<>();

        int deferred = resolveDeferredValues(themeDefaults, palette, report);
        int replaced = replaceUnresolved(themeDefaults, palette, report);
        int filled = fillMissing(themeDefaults, palette, report);
        enforceReadableTextPairs(themeDefaults, palette);

        if (!report.isEmpty()) log("Burp Customizer: Burp UI defaults resolved from the theme", report);
        return deferred + replaced + filled;
    }

    /**
     * Resolves Burp keys whose value FlatLaf deferred with {@code lazy(...)}.
     * <p>
     * A deferred value looks up its source with {@code UIManager.get}, which during
     * {@code setLookAndFeel} still answers from the look and feel being replaced - so a Burp
     * key pointed at, say, {@code Button.focusedBorderColor} would resolve to Burp's own
     * orange rather than to the theme's accent, and would then stay that way. Resolving them
     * here, against the defaults actually being built, is what stops Burp's branding leaking
     * through into a themed window.
     */
    private int resolveDeferredValues(UIDefaults themeDefaults, ThemePalette palette, List<String> report) {
        int resolvedCount = 0;
        for (Map.Entry<Object, Object> entry : new ArrayList<>(themeDefaults.entrySet())) {
            if (!(entry.getKey() instanceof String key) || !isBurpKey(key)) continue;

            Object value = entry.getValue();
            if (!(value instanceof UIDefaults.LazyValue) && !(value instanceof UIDefaults.ActiveValue)) continue;
            //Only colours: anything else Burp defers is not ours to second guess.
            if (otherValues.containsKey(key)) continue;

            Resolved resolved = resolve(key, palette);
            themeDefaults.put(key, new ColorUIResource(resolved.color()));
            report.add(describe(key, "was deferred, and would have resolved against the previous look and feel", resolved));
            resolvedCount++;
        }
        return resolvedCount;
    }

    /**
     * Replaces the placeholders left behind by Burp properties which could not be resolved.
     * These are keys Burp's own defaults did define - the definition just pointed at a colour
     * only Burp's theme has - so the theme's equivalent is what belongs there.
     */
    private int replaceUnresolved(UIDefaults themeDefaults, ThemePalette palette, List<String> report) {
        int replaced = 0;
        for (Map.Entry<Object, Object> entry : new ArrayList<>(themeDefaults.entrySet())) {
            if (!(entry.getKey() instanceof String key)) continue;
            //Only already-resolved colours are inspected; resolving a lazy value here would
            //change what the theme does.
            if (!(entry.getValue() instanceof Color color) || color.getAlpha() != UNRESOLVED_ALPHA) continue;

            Resolved resolved = resolve(key, palette);
            themeDefaults.put(key, new ColorUIResource(resolved.color()));
            report.add(describe(key, "could not be resolved from Burp's defaults", resolved));
            replaced++;
        }
        return replaced;
    }

    /**
     * Defines the Burp keys the theme has no value for at all.
     */
    private int fillMissing(UIDefaults themeDefaults, ThemePalette palette, List<String> report) {
        int filled = 0;

        for (Map.Entry<String, Color> entry : colors.entrySet()) {
            //containsKey rather than get: a lazy value should not be resolved just to test it.
            if (themeDefaults.containsKey(entry.getKey())) continue;

            Resolved resolved = resolve(entry.getKey(), palette);
            themeDefaults.put(entry.getKey(), new ColorUIResource(resolved.color()));
            report.add(describe(entry.getKey(), "was not defined by this theme", resolved));
            filled++;
        }

        //Sizes, insets, fonts and flags are not theme dependent, so they carry over as they are.
        for (Map.Entry<String, Object> entry : otherValues.entrySet()) {
            if (themeDefaults.containsKey(entry.getKey())) continue;
            themeDefaults.put(entry.getKey(), entry.getValue());
            filled++;
        }

        return filled;
    }

    // ------------------------------------------------------------------ the semantic resolver

    /**
     * What a Burp key is for, inferred from its name. This is the whole of the mapping policy:
     * everything else just asks the theme for the role's colour.
     */
    private enum Role {
        ERROR, WARNING, SUCCESS, LINK, SELECTION_BACKGROUND, SELECTION_FOREGROUND,
        DISABLED, SEPARATOR, ACCENT, FOCUS, FOREGROUND, BACKGROUND, UNKNOWN
    }

    private record Resolved(Color color, String source) {
    }

    /**
     * The colour a Burp key should have under the active theme.
     * <p>
     * The theme's own value for the key's role comes first. Failing that - a key whose name
     * says nothing - the colour Burp had is re-expressed in the theme's palette, which keeps
     * Burp's intent (how light, how prominent) without keeping Burp's colours. Burp's original
     * value is only used if the theme turns out to have nothing usable at all.
     */
    private Resolved resolve(String key, ThemePalette palette) {
        String name = key.toLowerCase(Locale.ROOT);

        Resolved fromTheme = palette.forRole(roleOf(name), name);
        if (fromTheme != null) return fromTheme;

        Color original = colors.get(key);
        if (original != null && palette.isUsable())
            return new Resolved(reposition(original, name, palette), "derived from the theme's palette");
        if (original != null)
            return new Resolved(original, "Burp's own value (the theme offered no equivalent)");

        return new Resolved(palette.neutral(), "the theme's neutral tone");
    }

    private static Role roleOf(String name) {
        //A palette entry is a colour in its own right, not a role - "orange" in
        //Colors.swatches.orange.core names the colour, it does not mean "accent".
        if (isPaletteEntryName(name)) return Role.UNKNOWN;

        //Order matters: the more specific a role, the earlier it has to be tested.
        if (containsAny(name, "error", "invalid", "danger", "destructive", "critical", "fail")) return Role.ERROR;
        if (containsAny(name, "warning", "caution", "alert")) return Role.WARNING;
        if (containsAny(name, "success", "passed", "valid", "ok")) return Role.SUCCESS;
        if (name.contains("link")) return Role.LINK;
        if (containsAny(name, "selection", "selected")) {
            return isForegroundName(name) ? Role.SELECTION_FOREGROUND : Role.SELECTION_BACKGROUND;
        }
        if (containsAny(name, "disabled", "inactive", "muted")) return Role.DISABLED;
        if (containsAny(name, "separator", "border", "divider", "grid", "outline")) return Role.SEPARATOR;
        if (name.contains("focus")) return Role.FOCUS;
        if (containsAny(name, "accent", "orange", "highlight", "underline", "active", "flash", "primary"))
            return Role.ACCENT;
        if (isForegroundName(name)) return Role.FOREGROUND;
        if (containsAny(name, "background", "fill")) return Role.BACKGROUND;
        return Role.UNKNOWN;
    }

    private static boolean isForegroundName(String name) {
        return containsAny(name, "foreground", "text", "caret", "label", "title");
    }

    private static boolean isPaletteEntryName(String name) {
        return containsAny(name, "swatch", "palette") || name.startsWith("colors.");
    }

    /**
     * Text colours this class made up, as opposed to the ones it took straight from the
     * theme's own palette. Only the invented ones are second guessed - a theme's own pairing
     * of, say, selection foreground and background is the theme author's call, and is what
     * every ordinary Swing table in Burp uses anyway.
     */
    private static boolean isDerivedTextKey(String name) {
        return isForegroundName(name) && roleOf(name) == Role.FOREGROUND;
    }

    /**
     * The colours of the theme being applied. Every lookup is a list of the theme's own keys
     * for that role, most specific first.
     */
    private static final class ThemePalette {

        private final UIDefaults defaults;
        private final Color background;
        private final Color foreground;

        ThemePalette(UIDefaults defaults) {
            this.defaults = defaults;
            this.background = readColor(defaults, "Panel.background", "control");
            this.foreground = readColor(defaults, "Panel.foreground", "Label.foreground", "controlText");
        }

        boolean isUsable() {
            return background != null && foreground != null;
        }

        Color background() {
            return background != null ? background : Color.LIGHT_GRAY;
        }

        Color foreground() {
            return foreground != null ? foreground : Color.BLACK;
        }

        Color neutral() {
            return mix(background(), foreground(), 0.5f);
        }

        Resolved forRole(Role role, String name) {
            return switch (role) {
                case ERROR -> first("Component.error.focusedBorderColor", "Component.error.borderColor",
                        "ColorPalette.red", "Actions.Red");
                case WARNING -> first("Component.warning.focusedBorderColor", "Component.warning.borderColor",
                        "ColorPalette.yellow", "Actions.Yellow");
                case SUCCESS -> first("ColorPalette.green", "Actions.Green", "ProgressBar.passedColor");
                case LINK -> first("Component.linkColor", "Component.accentColor");
                case SELECTION_BACKGROUND -> first("List.selectionBackground", "Table.selectionBackground");
                case SELECTION_FOREGROUND -> first("List.selectionForeground", "Table.selectionForeground");
                case DISABLED -> first("Label.disabledForeground", "Component.disabledColor", "textInactiveText");
                case SEPARATOR -> first("Separator.foreground", "Component.borderColor");
                case ACCENT -> first("Component.accentColor", "Component.focusedBorderColor",
                        "Button.focusedBorderColor", "TabbedPane.underlineColor");
                case FOCUS -> first("Component.focusColor", "Component.focusedBorderColor",
                        "Button.focusedBorderColor", "Component.accentColor");
                case FOREGROUND -> first(surfaceKeys(name, "foreground"));
                case BACKGROUND -> first(surfaceKeys(name, "background"));
                case UNKNOWN -> null;
            };
        }

        /**
         * The theme keys for a surface, in the order the key's name points at.
         */
        private String[] surfaceKeys(String name, String suffix) {
            String surface = containsAny(name, "editor", "textinput", "textfield", "search", "input", "message") ? "TextField"
                    : containsAny(name, "table", "row", "grid", "cell") ? "Table"
                    : name.contains("list") ? "List"
                    : name.contains("tree") ? "Tree"
                    : name.contains("button") ? "Button"
                    : name.contains("menu") ? "MenuItem"
                    : name.contains("tooltip") ? "ToolTip"
                    : name.contains("tab") ? "TabbedPane"
                    : null;

            String capitalised = Character.toUpperCase(suffix.charAt(0)) + suffix.substring(1);
            return surface != null
                    ? new String[]{surface + "." + suffix, "Panel." + suffix, "Label." + suffix, "control" + capitalised}
                    : new String[]{"Panel." + suffix, "Label." + suffix, "control" + capitalised};
        }

        private Resolved first(String... keys) {
            for (String key : keys) {
                Color color = readColor(defaults, key);
                if (color != null) return new Resolved(color, key);
            }
            return null;
        }
    }

    /**
     * Re-expresses a colour in the theme's palette, keeping how far it sat between Burp's own
     * background and foreground. A grey lands on the theme's own grey ramp. A colour Burp uses
     * for branding becomes the theme's accent, because that is what it was doing; any other
     * colour keeps its hue, so a red stays a red.
     */
    private Color reposition(Color original, String name, ThemePalette palette) {
        Color surfaceBackground = surfaceColor(palette, name, "background");
        Color surfaceForeground = surfaceColor(palette, name, "foreground");
        Color neutral = mix(surfaceBackground, surfaceForeground, positionOf(original));

        float[] hsb = Color.RGBtoHSB(original.getRed(), original.getGreen(), original.getBlue(), null);
        if (hsb[1] < CHROMATIC_SATURATION) return neutral;

        if (isBrandColour(hsb) && !isSemanticColourName(name)) {
            Resolved accent = palette.forRole(Role.ACCENT, name);
            if (accent != null) return accent.color();
        }

        float[] neutralHsb = Color.RGBtoHSB(neutral.getRed(), neutral.getGreen(), neutral.getBlue(), null);
        return new Color(Color.HSBtoRGB(hsb[0], hsb[1], neutralHsb[2]));
    }

    private static Color surfaceColor(ThemePalette palette, String name, String suffix) {
        Resolved resolved = palette.forRole(suffix.equals("background") ? Role.BACKGROUND : Role.FOREGROUND, name);
        return resolved != null ? resolved.color()
                : suffix.equals("background") ? palette.background() : palette.foreground();
    }

    /**
     * Burp's branding sits in the red to orange band. A key which is named for a state -
     * an error, a warning, a scanner severity - or which is a palette entry in its own right
     * keeps that colour; anything else was using it as chrome.
     */
    private static boolean isBrandColour(float[] hsb) {
        float hue = hsb[0] * 360f;
        return (hue <= 45f || hue >= 345f) && hsb[1] >= 0.45f && hsb[2] >= 0.4f;
    }

    private static boolean isSemanticColourName(String name) {
        return containsAny(name, "error", "invalid", "danger", "destructive", "critical", "fail",
                "warning", "caution", "alert", "severity", "risk", "vuln", "issue", "confidence",
                "certain", "firm", "tentative", "high", "medium", "low", "info",
                "red", "orange", "crimson", "swatch", "palette");
    }

    /**
     * How far a colour sits between Burp's own background (0) and foreground (1).
     */
    private float positionOf(Color color) {
        if (sourceBackground == null || sourceForeground == null) return 0.5f;

        double from = luminance(sourceBackground);
        double to = luminance(sourceForeground);
        if (Math.abs(to - from) < 0.001) return 0.5f;

        return clamp((float) ((luminance(color) - from) / (to - from)));
    }

    // ------------------------------------------------------------------ readability

    /**
     * A text colour is only readable against the background it is actually painted on, and
     * Burp names that background right next to it. Both may have been derived, so the pair is
     * checked once both exist.
     */
    private void enforceReadableTextPairs(UIDefaults themeDefaults, ThemePalette palette) {
        for (String key : colors.keySet()) {
            String name = key.toLowerCase(Locale.ROOT);
            if (!isDerivedTextKey(name)) continue;

            String backgroundKey = backgroundSiblingOf(key);
            Color background = backgroundKey != null ? colorAt(themeDefaults, backgroundKey) : null;
            Color foreground = colorAt(themeDefaults, key);
            if (background == null || foreground == null) continue;

            Color readable = readable(foreground, background, surfaceColor(palette, name, "foreground"));
            if (!readable.equals(foreground)) themeDefaults.put(key, new ColorUIResource(readable));
        }
    }

    /**
     * Moves a foreground colour towards the theme's own foreground until it is readable on
     * the background it will be painted on.
     */
    private static Color readable(Color color, Color background, Color foreground) {
        if (contrast(color, background) >= MINIMUM_CONTRAST) return color;

        for (float amount = 0.2f; amount < 1f; amount += 0.2f) {
            Color candidate = mix(color, foreground, amount);
            if (contrast(candidate, background) >= MINIMUM_CONTRAST) return candidate;
        }
        if (contrast(foreground, background) >= MINIMUM_CONTRAST) return foreground;

        //The theme's own foreground is not readable on this background either, so fall back
        //to whichever extreme is.
        return contrast(Color.WHITE, background) >= contrast(Color.BLACK, background) ? Color.WHITE : Color.BLACK;
    }

    // ------------------------------------------------------------------ diagnostics

    /**
     * Burp keys which came through theming still carrying Burp's own branding colour, for
     * working out which component is still painted orange. Each entry is
     * {@code key -> Burp's value -> the themed value}.
     */
    List<String> brandColouredKeysAfterTheming(UIDefaults themeDefaults) {
        List<String> remaining = new ArrayList<>();
        for (Map.Entry<String, Color> entry : colors.entrySet()) {
            Color themed = colorAt(themeDefaults, entry.getKey());
            if (themed == null) continue;

            float[] hsb = Color.RGBtoHSB(themed.getRed(), themed.getGreen(), themed.getBlue(), null);
            if (!isBrandColour(hsb)) continue;
            if (isSemanticColourName(entry.getKey().toLowerCase(Locale.ROOT))) continue;

            remaining.add(entry.getKey() + " -> " + hex(entry.getValue()) + " -> " + hex(themed));
        }
        return remaining;
    }

    private static String describe(String key, String problem, Resolved resolved) {
        return "Burp key: " + key
                + "\n    " + problem
                + "\n    using " + resolved.source() + " = " + hex(resolved.color());
    }

    private static void log(String heading, List<String> entries) {
        StringBuilder message = new StringBuilder(heading).append(" (").append(entries.size()).append(")");
        for (int i = 0; i < Math.min(entries.size(), MAX_LOGGED_KEYS); i++)
            message.append('\n').append("  ").append(entries.get(i));
        if (entries.size() > MAX_LOGGED_KEYS)
            message.append("\n  ... and ").append(entries.size() - MAX_LOGGED_KEYS).append(" more");
        BurpCustomizer.logOutput(message.toString());
    }

    static String hex(Color color) {
        return color == null ? "none" : String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    // ------------------------------------------------------------------ helpers

    static boolean isBurpKey(String key) {
        for (String prefix : KEY_PREFIXES) {
            if (key.startsWith(prefix)) return true;
        }
        return false;
    }

    private static boolean isCopyable(Object value) {
        //Borders and icons are excluded: they paint themselves with the colours of the theme
        //they were built for.
        return value instanceof Integer || value instanceof Boolean || value instanceof Long
                || value instanceof Float || value instanceof Double || value instanceof Character
                || value instanceof String || value instanceof Insets || value instanceof Dimension
                || value instanceof Font;
    }

    /**
     * The key naming the background a foreground is painted on, following Burp's own
     * convention of naming the two alike.
     */
    static String backgroundSiblingOf(String key) {
        int upper = key.lastIndexOf("Foreground");
        if (upper >= 0) return key.substring(0, upper) + "Background" + key.substring(upper + "Foreground".length());

        int lower = key.lastIndexOf("foreground");
        if (lower >= 0) return key.substring(0, lower) + "background" + key.substring(lower + "foreground".length());

        return null;
    }

    private static Color colorAt(UIDefaults defaults, String key) {
        try {
            Object value = defaults.get(key);
            return value instanceof Color color ? color : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Color readColor(UIDefaults defaults, String... keys) {
        for (String key : keys) {
            try {
                Color color = defaults.getColor(key);
                //A placeholder is not an answer - it is the question.
                if (color != null && color.getAlpha() != UNRESOLVED_ALPHA) return color;
            } catch (RuntimeException ignored) {
                //Try the next candidate.
            }
        }
        return null;
    }

    static double contrast(Color a, Color b) {
        double lighter = Math.max(luminance(a), luminance(b));
        double darker = Math.min(luminance(a), luminance(b));
        return (lighter + 0.05) / (darker + 0.05);
    }

    /**
     * WCAG relative luminance.
     */
    private static double luminance(Color color) {
        return 0.2126 * channel(color.getRed()) + 0.7152 * channel(color.getGreen()) + 0.0722 * channel(color.getBlue());
    }

    private static double channel(int value) {
        double c = value / 255d;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static Color mix(Color from, Color to, float amount) {
        float t = clamp(amount);
        return new Color(
                Math.round(from.getRed() + (to.getRed() - from.getRed()) * t),
                Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * t),
                Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * t));
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) return true;
        }
        return false;
    }
}
