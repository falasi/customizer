package com.coreyd97.burpcustomizer;

import com.formdev.flatlaf.IntelliJTheme;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * A FlatLaf IntelliJ theme with Burp's own UI properties layered on top.
 * <p>
 * Burp extends FlatLaf with a large number of custom UI defaults (the {@code Burp.*},
 * {@code ColourPalette.*} and {@code DesignSystemPalette.*} keys). Those defaults live in
 * property files next to Burp's own look and feel classes, so we load them before the
 * theme's own values and then map the theme's colours onto the Burp specific keys in
 * {@link #getAdditionalDefaults()}.
 */
public class CustomTheme extends IntelliJTheme.ThemeLaf {

    private static final String BURP_LAF_CLASS = "burp.theme.BurpLaf";
    private static final String BURP_DARK_LAF_CLASS = "burp.theme.BurpDarkLaf";
    private static final String BURP_LIGHT_LAF_CLASS = "burp.theme.BurpLightLaf";

    /**
     * A class loader known to see Burp's internal classes, registered by {@link ThemeManager}.
     * Burp is not necessarily visible from the system class loader, so we cannot rely on it.
     */
    private static volatile ClassLoader burpClassLoaderHint;
    private static volatile BurpLafClasses burpLafClasses;
    private static volatile boolean burpLafLookupFailureLogged;

    /**
     * Set for themes built for the (currently disabled) live preview panel rather than
     * for Burp itself.
     */
    private final boolean isPreview;

    public CustomTheme(IntelliJTheme.ThemeLaf base, boolean isPreview) {
        super(base.getTheme());
        this.isPreview = isPreview;
    }

    /**
     * Registers a class loader which is known to see Burp's classes - in practice the one
     * which loaded Burp's own look and feel.
     */
    public static void setBurpClassLoaderHint(ClassLoader classLoader) {
        if (classLoader == null || classLoader.equals(burpClassLoaderHint)) return;
        burpClassLoaderHint = classLoader;
        burpLafClasses = null;
        burpLafLookupFailureLogged = false;
    }

    /**
     * Burp's look and feel classes, or null when they cannot be found. Modern Burp releases
     * do not necessarily expose them to the system class loader, and previews may be built
     * before Burp's UI exists at all, so a miss must not be fatal.
     */
    private static BurpLafClasses getBurpLafClasses() {
        BurpLafClasses cached = burpLafClasses;
        if (cached != null) return cached;

        for (ClassLoader classLoader : candidateClassLoaders()) {
            if (classLoader == null) continue;
            try {
                BurpLafClasses found = new BurpLafClasses(
                        classLoader.loadClass(BURP_LAF_CLASS),
                        classLoader.loadClass(BURP_DARK_LAF_CLASS),
                        classLoader.loadClass(BURP_LIGHT_LAF_CLASS));
                burpLafClasses = found;
                return found;
            } catch (ClassNotFoundException | LinkageError ignored) {
                // Try the next candidate.
            }
        }

        if (!burpLafLookupFailureLogged) {
            burpLafLookupFailureLogged = true;
            BurpCustomizer.logError("Could not find Burp's theme classes (" + BURP_LAF_CLASS + "). " +
                    "The theme will be applied without Burp's own UI defaults, so some Burp specific " +
                    "components may not match the theme.", null);
        }
        return null;
    }

    private static List<ClassLoader> candidateClassLoaders() {
        LookAndFeel currentLookAndFeel = UIManager.getLookAndFeel();
        return Arrays.asList(
                burpClassLoaderHint,
                currentLookAndFeel != null ? currentLookAndFeel.getClass().getClassLoader() : null,
                CustomTheme.class.getClassLoader(),
                Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader());
    }

    @Override
    protected ArrayList<Class<?>> getLafClassesForDefaultsLoading() {
        ArrayList<Class<?>> lafClasses = super.getLafClassesForDefaultsLoading();
        BurpLafClasses burpClasses = getBurpLafClasses();
        if (burpClasses == null) return lafClasses;

        // Burp's defaults are loaded after FlatLaf's, so the theme's own values (applied
        // afterwards from the theme json) still win.
        lafClasses.add(burpClasses.base);
        lafClasses.add(isDark() ? burpClasses.dark : burpClasses.light);
        return lafClasses;
    }

    private record BurpLafClasses(Class<?> base, Class<?> dark, Class<?> light) {
    }

    @Override
    protected Properties getAdditionalDefaults() {
        //Add Additional Overrides Here
        //This is actually run BEFORE the theme is loaded, so we need to use lazy loading to pull values from the theme.
        Properties defaults = new Properties();

        //Force the IntellijTheme class into loading the json containing defaults so we can use its values
//        defaults.put("Test", "#00FF00");
        //Color Palettes. 1-8, dark needs lightening, light needs darkening
        defaults.put("@accent", "lazy(Button.focusedBorderColor)");
        defaults.put("ColourPalette.mono0", "lazy(Label.background)");
        defaults.put("[dark]ColourPalette.mono1", "lighten(ColourPalette.mono0,5%,lazy)");
        defaults.put("[dark]ColourPalette.mono2", "lighten(ColourPalette.mono0,10%,lazy)");
        defaults.put("[dark]ColourPalette.mono3", "lighten(ColourPalette.mono0,15%,lazy)");
        defaults.put("[dark]ColourPalette.mono4", "lighten(ColourPalette.mono0,20%,lazy)");
        defaults.put("[dark]ColourPalette.mono5", "lighten(ColourPalette.mono0,25%,lazy)");
        defaults.put("[dark]ColourPalette.mono6", "lighten(ColourPalette.mono0,30%,lazy)");
        defaults.put("[dark]ColourPalette.mono7", "lighten(ColourPalette.mono0,35%,lazy)");
        defaults.put("[dark]ColourPalette.mono8", "lighten(ColourPalette.mono0,40%,lazy)");
        defaults.put("[light]ColourPalette.mono1", "darken(ColourPalette.mono0,5%,lazy)");
        defaults.put("[light]ColourPalette.mono2", "darken(ColourPalette.mono0,10%,lazy)");
        defaults.put("[light]ColourPalette.mono3", "darken(ColourPalette.mono0,15%,lazy)");
        defaults.put("[light]ColourPalette.mono4", "darken(ColourPalette.mono0,20%,lazy)");
        defaults.put("[light]ColourPalette.mono5", "darken(ColourPalette.mono0,25%,lazy)");
        defaults.put("[light]ColourPalette.mono6", "darken(ColourPalette.mono0,30%,lazy)");
        defaults.put("[light]ColourPalette.mono7", "darken(ColourPalette.mono0,35%,lazy)");
        defaults.put("[light]ColourPalette.mono8", "darken(ColourPalette.mono0,40%,lazy)");


        defaults.put("BurpPalette.mono0", "lazy(Label.background)");
        defaults.put("[dark]BurpPalette.mono1", "lighten(BurpPalette.mono0,5%,lazy)");
        defaults.put("[dark]BurpPalette.mono2", "lighten(BurpPalette.mono0,10%,lazy)");
        defaults.put("[dark]BurpPalette.mono3", "lighten(BurpPalette.mono0,15%,lazy)");
        defaults.put("[dark]BurpPalette.mono4", "lighten(BurpPalette.mono0,20%,lazy)");
        defaults.put("[dark]BurpPalette.mono5", "lighten(BurpPalette.mono0,25%,lazy)");
        defaults.put("[dark]BurpPalette.mono6", "lighten(BurpPalette.mono0,30%,lazy)");
        defaults.put("[dark]BurpPalette.mono7", "lighten(BurpPalette.mono0,35%,lazy)");
        defaults.put("[dark]BurpPalette.mono8", "lighten(BurpPalette.mono0,40%,lazy)");
        defaults.put("[dark]BurpPalette.mono9", "lighten(BurpPalette.mono0,45%,lazy)");
        defaults.put("[dark]BurpPalette.mono10", "lighten(BurpPalette.mono0,50%,lazy)");
        defaults.put("[dark]BurpPalette.mono11", "lighten(BurpPalette.mono0,55%,lazy)");
        defaults.put("[light]BurpPalette.mono1", "darken(BurpPalette.mono0,5%,lazy)");
        defaults.put("[light]BurpPalette.mono2", "darken(BurpPalette.mono0,10%,lazy)");
        defaults.put("[light]BurpPalette.mono3", "darken(BurpPalette.mono0,15%,lazy)");
        defaults.put("[light]BurpPalette.mono4", "darken(BurpPalette.mono0,20%,lazy)");
        defaults.put("[light]BurpPalette.mono5", "darken(BurpPalette.mono0,25%,lazy)");
        defaults.put("[light]BurpPalette.mono6", "darken(BurpPalette.mono0,30%,lazy)");
        defaults.put("[light]BurpPalette.mono7", "darken(BurpPalette.mono0,35%,lazy)");
        defaults.put("[light]BurpPalette.mono8", "darken(BurpPalette.mono0,40%,lazy)");
        defaults.put("[light]BurpPalette.mono9", "darken(BurpPalette.mono0,45%,lazy)");
        defaults.put("[light]BurpPalette.mono10", "darken(BurpPalette.mono0,50%,lazy)");
        defaults.put("[light]BurpPalette.mono11", "darken(BurpPalette.mono0,55%,lazy)");
//
        defaults.put("DesignSystemPalette.grey0", "lazy(Label.background)");
        defaults.put("[dark]DesignSystemPalette.grey1", "lighten(DesignSystemPalette.grey0,5%,lazy)");
        defaults.put("[dark]DesignSystemPalette.grey2", "lighten(DesignSystemPalette.grey0,10%,lazy)");
        defaults.put("[dark]DesignSystemPalette.grey3", "lighten(DesignSystemPalette.grey0,15%,lazy)");
        defaults.put("[dark]DesignSystemPalette.grey4", "lighten(DesignSystemPalette.grey0,20%,lazy)");
        defaults.put("[dark]DesignSystemPalette.grey5", "lighten(DesignSystemPalette.grey0,25%,lazy)");
        defaults.put("[dark]DesignSystemPalette.grey6", "lighten(DesignSystemPalette.grey0,30%,lazy)");
        defaults.put("[dark]DesignSystemPalette.grey7", "lighten(DesignSystemPalette.grey0,35%,lazy)");
        defaults.put("[dark]DesignSystemPalette.grey8", "lighten(DesignSystemPalette.grey0,40%,lazy)");
        defaults.put("[dark]DesignSystemPalette.grey9", "lighten(DesignSystemPalette.grey0,45%,lazy)");
        defaults.put("[dark]DesignSystemPalette.grey10", "lighten(DesignSystemPalette.grey0,50%,lazy)");
        defaults.put("[dark]DesignSystemPalette.grey11", "lighten(DesignSystemPalette.grey0,55%,lazy)");
        defaults.put("[dark]DesignSystemPalette.grey12", "lighten(DesignSystemPalette.grey0,60%,lazy)");
        defaults.put("[light]DesignSystemPalette.grey1", "darken(DesignSystemPalette.grey0,5%,lazy)");
        defaults.put("[light]DesignSystemPalette.grey2", "darken(DesignSystemPalette.grey0,10%,lazy)");
        defaults.put("[light]DesignSystemPalette.grey3", "darken(DesignSystemPalette.grey0,15%,lazy)");
        defaults.put("[light]DesignSystemPalette.grey4", "darken(DesignSystemPalette.grey0,20%,lazy)");
        defaults.put("[light]DesignSystemPalette.grey5", "darken(DesignSystemPalette.grey0,25%,lazy)");
        defaults.put("[light]DesignSystemPalette.grey6", "darken(DesignSystemPalette.grey0,30%,lazy)");
        defaults.put("[light]DesignSystemPalette.grey7", "darken(DesignSystemPalette.grey0,35%,lazy)");
        defaults.put("[light]DesignSystemPalette.grey8", "darken(DesignSystemPalette.grey0,40%,lazy)");
        defaults.put("[light]DesignSystemPalette.grey9", "darken(DesignSystemPalette.grey0,45%,lazy)");
        defaults.put("[light]DesignSystemPalette.grey10", "darken(DesignSystemPalette.grey0,50%,lazy)");
        defaults.put("[light]DesignSystemPalette.grey11", "darken(DesignSystemPalette.grey0,55%,lazy)");
        defaults.put("[light]DesignSystemPalette.grey12", "darken(DesignSystemPalette.grey0,60%,lazy)");

        defaults.put("ColourPalette.background5", "lazy(BurpPalette.mono0)");
        defaults.put("BurpPalette.blue1", "lazy(BurpPalette.mono2)");
        defaults.put("BurpPalette.blue4", "lazy(BurpPalette.mono4)");
        defaults.put("ColourPalette.blue1", "lazy(BurpPalette.mono2)");
        defaults.put("Burp.dualEmptyPanelLeftBackground", "lazy(BurpPalette.mono2)");
        defaults.put("Burp.collapsibleSidebarSelectedLabelBackground", "@accent");
        defaults.put("Burp.burpOrange", "@accent");
        defaults.put("Burp.primaryButtonBackground", "@accent");
        defaults.put("Burp.tabFlashColour", "@accent");
        defaults.put("Burp.tableFilterBarBorder", "@accent");
        defaults.put("Burp.searchBarBorder", "@accent");

        defaults.put("[dark]Burp.backgrounder", "lighten(Label.background,2%,lazy)");
        defaults.put("[light]Burp.backgrounder", "darken(Label.background,2%,lazy)");
//        defaults.put("DesignSystemPalette.grey2", "$Burp.backgrounder");
        defaults.put("@toolBackground", "$Burp.backgrounder");
        defaults.put("Burp.taskListEntrySelectedHighlight", "lazy(Component.accentColor)");

        defaults.put("Burp.taskListEntry", "lazy(ColourPalette.mono2)");
        defaults.put("Burp.textEditorBackground", "lazy(EditorPane.background)");
        defaults.put("Burp.textEditorCurrentLineBackground", "lazy(EditorPane.background)");
//        defaults.put("Checkbox.icon.focusedSelectedBackground", "@accent");
//        defaults.put("Checkbox.icon.hoverSelectedBackground", "@accent");

        return defaults;
    }
    
}
