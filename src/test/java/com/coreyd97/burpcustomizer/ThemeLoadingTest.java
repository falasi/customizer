package com.coreyd97.burpcustomizer;

import com.formdev.flatlaf.IntelliJTheme;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.swing.*;
import java.awt.Color;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the same path Burp uses - {@link ThemeManager#applyBundledTheme} ->
 * {@link CustomTheme} -> {@code UIManager.setLookAndFeel} -> {@code getDefaults()} - with
 * stand-ins for Burp's own look and feel classes on the classpath, so that Burp's defaults
 * layer is really loaded and unresolved properties really fail the test.
 */
class ThemeLoadingTest {

    /**
     * Representative defaults which must resolve to a colour for every theme.
     */
    private static final String[] REPRESENTATIVE_KEYS = {
            "Panel.background",
            "TextField.background",
            "Table.background",
            "Tree.background",
            "Separator.foreground",
            "Button.background",
            "Button.focusedBorderColor",
            "ProgressBar.foreground",
            "Component.focusColor",
    };

    private ThemeManager themeManager;

    @BeforeEach
    void setUp() {
        themeManager = new ThemeManager(UIManager.getLookAndFeel(), null);
    }

    private UIManager.LookAndFeelInfo theme(String name) {
        return themeManager.getThemes().stream()
                .filter(info -> info.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Theme not in the catalogue: " + name));
    }

    /**
     * Guards the test itself: if Burp's stand-in defaults were not loaded, every other test
     * here would pass for the wrong reason.
     */
    @Test
    void burpDefaultsAreLoadedIntoTheTheme() throws ThemeLoadException {
        themeManager.applyBundledTheme(theme("Catppuccin Mocha"));
        UIDefaults defaults = UIManager.getLookAndFeelDefaults();
        assertEquals("dark", defaults.get("Burp.regressionPolarity"),
                "Burp's own properties were not loaded, so this test proves nothing");
        assertEquals(new Color(0xff6633), defaults.get("Burp.burpOrangeUnmapped"),
                "Burp's plain values should survive into the theme");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Arc Dark", "Dracula", "Solarized Light",
            "Catppuccin Latte", "Catppuccin Frappé", "Catppuccin Macchiato", "Catppuccin Mocha"})
    void themeAppliesAndResolvesItsDefaults(String name) throws ThemeLoadException {
        themeManager.applyBundledTheme(theme(name));

        assertInstanceOf(CustomTheme.class, UIManager.getLookAndFeel(),
                name + ": Burp's override layer was not applied");

        UIDefaults defaults = UIManager.getLookAndFeelDefaults();
        for (String key : REPRESENTATIVE_KEYS) {
            Object value = defaults.get(key);
            assertNotNull(value, name + ": " + key + " is not defined");
            assertInstanceOf(Color.class, value, name + ": " + key + " did not resolve to a colour");
        }
        assertNotEquals(defaults.get("Panel.background"), defaults.get("Label.foreground"),
                name + ": foreground and background resolved to the same colour");
    }

    /**
     * The references Burp makes to its own theme's named colours must be filled in rather
     * than aborting the look and feel.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Dracula", "Catppuccin Latte", "Catppuccin Mocha"})
    void unresolvableBurpPaletteReferencesAreGivenFallbacks(String name) throws ThemeLoadException {
        themeManager.applyBundledTheme(theme(name));
        UIDefaults defaults = UIManager.getLookAndFeelDefaults();

        for (String key : new String[]{"Burp.regressionSeparator", "Burp.regressionEditorBackground",
                "Burp.regressionDisabledText", "Burp.regressionSelection", "Burp.regressionToolBackground",
                "Burp.regressionDerived", "Burp.regressionMono", "Burp.regressionPolarityColor"}) {
            assertInstanceOf(Color.class, defaults.get(key), name + ": " + key + " was not given a fallback");
        }
    }

    /**
     * Fallbacks must only fill gaps - a palette colour Burp defines itself still wins.
     */
    @ParameterizedTest
    @CsvSource({"Catppuccin Mocha,1193046", "Catppuccin Latte,6636321"})
    void burpsOwnPaletteDefinitionsWinOverFallbacks(String name, int expectedRgb) throws ThemeLoadException {
        themeManager.applyBundledTheme(theme(name));
        assertEquals(new Color(expectedRgb), UIManager.getLookAndFeelDefaults().get("Burp.regressionDefined"));
    }

    /**
     * A definition which only applies to the other polarity is not a definition: a light
     * theme must still get a fallback for a colour Burp only defines under {@code [dark]}.
     */
    @Test
    void conditionalBurpDefinitionsOnlyCountForTheMatchingPolarity() throws ThemeLoadException {
        themeManager.applyBundledTheme(theme("Catppuccin Mocha"));
        assertEquals(Color.decode("#abcdef"), UIManager.getLookAndFeelDefaults().get("Burp.regressionDarkOnly"),
                "A dark theme should use Burp's own [dark] definition");

        themeManager.applyBundledTheme(theme("Catppuccin Latte"));
        Object lightValue = UIManager.getLookAndFeelDefaults().get("Burp.regressionDarkOnly");
        assertInstanceOf(Color.class, lightValue, "A light theme should fall back rather than fail");
        assertNotEquals(Color.decode("#abcdef"), lightValue);
    }

    /**
     * A Burp defaults layer which cannot be repaired must cost the Burp specific keys, not
     * the whole look and feel.
     */
    @Test
    void themeStillAppliesWhenBurpDefaultsCannotBeResolvedAtAll() throws Exception {
        IntelliJTheme.ThemeLaf base;
        try (InputStream in = getClass().getResourceAsStream("/themes/Catppuccin-Mocha.theme.json")) {
            base = new IntelliJTheme.ThemeLaf(new IntelliJTheme(in));
        }

        LookAndFeel unfixable = new CustomTheme(base, false) {
            @Override
            protected ArrayList<Class<?>> getLafClassesForDefaultsLoading() {
                ArrayList<Class<?>> lafClasses = super.getLafClassesForDefaultsLoading();
                // Only sabotage the attempt which still includes Burp's defaults, so the
                // retry without them is a fair test.
                if (lafClasses.contains(burp.theme.BurpLaf.class)) lafClasses.add(UnfixableDefaults.class);
                return lafClasses;
            }
        };

        assertDoesNotThrow(() -> UIManager.setLookAndFeel(unfixable));
        UIDefaults defaults = UIManager.getLookAndFeelDefaults();
        for (String key : REPRESENTATIVE_KEYS)
            assertInstanceOf(Color.class, defaults.get(key), key + " should still resolve without Burp's defaults");
        assertNull(defaults.get("Burp.regressionPolarity"), "Burp's defaults should have been dropped");
    }

    @ParameterizedTest
    @CsvSource({
            //             panel,   content, chrome,  accent,  text
            "Catppuccin Latte,     #e6e9ef, #eff1f5, #dce0e8, #8839ef, #4c4f69",
            "Catppuccin Frappé,    #303446, #232634, #292c3c, #ca9ee6, #c6d0f5",
            "Catppuccin Macchiato, #24273a, #181926, #1e2030, #c6a0f6, #cad3f5",
            "Catppuccin Mocha,     #1e1e2e, #11111b, #181825, #cba6f7, #cdd6f4",
    })
    void catppuccinFlavourUsesItsOfficialPalette(String name, String panel, String content, String chrome,
                                                 String accent, String text) throws ThemeLoadException {
        themeManager.applyBundledTheme(theme(name));
        UIDefaults defaults = UIManager.getLookAndFeelDefaults();

        assertEquals(Color.decode(panel), defaults.get("Panel.background"));
        assertEquals(Color.decode(content), defaults.get("TextField.background"));
        assertEquals(Color.decode(content), defaults.get("Table.background"));
        assertEquals(Color.decode(content), defaults.get("Tree.background"));
        assertEquals(Color.decode(chrome), defaults.get("MenuBar.background"));
        assertEquals(Color.decode(accent), defaults.get("TabbedPane.underlineColor"));
        assertEquals(Color.decode(accent), defaults.get("Button.focusedBorderColor"));
        assertEquals(Color.decode(accent), defaults.get("Component.accentColor"));
        assertEquals(Color.decode(accent), defaults.get("ProgressBar.foreground"));
        assertEquals(Color.decode(text), defaults.get("Label.foreground"));
    }

    /**
     * The bundled theme json files must be self contained - FlatLaf cannot resolve a
     * {@code $property} or {@code @variable} reference from a theme's "ui" section.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Catppuccin-Latte", "Catppuccin-Frappe", "Catppuccin-Macchiato", "Catppuccin-Mocha"})
    void bundledThemeJsonHasNoUnresolvableReferences(String resource) throws IOException {
        String json;
        try (InputStream in = getClass().getResourceAsStream("/themes/" + resource + ".theme.json")) {
            json = new String(assertDoesNotThrow(() -> in.readAllBytes()), StandardCharsets.UTF_8);
        }

        List<String> offenders = new ArrayList<>();
        // Every value in "colors" and "ui" must be a literal or a name defined in "colors".
        java.util.Set<String> names = new java.util.HashSet<>();
        java.util.regex.Matcher colorNames = java.util.regex.Pattern
                .compile("\"([A-Za-z0-9_]+)\"\\s*:\\s*\"(#[0-9a-fA-F]{3,8})\"").matcher(json);
        while (colorNames.find()) names.add(colorNames.group(1));

        java.util.regex.Matcher values = java.util.regex.Pattern
                .compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        while (values.find()) {
            String key = values.group(1);
            String value = values.group(2);
            if (key.equals("name") || key.equals("author")) continue; //metadata, not a colour
            if (value.startsWith("$") || value.startsWith("@")) {
                offenders.add(value);
                continue;
            }
            boolean literal = value.isEmpty() || value.startsWith("#") || value.matches("-?\\d+")
                    || value.equals("true") || value.equals("false") || names.contains(value);
            if (!literal) offenders.add(value);
        }
        assertTrue(offenders.isEmpty(), resource + " references values which are not defined by the theme: " + offenders);
    }

    @Test
    void externalThemeFileIsLoadedThroughTheSamePath() throws Exception {
        File external = File.createTempFile("external-", ".theme.json");
        external.deleteOnExit();
        try (InputStream in = getClass().getResourceAsStream("/themes/Catppuccin-Mocha.theme.json")) {
            Files.copy(in, external.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        themeManager.applyCustomTheme(external);

        assertEquals("Catppuccin Mocha", themeManager.getCustomThemeName());
        assertEquals(ThemeManager.ThemeSource.FILE, themeManager.getThemeSource());
        assertEquals(Color.decode("#1e1e2e"), UIManager.getLookAndFeelDefaults().get("Panel.background"));
    }

    @Test
    void brokenThemeFilesAreReportedNotThrownRaw() throws Exception {
        File invalidJson = File.createTempFile("broken-", ".theme.json");
        invalidJson.deleteOnExit();
        try (FileWriter writer = new FileWriter(invalidJson)) { writer.write("{ this is not json"); }
        assertThrows(ThemeLoadException.class, () -> themeManager.applyCustomTheme(invalidJson));

        File notATheme = File.createTempFile("nottheme-", ".json");
        notATheme.deleteOnExit();
        try (FileWriter writer = new FileWriter(notATheme)) { writer.write("{\"dark\": true, \"ui\": {}}"); }
        assertThrows(ThemeLoadException.class, () -> themeManager.applyCustomTheme(notATheme));

        assertThrows(ThemeLoadException.class, () -> themeManager.applyCustomTheme(new File("/does/not/exist.theme.json")));
    }

    @Test
    void missingCustomThemeFileFallsBackToTheRememberedBundledTheme() throws Exception {
        themeManager.applyBundledTheme(theme("Catppuccin Mocha"));

        File vanishing = File.createTempFile("vanishing-", ".theme.json");
        try (InputStream in = getClass().getResourceAsStream("/themes/Catppuccin-Latte.theme.json")) {
            Files.copy(in, vanishing.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        themeManager.applyCustomTheme(vanishing);
        assertTrue(vanishing.delete());

        assertDoesNotThrow(() -> themeManager.restoreSavedTheme());
        assertEquals(Color.decode("#1e1e2e"), UIManager.getLookAndFeelDefaults().get("Panel.background"));
    }
}
