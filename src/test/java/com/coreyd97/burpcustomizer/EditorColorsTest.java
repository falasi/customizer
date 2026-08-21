package com.coreyd97.burpcustomizer;

import burp.ui.laf.PortSwiggerDarkTheme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.plaf.metal.MetalLookAndFeel;
import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The user's own message editor colours: optional overrides laid over whatever the theme and
 * Burp's defaults resolved, remembered per Burp key rather than per theme or per component.
 */
class EditorColorsTest {

    private static final String PARAM_VALUE = "Colors.ui.editor.message.paramValue";
    private static final String STRING = "Colors.ui.editor.message.literalString";
    private static final Color CHOSEN = new Color(0x81c8be);

    /**
     * Stands in for Burp's preferences, so what a session saves can be handed to the next one.
     */
    private final Map<String, String> saved = new HashMap<>();

    private ThemeManager themeManager;

    private EditorColors.Store store() {
        return new EditorColors.Store() {
            @Override
            public String get(String key) {
                return saved.get(key);
            }

            @Override
            public void put(String key, String value) {
                saved.put(key, value);
            }

            @Override
            public void remove(String key) {
                saved.remove(key);
            }
        };
    }

    /**
     * Starts the extension as Burp does, with whatever is already in the preferences.
     */
    private EditorColors startUp() throws Exception {
        UIManager.setLookAndFeel(new PortSwiggerDarkTheme());
        themeManager = new ThemeManager(UIManager.getLookAndFeel(), null);
        EditorColors colors = new EditorColors(store());
        CustomTheme.setEditorColors(colors);
        return colors;
    }

