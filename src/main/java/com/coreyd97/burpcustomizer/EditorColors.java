package com.coreyd97.burpcustomizer;

import javax.swing.*;
import javax.swing.plaf.ColorUIResource;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The colours the user has chosen for Burp's message editor, layered on top of whatever the
 * theme resolved.
 * <p>
 * A colour is only ever one of two things:
 * <pre>
 * effective(key) = override(key) != null ? override(key) : themeColour(key)
 * </pre>
 * The theme's own value is kept alongside the override rather than replaced, so resetting a
 * row restores what the <em>current</em> theme provides rather than a colour remembered from
 * whichever theme happened to be active when the override was made. Nothing is written back
 * into the bundled theme files.
 * <p>
 * Overrides are applied to the theme's {@code UIDefaults} - not painted onto components - so a
 * message editor Burp creates later in the session picks them up on its own.
 */
final class EditorColors {

    /**
     * A syntax category the user can change, and the Burp key behind it. Every one of these is
     * a key Burp defines itself; there is no role here without one.
     */
    record Role(String label, String key) {
    }

    private static final String MESSAGE = "Colors.ui.editor.message.";

    static final List<Role> ROLES = List.of(
            new Role("Parameter name", MESSAGE + "paramName"),
            new Role("Parameter value", MESSAGE + "paramValue"),
            new Role("String", MESSAGE + "literalString"),
            new Role("Number", MESSAGE + "literalNumber"),
            new Role("Boolean", MESSAGE + "literalBoolean"),
            new Role("Regex", MESSAGE + "regex"),
            new Role("Entity/reference", MESSAGE + "entityReference"),
            new Role("Keyword/special", MESSAGE + "reservedWord"));

    /**
     * Overrides are remembered per Burp key, so they mean the same thing whichever theme is
     * applied and whichever components happen to exist.
     */
    static final String PREFERENCE_PREFIX = "editorColor.";

    /**
     * Where the choices are kept between sessions. Burp's own preferences in Burp; a map in
     * tests.
     */
    interface Store {
        String get(String key);

        void put(String key, String value);

        void remove(String key);
    }

    private static final Store BURP_PREFERENCES = new Store() {
        @Override
        public String get(String key) {
            return BurpCustomizer.getPreference(key);
        }

        @Override
        public void put(String key, String value) {
            BurpCustomizer.setPreference(key, value);
        }

        @Override
        public void remove(String key) {
            BurpCustomizer.deletePreference(key);
        }
    };

    private final Store store;
    private final Map<String, Color> overrides = new LinkedHashMap<>();
    private final Map<String, Color> themeColours = new LinkedHashMap<>();

    EditorColors() {
        this(BURP_PREFERENCES);
    }

    EditorColors(Store store) {
        this.store = store;
        for (Role role : ROLES) {
            Color saved = parse(store.get(PREFERENCE_PREFIX + role.key()));
            if (saved != null) overrides.put(role.key(), saved);
        }
    }

    /**
     * Records what the theme resolved for each role and then lays the user's own choices over
     * the top. Called once the theme and Burp's own defaults are both in place, so an explicit
     * choice wins over both.
     */
    void applyTo(UIDefaults defaults) {
        themeColours.clear();
        for (Role role : ROLES) {
            Object resolved = defaults.get(role.key());
            if (resolved instanceof Color colour) themeColours.put(role.key(), colour);
        }
        for (Map.Entry<String, Color> override : overrides.entrySet())
            defaults.put(override.getKey(), new ColorUIResource(override.getValue()));
    }

    /**
     * What the active theme resolved for this role, whether or not the user has overridden it.
     */
    Color themeColour(String key) {
        return themeColours.get(key);
    }

    Color override(String key) {
        return overrides.get(key);
    }

    boolean isOverridden(String key) {
        return overrides.containsKey(key);
    }

    Color effective(String key) {
        Color override = overrides.get(key);
        return override != null ? override : themeColours.get(key);
    }

    /**
     * Takes the user's colour for a role, remembers it, and puts it into the defaults every
     * component - including one built later - reads from.
     */
    void set(String key, Color colour) {
        overrides.put(key, colour);
        store.put(PREFERENCE_PREFIX + key, hex(colour));
        putLive(key, colour);
    }

    /**
     * Forgets the user's colour for a role entirely, rather than remembering the theme's colour
     * as the new choice, and puts back what the theme currently resolves. Switching theme after
     * this leaves the row following the new theme.
     */
    void reset(String key) {
        overrides.remove(key);
        store.remove(PREFERENCE_PREFIX + key);
        putLive(key, themeColours.get(key));
    }

    void resetAll() {
        for (Role role : ROLES) reset(role.key());
    }

    private void putLive(String key, Color colour) {
        UIDefaults live = UIManager.getLookAndFeelDefaults();
        if (live == null) return;
        //A role the theme never defined goes back to being undefined, not to a colour we chose.
        if (colour == null) live.remove(key);
        else live.put(key, new ColorUIResource(colour));
    }

    static String hex(Color colour) {
        return String.format("#%02x%02x%02x", colour.getRed(), colour.getGreen(), colour.getBlue());
    }

    private static Color parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Color.decode(value.trim());
        } catch (NumberFormatException e) {
            BurpCustomizer.logError("Ignoring a saved editor colour which is not a colour: " + value, e);
            return null;
        }
    }
}
