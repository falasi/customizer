package com.coreyd97.burpcustomizer;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import com.formdev.flatlaf.extras.FlatInspector;
import com.formdev.flatlaf.extras.FlatUIDefaultsInspector;
import lombok.Getter;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;

public class BurpCustomizer implements BurpExtension {

    private LookAndFeel originalBurpTheme;
    @Getter
    private ThemeManager themeManager;
    private CustomizerPanel ui;
    public static MontoyaApi montoya;
    JMenuBar menuBar;
    JMenu menuItem;

    @Override
    public void initialize(MontoyaApi montoyaApi) {
        BurpCustomizer.montoya = montoyaApi;
        originalBurpTheme = UIManager.getLookAndFeel();

        themeManager = new ThemeManager(originalBurpTheme, this::patchPopupFactoryForFlatInspector);
        themeManager.loadSavedSelection();

        FlatUIDefaultsInspector.install("ctrl shift alt Y");
        FlatInspector.install("ctrl shift alt U");
        patchPopupFactoryForFlatInspector();

        this.ui = new CustomizerPanel(this);
        montoya.extension().registerUnloadingHandler(this::extensionUnloaded);

        SwingUtilities.invokeLater(() -> {
            themeManager.restoreSavedTheme();
            ui.themeChanged();

            montoya.userInterface().registerSuiteTab("Customizer", this.ui);
        });
    }

    //Since Burp explicitly disables HTML in components we need to manually re-enable HTML for the inspector tooltip.
    //Shouldn't reintroduce any vulnerabilities unless somehow a malicious value is used in a tooltip somewhere which is unlikely
    private void patchPopupFactoryForFlatInspector() {
        PopupFactory.setSharedInstance(new PopupFactory() {
            @Override
            public Popup getPopup(Component owner, Component contents, int x, int y) throws IllegalArgumentException {
                if (contents instanceof JToolTip) {
                    ((JToolTip) contents).putClientProperty("html.disable", false);
                }
                return super.getPopup(owner, contents, x, y);
            }
        });
    }

    public ArrayList<UIManager.LookAndFeelInfo> getThemes() {
        return themeManager.getThemes();
    }

    public UIManager.LookAndFeelInfo getSelectedBuiltIn() {
        return themeManager.getSelectedBuiltIn();
    }

    public File getSelectedThemeFile() {
        return themeManager.getSelectedThemeFile();
    }

    public ThemeManager.ThemeSource getThemeSource() {
        return themeManager.getThemeSource();
    }

    /**
     * Applies a bundled theme, reporting any problem to the user.
     */
    public void setTheme(UIManager.LookAndFeelInfo lookAndFeelInfo) {
        applyOnEventDispatchThread(() -> themeManager.applyBundledTheme(lookAndFeelInfo));
    }

    /**
     * Applies a user supplied IntelliJ/FlatLaf theme file, reporting any problem to the user.
     */
    public void setTheme(File themeJsonFile) {
        applyOnEventDispatchThread(() -> themeManager.applyCustomTheme(themeJsonFile));
    }

    private interface ThemeAction {
        void run() throws ThemeLoadException;
    }

    private void applyOnEventDispatchThread(ThemeAction action) {
        if (SwingUtilities.isEventDispatchThread()) {
            applyTheme(action);
        } else {
            SwingUtilities.invokeLater(() -> applyTheme(action));
        }
    }

    private void applyTheme(ThemeAction action) {
        try {
            action.run();
        } catch (ThemeLoadException ex) {
            logError("Could not load theme.", ex);
            JOptionPane.showMessageDialog(ui, ex.getMessage(), "Burp Customizer", JOptionPane.ERROR_MESSAGE);
        }
        if (ui != null) ui.themeChanged();
    }

    public LookAndFeel createThemeFromDefaults(UIManager.LookAndFeelInfo lookAndFeelInfo, boolean isPreview) throws ThemeLoadException {
        return themeManager.createTheme(lookAndFeelInfo, isPreview);
    }

    public LookAndFeel createThemeFromFile(File themeJsonFile) throws ThemeLoadException {
        return themeManager.createTheme(themeJsonFile, false);
    }

    // ----------------------------------------------------------------- logging & preferences
    // Everything below tolerates the extension not being loaded into Burp (previews, tests).

    public static void logError(String message, Throwable cause) {
        if (montoya == null) return;
        montoya.logging().logToError(message);
        if (cause != null) {
            StringWriter stackTrace = new StringWriter();
            cause.printStackTrace(new PrintWriter(stackTrace));
            montoya.logging().logToError(stackTrace.toString());
        }
    }

    public static void logOutput(String message) {
        if (montoya == null) return;
        montoya.logging().logToOutput(message);
    }

    public static String getPreference(String key) {
        return montoya != null ? montoya.persistence().preferences().getString(key) : null;
    }

    public static void setPreference(String key, String value) {
        if (montoya == null) return;
        montoya.persistence().preferences().setString(key, value);
    }

    public void extensionUnloaded() {
        BurpCustomizer.montoya = null;
        if (menuBar != null && menuItem != null) menuBar.remove(menuItem);
        SwingUtilities.invokeLater(() -> themeManager.restoreOriginalTheme());
    }
}
