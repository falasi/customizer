package com.coreyd97.burpcustomizer;

import javax.swing.*;
import javax.swing.plaf.ColorUIResource;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A snapshot of the UI defaults Burp defines for its own components, taken before the
 * extension changes the look and feel.
 * <p>
 * Burp's components read a large number of private keys ({@code Burp.*},
 * {@code ColourPalette.*}, {@code DesignSystemPalette.*}, ...) which only exist while Burp's
 * own look and feel is installed. If those keys cannot be reloaded from Burp's own defaults -
 * because a newer Burp does not expose the classes they live in, or keeps them somewhere we
 * cannot reach - a theme would otherwise leave them undefined and Burp would paint text and
 * backgrounds with whatever it falls back to, which is frequently unreadable.
 * <p>
 * This class keeps the values Burp had, and re-expresses each of them in the palette of the
 * theme that is being applied: a key's colour keeps its position between Burp's own
 * background and foreground, but that position is measured out along the new theme's
 * background and foreground instead. Foreground keys are then checked against the background
 * they are most likely painted on and pushed until they meet a readable contrast ratio, so
 * nothing degrades into dark-on-dark or light-on-light.
 */
final class BurpDefaults {

    /**
     * The namespaces Burp uses for its own UI defaults.
     */
    private static final String[] KEY_PREFIXES = {
            "Burp.", "BurpPalette.", "ColourPalette.", "ColorPalette.", "DesignSystemPalette.",
    };

    /**
     * WCAG AA contrast for normal text.
     */
    private static final double MINIMUM_CONTRAST = 4.5;

    /**
     * Above this HSB saturation a colour is treated as deliberately coloured rather than as a
     * point on Burp's grey ramp, and keeps its hue when it is re-expressed.
     */
    private static final float CHROMATIC_SATURATION = 0.18f;

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
     * Defines every captured Burp key which the given defaults do not already have, deriving
     * each colour from the theme's own palette.
     *
     * @return how many keys were filled in
     */
    int applyMissingTo(UIDefaults themeDefaults) {
        if (size() == 0) return 0;

        Palette palette = new Palette(themeDefaults);
        int filled = 0;
        ArrayList<String> derivedText = new ArrayList<>();

        for (Map.Entry<String, Color> entry : colors.entrySet()) {
            //containsKey rather than get: a lazy value should not be resolved just to test it.
            if (themeDefaults.containsKey(entry.getKey())) continue;
            themeDefaults.put(entry.getKey(), new ColorUIResource(derive(entry.getKey(), entry.getValue(), palette)));
            if (isDerivedTextKey(entry.getKey().toLowerCase(Locale.ROOT))) derivedText.add(entry.getKey());
            filled++;
        }

        //A text colour is only readable against the background it is actually painted on, and
        //Burp names that background right next to it. Both may have been derived, so the pair
        //is checked once both exist.
        for (String key : derivedText) {
            String backgroundKey = backgroundSiblingOf(key);
            Color background = backgroundKey != null ? colorAt(themeDefaults, backgroundKey) : null;
            Color foreground = colorAt(themeDefaults, key);
            if (background == null || foreground == null) continue;

            Color readable = readable(foreground, background, palette.foregroundFor(key.toLowerCase(Locale.ROOT)));
            if (!readable.equals(foreground)) themeDefaults.put(key, new ColorUIResource(readable));
        }

        //Sizes, insets, fonts and flags are not theme dependent, so they carry over as they are.
        for (Map.Entry<String, Object> entry : otherValues.entrySet()) {
            if (themeDefaults.containsKey(entry.getKey())) continue;
            themeDefaults.put(entry.getKey(), entry.getValue());
            filled++;
        }

        return filled;
    }

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

    // ------------------------------------------------------------------ derivation

    private Color derive(String key, Color original, Palette palette) {
        String name = key.toLowerCase(Locale.ROOT);
        Color background = palette.backgroundFor(name);
        Color foreground = palette.foregroundFor(name);

        if (containsAny(name, "selection", "selected")) {
            return isForegroundKey(name) ? palette.selectionForeground : palette.selectionBackground;
        }
        if (containsAny(name, "disabled", "inactive")) {
            //Deliberately dimmer than the text colour, but never so dim it disappears.
            return mix(background, foreground, 0.45f);
        }
        if (containsAny(name, "separator", "border", "divider", "grid", "outline")) {
            return palette.separator;
        }
        if (name.contains("focus")) return palette.focus;
        if (containsAny(name, "accent", "orange", "highlight", "underline", "link")) return palette.accent;

        Color derived = reposition(original, background, foreground);
        return isForegroundKey(name) ? readable(derived, background, foreground) : derived;
    }

    private static boolean isForegroundKey(String name) {
        return containsAny(name, "foreground", "text", "caret", "label", "title");
    }

