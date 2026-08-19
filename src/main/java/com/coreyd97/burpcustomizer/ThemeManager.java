package com.coreyd97.burpcustomizer;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.IntelliJTheme;
import com.formdev.flatlaf.intellijthemes.FlatAllIJThemes;
import lombok.Getter;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Owns the extension's theme catalogue and everything to do with loading, applying,
 * remembering and restoring themes.
 * <p>
 * Every theme - a bundled FlatLaf IntelliJ theme, a bundled {@code .theme.json} resource
 * (the Catppuccin flavours) or a user supplied {@code .theme.json} file - goes through the
 * same pipeline:
 * <pre>
 * theme JSON -&gt; FlatLaf IntelliJTheme -&gt; Burp specific overrides ({@link CustomTheme}) -&gt; Burp UI refresh
 * </pre>
 */
public class ThemeManager {

    public enum ThemeSource {BUILTIN, FILE}

    private static final String PREF_SOURCE = "source";
    private static final String PREF_BUILTIN_THEME = "theme";
    private static final String PREF_THEME_FILE = "themeFile";

    /**
     * Bundled {@code .theme.json} resources. They are loaded through the same code path as
     * external theme files, so the palettes live in resources instead of in Java code.
     */
    private static final ResourceTheme[] BUNDLED_JSON_THEMES = new ResourceTheme[]{
            new ResourceTheme("Catppuccin Latte", "/themes/Catppuccin-Latte.theme.json"),
            new ResourceTheme("Catppuccin Frappé", "/themes/Catppuccin-Frappe.theme.json"),
            new ResourceTheme("Catppuccin Macchiato", "/themes/Catppuccin-Macchiato.theme.json"),
            new ResourceTheme("Catppuccin Mocha", "/themes/Catppuccin-Mocha.theme.json"),
    };

    /**
     * Burp's own look and feel, as it was before the extension changed anything.
     */
    private final LookAndFeel originalBurpTheme;

    /**
     * Run after a theme has been applied, so the extension can re-install anything the
     * new look and feel replaced (Burp Customizer re-patches the shared PopupFactory).
     */
    private final Runnable afterThemeApplied;

    @Getter
    private final ArrayList<UIManager.LookAndFeelInfo> themes;
    @Getter
    private ThemeSource themeSource = ThemeSource.BUILTIN;
    @Getter
    private UIManager.LookAndFeelInfo selectedBuiltIn;
    @Getter
    private File selectedThemeFile;
    /**
     * Display name of the currently applied custom theme file, or null if none is applied.
     */
    @Getter
    private String customThemeName;

    public ThemeManager(LookAndFeel originalBurpTheme, Runnable afterThemeApplied) {
        this.originalBurpTheme = originalBurpTheme;
        this.afterThemeApplied = afterThemeApplied;
        if (originalBurpTheme != null) {
            // Burp's own look and feel is the most reliable handle on a class loader
            // which can actually see Burp's internal theme classes.
            CustomTheme.setBurpClassLoaderHint(originalBurpTheme.getClass().getClassLoader());
        }

        this.themes = new ArrayList<>(Arrays.stream(FlatAllIJThemes.INFOS)
                .filter(lookAndFeelInfo -> !lookAndFeelInfo.getName().equalsIgnoreCase("Xcode-Dark"))
                .map(flatIJLookAndFeelInfo -> (UIManager.LookAndFeelInfo) flatIJLookAndFeelInfo)
                .toList());
        this.themes.addAll(Arrays.asList(BUNDLED_JSON_THEMES));
        this.themes.sort(Comparator.comparing(UIManager.LookAndFeelInfo::getName));
    }

    // ---------------------------------------------------------------- loading

