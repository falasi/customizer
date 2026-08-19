package com.coreyd97.burpcustomizer;

import burp.ui.laf.PortSwiggerDarkTheme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.swing.*;
import javax.swing.plaf.metal.MetalLookAndFeel;
import java.awt.Color;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers what the extension makes of the look and feel Burp is actually running: finding the
 * classes Burp keeps its UI defaults in whatever they are called, and keeping Burp's own
 * components readable when those defaults cannot be reloaded at all.
 */
class BurpDefaultsDiscoveryTest {

    /**
     * WCAG AA for normal text. Disabled text is exempt and checked separately.
     */
    private static final double MINIMUM_CONTRAST = 4.5;

    private ThemeManager install(LookAndFeel burpLookAndFeel) throws Exception {
        UIManager.setLookAndFeel(burpLookAndFeel);
        //ThemeManager inspects and captures the installed look and feel, as it does when the
        //extension is initialised inside Burp.
        return new ThemeManager(UIManager.getLookAndFeel(), null);
    }

    private UIManager.LookAndFeelInfo theme(ThemeManager themeManager, String name) {
        return themeManager.getThemes().stream()
                .filter(info -> info.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Theme not in the catalogue: " + name));
    }

    @AfterEach
    void resetLookAndFeel() throws Exception {
        UIManager.setLookAndFeel(new MetalLookAndFeel());
    }

    /**
     * Old Burp, old class names: the defaults still come from Burp's own properties files.
     */
    @Test
    void legacyBurpClassesAreStillUsed() throws Exception {
        ThemeManager themeManager = install(new burp.theme.BurpDarkLaf());
        themeManager.applyBundledTheme(theme(themeManager, "Catppuccin Mocha"));

        assertEquals("dark", UIManager.getLookAndFeelDefaults().get("Burp.regressionPolarity"),
                "Burp's own dark properties should have been loaded");
    }

    /**
     * A Burp release which renamed and moved its look and feel classes must still be found,
     * because they are discovered from the running look and feel rather than by name.
     */
    @Test
    void renamedBurpClassesAreDiscoveredFromTheRunningLookAndFeel() throws Exception {
        ThemeManager themeManager = install(new PortSwiggerDarkTheme());
        themeManager.applyBundledTheme(theme(themeManager, "Catppuccin Mocha"));
        UIDefaults defaults = UIManager.getLookAndFeelDefaults();

        assertEquals(new Color(0x0a0b0c), defaults.get("Burp.modernPropertiesKey"),
                "The renamed Burp classes' own defaults should have been loaded");
        assertEquals("dark", defaults.get("Burp.modernPolarity"),
                "The dark variant of the renamed classes should have been used for a dark theme");
        assertInstanceOf(Color.class, defaults.get("Burp.modernSeparator"),
                "A reference the renamed classes cannot resolve should still be filled in");
    }

    /**
     * The polarity variant is picked to match the theme, not to match what Burp was using:
     * a light theme must not be handed Burp's dark values.
     */
    @Test
    void polarityFollowsTheThemeNotBurp() throws Exception {
        ThemeManager themeManager = install(new PortSwiggerDarkTheme());

        themeManager.applyBundledTheme(theme(themeManager, "Catppuccin Latte"));
        assertEquals("light", UIManager.getLookAndFeelDefaults().get("Burp.modernPolarity"));

        themeManager.applyBundledTheme(theme(themeManager, "Catppuccin Mocha"));
        assertEquals("dark", UIManager.getLookAndFeelDefaults().get("Burp.modernPolarity"));
    }

    /**
     * Defaults Burp defines in code cannot be reloaded from any properties file. They must
     * still be present after theming - this is the case that left Burp unreadable.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Catppuccin Latte", "Catppuccin Mocha", "Dracula", "Solarized Light"})
    void burpKeysDefinedInCodeAreDerivedFromTheTheme(String themeName) throws Exception {
        ThemeManager themeManager = install(new PortSwiggerDarkTheme());
        List<String> burpKeys = burpKeysOf(UIManager.getLookAndFeelDefaults());
        assertFalse(burpKeys.isEmpty(), "the stand-in should define Burp keys to begin with");

        themeManager.applyBundledTheme(theme(themeManager, themeName));
        UIDefaults defaults = UIManager.getLookAndFeelDefaults();

        for (String key : burpKeys)
            assertNotNull(defaults.get(key), themeName + ": " + key + " was left undefined");
    }

    /**
     * Every foreground Burp pairs with a background has to stay readable against it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Catppuccin Latte", "Catppuccin Frappé", "Catppuccin Macchiato",
            "Catppuccin Mocha", "Dracula", "Arc Dark", "Solarized Light"})
    void derivedBurpTextStaysReadable(String themeName) throws Exception {
        ThemeManager themeManager = install(new PortSwiggerDarkTheme());
        themeManager.applyBundledTheme(theme(themeManager, themeName));
        UIDefaults defaults = UIManager.getLookAndFeelDefaults();

        for (String[] pair : PortSwiggerDarkTheme.TEXT_PAIRS) {
            Color foreground = (Color) defaults.get(pair[0]);
            Color background = (Color) defaults.get(pair[1]);
            assertNotNull(foreground, themeName + ": " + pair[0]);
            assertNotNull(background, themeName + ": " + pair[1]);

            //Selected rows use the theme's own selection pairing, which is what every
            //ordinary Swing table in Burp uses too, so it is held to a visibility floor
            //rather than to the threshold for colours this extension made up.
            double required = pair[0].contains("selected") ? 3.0 : MINIMUM_CONTRAST;
            double contrast = BurpDefaults.contrast(foreground, background);
            assertTrue(contrast >= required, String.format(
                    "%s: %s on %s is unreadable (contrast %.2f, need %.1f)",
                    themeName, pair[0], pair[1], contrast, required));
        }

        //Disabled text is meant to be quiet, but still has to be visible against the panel.
        Color disabled = (Color) defaults.get("Burp.disabledText");
        Color panel = defaults.getColor("Panel.background");
        assertNotNull(disabled);
        assertTrue(BurpDefaults.contrast(disabled, panel) >= 1.8,
                themeName + ": Burp.disabledText has vanished into the background");
    }

    /**
     * Derived values must come from the theme, not from the colours Burp happened to have.
     */
    @Test
    void derivedValuesUseTheThemesPaletteRatherThanBurpsOldColours() throws Exception {
        ThemeManager themeManager = install(new PortSwiggerDarkTheme());
        themeManager.applyBundledTheme(theme(themeManager, "Catppuccin Latte"));
        UIDefaults defaults = UIManager.getLookAndFeelDefaults();

        Color editorBackground = (Color) defaults.get("Burp.messageEditorBackground");
        assertNotEquals(new Color(0x1e1e1e), editorBackground, "Burp's dark editor colour survived into a light theme");
        assertTrue(BurpDefaults.contrast(editorBackground, defaults.getColor("TextField.background")) < 1.5,
                "an editor background should sit in the theme's own editor palette, was " + editorBackground);

        Color panelBackground = (Color) defaults.get("Burp.settingsPanelBackground");
        assertTrue(BurpDefaults.contrast(panelBackground, defaults.getColor("Panel.background")) < 1.5,
                "a panel background should sit in the theme's own panel palette, was " + panelBackground);

        //Burp's grey ramps should still be a ramp, in the theme's greys.
        Color first = (Color) defaults.get("ColourPalette.mono0");
        Color last = (Color) defaults.get("ColourPalette.mono8");
        assertNotEquals(first, last, "the grey ramp collapsed to a single colour");
    }

    /**
     * Sizes and insets are not theme dependent, so they carry over untouched.
     */
    @Test
    void nonColourBurpDefaultsCarryOverUnchanged() throws Exception {
        ThemeManager themeManager = install(new PortSwiggerDarkTheme());
        themeManager.applyBundledTheme(theme(themeManager, "Catppuccin Mocha"));

        assertEquals(new Insets(2, 8, 2, 8), UIManager.getLookAndFeelDefaults().get("Burp.tabInsets"));
    }

    /**
     * What was discovered has to be reportable, or a Burp release which moves its look and
     * feel again cannot be diagnosed from a user's log.
     */
    @Test
    void discoveryIsReported() throws Exception {
        ThemeManager themeManager = install(new PortSwiggerDarkTheme());
        themeManager.applyBundledTheme(theme(themeManager, "Catppuccin Mocha"));

        String report = CustomTheme.describeBurpLookAndFeel();
        assertTrue(report.contains(PortSwiggerDarkTheme.class.getName()), "no active look and feel: " + report);
        assertTrue(report.contains("class loader: "), "no class loader: " + report);
        assertTrue(report.contains(burp.ui.laf.PortSwiggerTheme.class.getName()), "no superclasses: " + report);
        assertTrue(report.contains("Burp defaults classes: "), "no discovered classes: " + report);
        assertTrue(report.contains("(dark)"), "the polarity variant used is not named: " + report);
        assertTrue(report.contains("Burp specific defaults captured before theming: "), "nothing about capture: " + report);
    }

    private static List<String> burpKeysOf(UIDefaults defaults) {
        List<String> keys = new ArrayList<>();
        for (Object key : new ArrayList<>(defaults.keySet())) {
            if (key instanceof String name && BurpDefaults.isBurpKey(name) && defaults.get(name) != null)
                keys.add(name);
        }
        return keys;
    }
}