    /**
     * Text colours this class made up, as opposed to the ones it took straight from the
     * theme's own palette. Only the invented ones are second guessed - a theme's own pairing
     * of, say, selection foreground and background is the theme author's call, and is what
     * every ordinary Swing table in Burp uses anyway.
     */
    private static boolean isDerivedTextKey(String name) {
        return isForegroundKey(name)
                && !containsAny(name, "selection", "selected", "disabled", "inactive", "separator", "border",
                "divider", "grid", "outline", "focus", "accent", "orange", "highlight", "underline", "link");
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

    /**
     * Re-expresses a colour in the theme's palette, keeping how far it sat between Burp's own
     * background and foreground. A grey lands on the theme's own grey ramp; a coloured value
     * keeps its hue and takes the brightness of that position.
     */
    private Color reposition(Color original, Color background, Color foreground) {
        Color neutral = mix(background, foreground, positionOf(original));

        float[] hsb = Color.RGBtoHSB(original.getRed(), original.getGreen(), original.getBlue(), null);
        if (hsb[1] < CHROMATIC_SATURATION) return neutral;

        float[] neutralHsb = Color.RGBtoHSB(neutral.getRed(), neutral.getGreen(), neutral.getBlue(), null);
        return new Color(Color.HSBtoRGB(hsb[0], hsb[1], neutralHsb[2]));
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

    private static Color readColor(UIDefaults defaults, String... keys) {
        for (String key : keys) {
            try {
                Color color = defaults.getColor(key);
                if (color != null) return color;
            } catch (RuntimeException ignored) {
                //Try the next candidate.
            }
        }
        return null;
    }

    /**
     * The colours of the theme being applied, which everything Burp specific is derived from.
     */
    private static final class Palette {

        private final Color panelBackground, panelForeground;
        private final Color inputBackground, inputForeground;
        private final Color tableBackground, tableForeground;
        private final Color treeBackground, treeForeground;
        private final Color buttonBackground, buttonForeground;
        final Color selectionBackground, selectionForeground;
        final Color accent, focus, separator;

        Palette(UIDefaults defaults) {
            panelBackground = orDefault(readColor(defaults, "Panel.background", "control"), Color.LIGHT_GRAY);
            panelForeground = orDefault(readColor(defaults, "Panel.foreground", "Label.foreground", "controlText"), Color.BLACK);
            inputBackground = orDefault(readColor(defaults, "TextField.background", "EditorPane.background"), panelBackground);
            inputForeground = orDefault(readColor(defaults, "TextField.foreground", "EditorPane.foreground"), panelForeground);
            tableBackground = orDefault(readColor(defaults, "Table.background", "List.background"), inputBackground);
            tableForeground = orDefault(readColor(defaults, "Table.foreground", "List.foreground"), inputForeground);
            treeBackground = orDefault(readColor(defaults, "Tree.background"), tableBackground);
            treeForeground = orDefault(readColor(defaults, "Tree.foreground"), tableForeground);
            buttonBackground = orDefault(readColor(defaults, "Button.background"), panelBackground);
            buttonForeground = orDefault(readColor(defaults, "Button.foreground"), panelForeground);
            selectionBackground = orDefault(readColor(defaults, "Table.selectionBackground", "List.selectionBackground"),
                    mix(panelBackground, panelForeground, 0.25f));
            selectionForeground = orDefault(readColor(defaults, "Table.selectionForeground", "List.selectionForeground"), panelForeground);
            accent = orDefault(readColor(defaults, "Component.accentColor", "Component.focusedBorderColor",
                    "Button.focusedBorderColor", "TabbedPane.underlineColor"), selectionBackground);
            focus = orDefault(readColor(defaults, "Component.focusColor", "Component.focusedBorderColor"), accent);
            separator = orDefault(readColor(defaults, "Separator.foreground", "Component.borderColor"),
                    mix(panelBackground, panelForeground, 0.35f));
        }

        Color backgroundFor(String name) {
            if (containsAny(name, "editor", "textinput", "textfield", "search", "input", "message")) return inputBackground;
            if (containsAny(name, "table", "row", "grid", "list", "cell")) return tableBackground;
            if (name.contains("tree")) return treeBackground;
            if (name.contains("button")) return buttonBackground;
            return panelBackground;
        }

        Color foregroundFor(String name) {
            if (containsAny(name, "editor", "textinput", "textfield", "search", "input", "message")) return inputForeground;
            if (containsAny(name, "table", "row", "grid", "list", "cell")) return tableForeground;
            if (name.contains("tree")) return treeForeground;
            if (name.contains("button")) return buttonForeground;
            return panelForeground;
        }

        private static Color orDefault(Color color, Color fallback) {
            return color != null ? color : fallback;
        }
    }
}