    private void apply(String themeName) throws ThemeLoadException {
        themeManager.applyBundledTheme(themeManager.getThemes().stream()
                .filter(info -> info.getName().equals(themeName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Theme not in the catalogue: " + themeName)));
    }

    private static Color live(String key) {
        Object value = UIManager.getLookAndFeelDefaults().get(key);
        return value instanceof Color colour ? colour : null;
    }

    @BeforeEach
    void reset() {
        saved.clear();
    }

    @AfterEach
    void restoreLookAndFeel() throws Exception {
        CustomTheme.setEditorColors(null);
        UIManager.setLookAndFeel(new MetalLookAndFeel());
    }

    @Test
    void withoutOverridesTheThemesOwnEditorColourIsUsed() throws Exception {
        EditorColors colors = startUp();
        apply("Catppuccin Frappé");

        assertFalse(colors.isOverridden(PARAM_VALUE), "nothing should be overridden to begin with");
        assertNotNull(colors.themeColour(PARAM_VALUE), "the theme should have resolved a colour");
        assertEquals(colors.themeColour(PARAM_VALUE), live(PARAM_VALUE),
                "the theme's own colour should be the one in use");
        assertTrue(saved.isEmpty(), "nothing should have been saved");
    }

    @Test
    void anOverrideWinsOverTheTheme() throws Exception {
        EditorColors colors = startUp();
        apply("Catppuccin Frappé");
        Color fromTheme = colors.themeColour(PARAM_VALUE);

        colors.set(PARAM_VALUE, CHOSEN);

        assertEquals(CHOSEN, live(PARAM_VALUE), "the user's colour should be in use");
        assertEquals(CHOSEN, colors.effective(PARAM_VALUE));
        assertTrue(colors.isOverridden(PARAM_VALUE));
        assertEquals(fromTheme, colors.themeColour(PARAM_VALUE),
                "the theme's own colour should still be known, so it can be restored");
    }

    @Test
    void resettingOneRoleRestoresTheThemesColour() throws Exception {
        EditorColors colors = startUp();
        apply("Catppuccin Frappé");
        Color fromTheme = colors.themeColour(PARAM_VALUE);
        colors.set(PARAM_VALUE, CHOSEN);

        colors.reset(PARAM_VALUE);

        assertFalse(colors.isOverridden(PARAM_VALUE));
        assertEquals(fromTheme, live(PARAM_VALUE), "the theme's colour should be back");
        assertFalse(saved.containsKey(EditorColors.PREFERENCE_PREFIX + PARAM_VALUE),
                "reset should delete the override rather than remember a colour");
    }

    @Test
    void anOverrideSurvivesAThemeChange() throws Exception {
        EditorColors colors = startUp();
        apply("Catppuccin Frappé");
        colors.set(PARAM_VALUE, CHOSEN);

        apply("Catppuccin Mocha");

        assertEquals(CHOSEN, live(PARAM_VALUE), "an explicit choice should outlast the theme it was made under");
        assertNotEquals(CHOSEN, colors.themeColour(PARAM_VALUE),
                "and the new theme's own colour should be what it resolved, not the override");
    }

    @Test
    void afterAResetTheNewThemesColourIsUsed() throws Exception {
        EditorColors colors = startUp();
        apply("Catppuccin Frappé");
        colors.set(PARAM_VALUE, CHOSEN);
        colors.reset(PARAM_VALUE);

        apply("Catppuccin Mocha");

        assertEquals(colors.themeColour(PARAM_VALUE), live(PARAM_VALUE),
                "the row should follow the theme again, not a colour from the previous one");
        assertNotEquals(CHOSEN, live(PARAM_VALUE));
    }

    @Test
    void savedOverridesComeBackOnTheNextStartUp() throws Exception {
        EditorColors first = startUp();
        apply("Catppuccin Frappé");
        first.set(PARAM_VALUE, CHOSEN);
        assertEquals("#81c8be", saved.get(EditorColors.PREFERENCE_PREFIX + PARAM_VALUE),
                "the colour should be saved under the Burp key it belongs to");

        //A new session: same preferences, everything else built from scratch.
        EditorColors next = startUp();
        apply("Catppuccin Frappé");

        assertTrue(next.isOverridden(PARAM_VALUE));
        assertEquals(CHOSEN, live(PARAM_VALUE), "the saved colour should be applied on startup");
    }

    @Test
    void resetAllPutsEveryRoleBackToTheTheme() throws Exception {
        EditorColors colors = startUp();
        apply("Catppuccin Frappé");
        Map<String, Color> fromTheme = new HashMap<>();
        for (EditorColors.Role role : EditorColors.ROLES) {
            fromTheme.put(role.key(), colors.themeColour(role.key()));
            colors.set(role.key(), CHOSEN);
        }

        colors.resetAll();

        for (EditorColors.Role role : EditorColors.ROLES) {
            assertFalse(colors.isOverridden(role.key()), role.label() + " should no longer be overridden");
            assertEquals(fromTheme.get(role.key()), live(role.key()), role.label() + " should be the theme's again");
        }
        assertTrue(saved.isEmpty(), "every saved override should have been deleted");
    }

    @Test
    void aLateCreatedEditorReadsTheChosenColourFromTheDefaults() throws Exception {
        EditorColors colors = startUp();
        apply("Catppuccin Frappé");

        colors.set(PARAM_VALUE, CHOSEN);

        //Nothing was painted onto a component: the colour is in the defaults every component
        //reads when it is built, so one Burp creates later needs no help.
        assertEquals(CHOSEN, UIManager.getColor(PARAM_VALUE),
                "a component built after the change should read the user's colour");
        assertEquals(CHOSEN, UIManager.getLookAndFeelDefaults().get(PARAM_VALUE));
    }

    @Test
    void everyExposedRoleIsAKeyBurpDefines() throws Exception {
        EditorColors colors = startUp();
        apply("Catppuccin Frappé");

        for (EditorColors.Role role : EditorColors.ROLES) {
            assertTrue(role.key().startsWith("Colors.ui.editor.message."),
                    role.label() + " should be a message editor key, was " + role.key());
            assertNotNull(colors.themeColour(role.key()),
                    role.label() + " (" + role.key() + ") is not a key Burp defines");
        }
        assertEquals(8, EditorColors.ROLES.size());
    }

    @Test
    void onlyMessageEditorColoursAreTouched() throws Exception {
        EditorColors colors = startUp();
        apply("Catppuccin Frappé");
        Color panel = live("Panel.background");
        Color accent = live("Component.accentColor");

        for (EditorColors.Role role : EditorColors.ROLES) colors.set(role.key(), CHOSEN);

        assertEquals(panel, live("Panel.background"), "the panel background is not an editor colour");
        assertEquals(accent, live("Component.accentColor"), "the accent is not an editor colour");
    }
}