    /**
     * Creates the look and feel for a bundled theme, either a FlatLaf IntelliJ theme class
     * or a bundled {@code .theme.json} resource.
     */
    public LookAndFeel createTheme(UIManager.LookAndFeelInfo lookAndFeelInfo, boolean isPreview) throws ThemeLoadException {
        if (lookAndFeelInfo instanceof ResourceTheme resourceTheme) {
            try (InputStream in = resourceTheme.openStream()) {
                return createThemeFromStream(in, "The bundled theme \"" + resourceTheme.getName() + "\"", isPreview);
            } catch (IOException e) {
                throw new ThemeLoadException("The bundled theme \"" + resourceTheme.getName() + "\" could not be read.", e);
            }
        }

        try {
            Class<?> themeClass = Class.forName(lookAndFeelInfo.getClassName());
            IntelliJTheme.ThemeLaf theme = (IntelliJTheme.ThemeLaf) themeClass.getDeclaredConstructor().newInstance();
            return applyBurpOverrides(theme, isPreview);
        } catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
            throw new ThemeLoadException("The theme \"" + lookAndFeelInfo.getName() + "\" could not be loaded.\n" +
                    "It may not be included in this version of the extension.", e);
        }
    }

    /**
     * Creates the look and feel for a user supplied IntelliJ/FlatLaf {@code .theme.json} file.
     */
    public LookAndFeel createTheme(File themeJsonFile, boolean isPreview) throws ThemeLoadException {
        if (themeJsonFile == null || !themeJsonFile.isFile())
            throw new ThemeLoadException("The theme file could not be found:\n" +
                    (themeJsonFile != null ? themeJsonFile.getAbsolutePath() : "(no file selected)"));
        if (!themeJsonFile.canRead())
            throw new ThemeLoadException("The theme file cannot be read, check its permissions:\n" + themeJsonFile.getAbsolutePath());

        try (InputStream in = new FileInputStream(themeJsonFile)) {
            return createThemeFromStream(in, "\"" + themeJsonFile.getName() + "\"", isPreview);
        } catch (IOException e) {
            throw new ThemeLoadException("The theme file could not be read:\n" + themeJsonFile.getAbsolutePath(), e);
        }
    }

    /**
     * The single entry point for JSON themes. FlatLaf does the parsing, we only validate the
     * metadata it needs and then wrap the result so Burp's own UI properties are themed too.
     * The stream is closed by the caller.
     */
    private LookAndFeel createThemeFromStream(InputStream in, String sourceDescription, boolean isPreview) throws ThemeLoadException {
        IntelliJTheme intelliJTheme;
        try {
            intelliJTheme = new IntelliJTheme(in);
        } catch (IOException e) {
            throw new ThemeLoadException(sourceDescription + " is not valid JSON:\n" + e.getMessage(), e);
        } catch (RuntimeException e) {
            // FlatLaf expects "name", "author" and a *string* "dark" value. Files which
            // are valid JSON but not valid IntelliJ themes surface here.
            throw new ThemeLoadException(sourceDescription + " does not appear to be an IntelliJ theme file.\n" +
                    "Make sure it has the \"name\", \"author\" and \"dark\" attributes, and that \"dark\" is a string " +
                    "(for example \"dark\": \"true\").", e);
        }

        if (intelliJTheme.name == null || intelliJTheme.author == null)
            throw new ThemeLoadException(sourceDescription + " does not appear to be a valid theme file.\n" +
                    "If it is, make sure it has json attributes \"name\" and \"author\".");

        return applyBurpOverrides(new IntelliJTheme.ThemeLaf(intelliJTheme), isPreview);
    }

    /**
     * Wraps a FlatLaf IntelliJ theme in the Burp specific override layer.
     * Everything which ends up in Burp's UI goes through here.
     */
    private LookAndFeel applyBurpOverrides(IntelliJTheme.ThemeLaf theme, boolean isPreview) {
        return new CustomTheme(theme, isPreview);
    }

    // ---------------------------------------------------------------- applying

    /**
     * Applies a bundled theme and remembers it. Must be called on the EDT.
     */
    public void applyBundledTheme(UIManager.LookAndFeelInfo lookAndFeelInfo) throws ThemeLoadException {
        setLookAndFeel(createTheme(lookAndFeelInfo, false));

        selectedBuiltIn = lookAndFeelInfo;
        themeSource = ThemeSource.BUILTIN;
        customThemeName = null;
        BurpCustomizer.setPreference(PREF_BUILTIN_THEME, lookAndFeelInfo.getClassName());
        BurpCustomizer.setPreference(PREF_SOURCE, ThemeSource.BUILTIN.toString());
    }

    /**
     * Applies a user supplied theme file and remembers it. Must be called on the EDT.
     */
    public void applyCustomTheme(File themeJsonFile) throws ThemeLoadException {
        LookAndFeel lookAndFeel = createTheme(themeJsonFile, false);
        setLookAndFeel(lookAndFeel);

        selectedThemeFile = themeJsonFile;
        themeSource = ThemeSource.FILE;
        customThemeName = lookAndFeel.getName();
        BurpCustomizer.setPreference(PREF_THEME_FILE, themeJsonFile.getAbsolutePath());
        BurpCustomizer.setPreference(PREF_SOURCE, ThemeSource.FILE.toString());
    }

    private void setLookAndFeel(LookAndFeel lookAndFeel) throws ThemeLoadException {
        try {
            UIManager.setLookAndFeel(lookAndFeel);
        } catch (UnsupportedLookAndFeelException | RuntimeException e) {
            restoreOriginalTheme();
            throw new ThemeLoadException("FlatLaf rejected the theme \"" + lookAndFeel.getName() + "\".\n" +
                    "Burp's previous theme has been restored.", e);
        }
        refreshBurpUI();
    }

    /**
     * Makes the new look and feel visible across every Burp window without a restart.
     */
    public void refreshBurpUI() {
        FlatLaf.updateUI();
        for (Window window : Window.getWindows()) {
            if (!window.isDisplayable()) continue;
            window.invalidate();
            window.validate();
            window.repaint();
        }
        if (afterThemeApplied != null) afterThemeApplied.run();
    }

    /**
     * Puts Burp back on the look and feel it had before the extension was loaded.
     */
    public void restoreOriginalTheme() {
        if (originalBurpTheme == null) return;
        try {
            UIManager.setLookAndFeel(originalBurpTheme);
            FlatLaf.updateUI();
        } catch (UnsupportedLookAndFeelException | RuntimeException e) {
            BurpCustomizer.logError("Could not restore Burp's original theme.", e);
        }
    }

    // ------------------------------------------------------------ persistence

    /**
     * Reads the remembered theme from Burp's preferences. Does not apply anything, so a
     * broken saved theme can never stop the extension from initialising.
     */
    public void loadSavedSelection() {
        String savedSource = BurpCustomizer.getPreference(PREF_SOURCE);
        themeSource = ThemeSource.BUILTIN;
        if (savedSource != null && !savedSource.isEmpty()) {
            try {
                themeSource = ThemeSource.valueOf(savedSource);
            } catch (IllegalArgumentException e) {
                BurpCustomizer.logError("Ignoring unknown saved theme source \"" + savedSource + "\".", e);
            }
        }

        String savedTheme = BurpCustomizer.getPreference(PREF_BUILTIN_THEME);
        Optional<UIManager.LookAndFeelInfo> previousTheme = themes.stream()
                .filter(lookAndFeelInfo -> lookAndFeelInfo.getClassName().equalsIgnoreCase(savedTheme))
                .findFirst();
        previousTheme.ifPresent(lookAndFeelInfo -> selectedBuiltIn = lookAndFeelInfo);

        String savedThemeFile = BurpCustomizer.getPreference(PREF_THEME_FILE);
        if (savedThemeFile != null && !savedThemeFile.isEmpty()) {
            File file = new File(savedThemeFile);
            selectedThemeFile = file.isFile() ? file : null;
            if (selectedThemeFile == null)
                BurpCustomizer.logError("The saved custom theme file no longer exists: " + savedThemeFile, null);
        }
    }

    /**
     * Re-applies the remembered theme on startup. Falls back to the remembered bundled theme
     * (and finally to Burp's own theme) if a custom theme file has gone missing or is broken.
     * Must be called on the EDT.
     */
    public void restoreSavedTheme() {
        List<String> failures = new ArrayList<>();

        if (themeSource == ThemeSource.FILE) {
            if (selectedThemeFile == null) {
                failures.add("The custom theme file saved in your settings no longer exists.");
            } else {
                try {
                    applyCustomTheme(selectedThemeFile);
                    return;
                } catch (ThemeLoadException e) {
                    failures.add(e.getMessage());
                    BurpCustomizer.logError("Could not restore the saved custom theme.", e);
                }
            }
        }

        if (selectedBuiltIn != null) {
            try {
                applyBundledTheme(selectedBuiltIn);
                if (!failures.isEmpty())
                    BurpCustomizer.logOutput("Falling back to the theme \"" + selectedBuiltIn.getName() + "\".");
                return;
            } catch (ThemeLoadException e) {
                failures.add(e.getMessage());
                BurpCustomizer.logError("Could not restore the saved theme \"" + selectedBuiltIn.getName() + "\".", e);
            }
        }

        if (!failures.isEmpty()) {
            // Nothing could be restored - leave Burp on its own theme rather than failing to load.
            themeSource = ThemeSource.BUILTIN;
            BurpCustomizer.logError("Keeping Burp's own theme. " + String.join(" ", failures), null);
        }
    }
}
