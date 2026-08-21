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
import java.nio.file.Files;
import java.nio.file.Path;
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
     * A Burp key whose name says Background names a surface, never the text painted on it.
     * Both of these resolved to a foreground colour - {@code Burp.textEditorBackground} to
     * the theme's text colour and {@code Burp.collapsibleSidebarSelectedLabelBackground} to
     * its selected text colour - which put a light colour where a dark surface belonged.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Catppuccin Frappé", "Catppuccin Mocha", "Catppuccin Latte", "Dracula", "Solarized Light"})
    void burpKeysNamedBackgroundUseABackgroundProperty(String themeName) throws Exception {
        ThemeManager themeManager = install(new PortSwiggerDarkTheme());
        themeManager.applyBundledTheme(theme(themeManager, themeName));
        UIDefaults defaults = UIManager.getLookAndFeelDefaults();

        assertEquals(defaults.getColor("TextArea.background"), defaults.get("Burp.textEditorBackground"),
                themeName + ": an editor background should be the theme's editor surface");
        assertEquals(defaults.getColor("List.selectionBackground"),
                defaults.get("Burp.collapsibleSidebarSelectedLabelBackground"),
                themeName + ": a selected label background should be the theme's selected surface");

        //Nothing named Background may come out wearing one of the theme's text colours.
        for (String key : new String[]{"Burp.textEditorBackground", "Burp.textEditorCurrentLineBackground",
                "Burp.collapsibleSidebarSelectedLabelBackground"}) {
            Object value = defaults.get(key);
            assertInstanceOf(Color.class, value, themeName + ": " + key);
            for (String foreground : new String[]{"TextArea.foreground", "TextField.foreground",
                    "Label.foreground", "List.selectionForeground"}) {
                assertNotEquals(defaults.getColor(foreground), value,
                        themeName + ": " + key + " resolved to " + foreground + ", which is a text colour");
            }
        }
    }

    /**
     * The two keys named outright in the theme they were reported against: Frappé's editor
     * surface and its selected surface, rather than its light text colour (#c6d0f5).
     */
    @Test
    void frappeGivesTheBackgroundKeysItsOwnSurfaces() throws Exception {
        ThemeManager themeManager = install(new PortSwiggerDarkTheme());
        themeManager.applyBundledTheme(theme(themeManager, "Catppuccin Frappé"));
        UIDefaults defaults = UIManager.getLookAndFeelDefaults();

        assertEquals(Color.decode("#232634"), defaults.get("Burp.textEditorBackground"));
        assertEquals(Color.decode("#51576d"), defaults.get("Burp.collapsibleSidebarSelectedLabelBackground"));

        //Both are surfaces the theme's text is readable on, which is what makes them dark here.
        Color text = defaults.getColor("Label.foreground");
        assertTrue(BurpDefaults.contrast(text, (Color) defaults.get("Burp.textEditorBackground")) >= MINIMUM_CONTRAST,
                "Frappé's editor surface should be dark enough to read its text on");
        assertTrue(BurpDefaults.contrast(text, (Color) defaults.get("Burp.collapsibleSidebarSelectedLabelBackground")) >= 3.0,
                "Frappé's selected surface should be dark enough to read its label on");
    }

    /**
     * Burp derives some of its UI defaults - core Swing ones among them - from colours only
     * its own theme json defines, using FlatLaf's optional reference syntax. An optional
     * reference which does not resolve becomes null, and {@code UIDefaults.put(key, null)}
     * deletes the key, so {@code Panel.background} went missing before the theme was applied.
     * A theme which names {@code Panel.background} outright puts it back; a theme which sets
     * it only through its {@code "*"} wildcard cannot, because the wildcard only replaces
     * keys which already exist. FlatLaf then failed deriving {@code Desktop.background} from
     * it, and Burp's defaults were dropped for that theme as a whole.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Cyan light", "Dracula", "Solarized Light"})
    void themesSettingPanelBackgroundOnlyByWildcardKeepBurpsDefaults(String themeName) throws Exception {
        ThemeManager themeManager = install(new PortSwiggerDarkTheme());
        themeManager.applyBundledTheme(theme(themeManager, themeName));
        UIDefaults defaults = UIManager.getLookAndFeelDefaults();

        assertEquals(new Color(0x0a0b0c), defaults.get("Burp.modernPropertiesKey"),
                themeName + ": Burp's own defaults were dropped rather than applied");
        assertInstanceOf(Color.class, defaults.get("Panel.background"),
                themeName + ": Panel.background was deleted by a reference which resolved to null");
        assertInstanceOf(Color.class, defaults.get("Desktop.background"),
                themeName + ": Desktop.background could not be derived from Panel.background");
    }

    /**
     * The key an unresolved optional reference defines keeps a colour of its own, rather than
     * being deleted from the defaults.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Cyan light", "Catppuccin Frappé"})
    void optionalReferencesBurpCannotResolveStillDefineTheirKey(String themeName) throws Exception {
        ThemeManager themeManager = install(new PortSwiggerDarkTheme());
        themeManager.applyBundledTheme(theme(themeManager, themeName));

        assertInstanceOf(Color.class, UIManager.getLookAndFeelDefaults().get("Burp.modernOptionalReference"),
                themeName + ": the key was deleted instead of resolved against the theme");
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
     * A Burp key which points at one of Burp's own brand colours must come out as the theme's
     * accent, not as Burp orange.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Catppuccin Mocha", "Catppuccin Latte"})
    void unresolvedBrandChromeBecomesTheThemeAccent(String themeName) throws Exception {
        ThemeManager themeManager = install(new PortSwiggerDarkTheme());
        themeManager.applyBundledTheme(theme(themeManager, themeName));
        UIDefaults defaults = UIManager.getLookAndFeelDefaults();

        Color accent = defaults.getColor("Component.accentColor");
        assertNotNull(accent, "the theme should define an accent colour");

        for (String key : new String[]{"Burp.modernAccentBar", "Burp.modernFocusRing", "Burp.burpOrange",
                "Burp.tabFlashColour", "Burp.primaryButtonBackground"}) {
            assertEquals(accent, defaults.get(key),
                    themeName + ": " + key + " should be the theme's accent, was " + defaults.get(key));
        }
    }

    /**
     * Red that means something stays red - the theme's red, not Burp's, and not the accent.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Catppuccin Mocha", "Catppuccin Latte"})
    void stateColoursKeepTheirMeaning(String themeName) throws Exception {
        ThemeManager themeManager = install(new PortSwiggerDarkTheme());
        themeManager.applyBundledTheme(theme(themeManager, themeName));
        UIDefaults defaults = UIManager.getLookAndFeelDefaults();

        Color accent = defaults.getColor("Component.accentColor");
        Color themeError = defaults.getColor("Component.error.focusedBorderColor");
        Color themeWarning = defaults.getColor("Component.warning.focusedBorderColor");

        assertEquals(themeError, defaults.get("Burp.modernErrorForeground"), themeName + ": error foreground");
        assertEquals(themeError, defaults.get("Burp.errorForeground"), themeName + ": error foreground");
        assertEquals(themeWarning, defaults.get("Burp.warningForeground"), themeName + ": warning foreground");
        assertNotEquals(accent, defaults.get("Burp.errorForeground"), themeName + ": an error is not an accent");

        //A scanner severity is a state, so it keeps its own hue rather than becoming chrome.
        Color severity = (Color) defaults.get("Burp.issueSeverityHigh");
        assertNotNull(severity);
        float hue = Color.RGBtoHSB(severity.getRed(), severity.getGreen(), severity.getBlue(), null)[0] * 360f;
        assertTrue(hue <= 45f || hue >= 345f, themeName + ": Burp.issueSeverityHigh stopped being red, hue " + hue);
    }

    /**
     * Nothing Burp uses as ordinary chrome may come through theming still wearing Burp's
     * branding colour.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Catppuccin Mocha", "Catppuccin Latte"})
    void noBrandingColoursSurviveInChromeKeys(String themeName) throws Exception {
        ThemeManager themeManager = install(new PortSwiggerDarkTheme());
        themeManager.applyBundledTheme(theme(themeManager, themeName));

        List<String> remaining = CustomTheme.brandColouredBurpKeys(UIManager.getLookAndFeelDefaults());
        assertTrue(remaining.isEmpty(),
                themeName + ": Burp branding colours left in chrome keys (key -> Burp value -> themed value): " + remaining);
    }

    /**
     * The failure this was reported as: a Burp property referencing one of Burp's own
     * variables, contributed from somewhere the extension cannot read - here an application
     * defaults source, in Burp an addon or its own theme. Burp's defaults must survive it.
     */
    @Test
    void referencesFromSourcesTheScannerCannotReadDoNotCostBurpsDefaults() throws Exception {
        Path folder = Files.createTempDirectory("custom-defaults");
        Files.writeString(folder.resolve("PortSwiggerTheme.properties"),
                "Burp.applicationSuppliedAccent = @Colors.swatches.black.core\n");
        com.formdev.flatlaf.FlatLaf.registerCustomDefaultsSource(folder.toFile());
        try {
            ThemeManager themeManager = install(new PortSwiggerDarkTheme());
            themeManager.applyBundledTheme(theme(themeManager, "Catppuccin Macchiato"));
            UIDefaults defaults = UIManager.getLookAndFeelDefaults();

            assertInstanceOf(Color.class, defaults.get("Burp.applicationSuppliedAccent"),
                    "the unresolvable reference should have been resolved against the theme");
            assertEquals(defaults.getColor("Component.accentColor"), defaults.get("Burp.applicationSuppliedAccent"));
            assertEquals("dark", defaults.get("Burp.modernPolarity"),
                    "Burp's own defaults should still have been loaded");
        } finally {
            com.formdev.flatlaf.FlatLaf.unregisterCustomDefaultsSource(folder.toFile());
        }
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
