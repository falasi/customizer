package com.coreyd97.burpcustomizer;

import com.formdev.flatlaf.FlatDefaultsAddon;
import com.formdev.flatlaf.IntelliJTheme;
import com.formdev.flatlaf.util.SystemInfo;

import javax.swing.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A FlatLaf IntelliJ theme with Burp's own UI properties layered on top.
 * <p>
 * Burp extends FlatLaf with a large number of custom UI defaults (the {@code Burp.*},
 * {@code ColourPalette.*} and {@code DesignSystemPalette.*} keys). Those defaults live in
 * property files next to Burp's own look and feel classes, so we load them before the
 * theme's own values and then map the theme's colours onto the Burp specific keys in
 * {@link #getAdditionalDefaults()}.
 * <p>
 * Burp's look and feel is itself built from an IntelliJ theme json, so its property files
 * reference named colours from that json - {@code $ColorPalette.colorSeparator} and friends.
 * Those colours only exist while Burp's own theme is providing the defaults; under any other
 * theme FlatLaf cannot resolve the reference and throws {@link IllegalArgumentException} out
 * of {@code getDefaults()}, which fails the whole look and feel. Two things stop that here:
 * {@link #missingReferenceFallbacks} defines whatever Burp references and nothing provides,
 * and {@link #getDefaults()} falls back to loading the theme without Burp's defaults if they
 * turn out to be unusable anyway.
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
     * A {@code $property} or {@code @variable} reference inside a properties value.
     * {@code $?name} is optional - FlatLaf resolves it to null instead of failing.
     */
    private static final Pattern PROPERTY_REFERENCE = Pattern.compile("([$@])(\\??)([A-Za-z0-9_.\\[\\]-]+)");

    /**
     * Set for themes built for the (currently disabled) live preview panel rather than
     * for Burp itself.
     */
    private final boolean isPreview;

    /**
     * Set once Burp's defaults have proven unusable for this theme, so the retry in
     * {@link #getDefaults()} loads the theme without them.
     */
    private boolean burpDefaultsDisabled;

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
        if (burpDefaultsDisabled) return lafClasses;
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

    /**
     * Burp's defaults must never be able to take the whole look and feel down with them.
     * If they cannot be loaded - an unresolvable reference, a property FlatLaf refuses -
     * the theme is loaded again without them and the failure is logged.
     */
    @Override
    public UIDefaults getDefaults() {
        try {
            return super.getDefaults();
        } catch (RuntimeException e) {
            if (burpDefaultsDisabled) throw e;
            burpDefaultsDisabled = true;
            BurpCustomizer.logError("Burp's own UI defaults could not be applied to the theme \"" + getName()
                    + "\" (" + e.getMessage() + "). Applying the theme without them - Burp specific components "
                    + "may not match the theme.", e);
            return super.getDefaults();
        }
    }

    @Override
    protected Properties getAdditionalDefaults() {
        Properties defaults = new Properties();
        Properties burpOverrides = getBurpOverrides();
        //Fallbacks first: anything Burp genuinely defines, and every override below,
        //has to win over them.
        defaults.putAll(missingReferenceFallbacks(burpOverrides));
        defaults.putAll(burpOverrides);
        return defaults;
    }

    /**
     * Defines every {@code $property} / {@code @variable} which the properties files about to
     * be loaded reference but nothing defines, so FlatLaf can resolve them.
     * <p>
     * This is deliberately generic rather than a list of the keys one Burp release happens to
     * use: the same class of breakage appears whenever PortSwigger references another colour
     * from Burp's own theme json.
     *
     * @param ownDefaults the overrides this class is about to add, which also count as defined
     */
    private Properties missingReferenceFallbacks(Properties ownDefaults) {
        List<Class<?>> lafClasses = getLafClassesForDefaultsLoading();
        Properties properties = new Properties();
        for (Class<?> lafClass : lafClasses)
            loadProperties(lafClass, properties);

        //Addons contribute defaults too, and are loaded before ours - anything they define
        //must not be shadowed by a fallback.
        Properties definedByAddons = new Properties();
        for (FlatDefaultsAddon addon : ServiceLoader.load(FlatDefaultsAddon.class)) {
            for (Class<?> lafClass : lafClasses) {
                try (InputStream in = addon.getDefaults(lafClass)) {
                    if (in != null) definedByAddons.load(in);
                } catch (IOException | IllegalArgumentException e) {
                    BurpCustomizer.logError("Could not read UI defaults from addon " + addon.getClass().getName(), e);
                }
            }
        }

        Set<String> defined = new HashSet<>();
        collectDefinedKeys(properties, defined);
        collectDefinedKeys(definedByAddons, defined);
        collectDefinedKeys(ownDefaults, defined);

        Properties fallbacks = new Properties();
        for (Object value : properties.values()) {
            Matcher matcher = PROPERTY_REFERENCE.matcher((String) value);
            while (matcher.find()) {
                boolean optional = !matcher.group(2).isEmpty();
                //A variable reference keeps its '@', a property reference drops its '$'.
                String key = matcher.group(1).equals("@") ? "@" + matcher.group(3) : matcher.group(3);
                if (optional || defined.contains(key) || fallbacks.containsKey(key)) continue;
                fallbacks.put(key, fallbackFor(key));
            }
        }

        if (!fallbacks.isEmpty())
            BurpCustomizer.logOutput("Burp Customizer: defined " + fallbacks.size() + " fallback value(s) for UI "
                    + "properties which Burp references but this theme does not provide: " + fallbacks.keySet());

        return fallbacks;
    }

    /**
     * Loads the properties file FlatLaf would load for this class, if there is one.
     * Uses the class's own class loader, which for Burp's classes is Burp's.
     */
    private static void loadProperties(Class<?> lafClass, Properties into) {
        String resource = '/' + lafClass.getName().replace('.', '/') + ".properties";
        try (InputStream in = lafClass.getResourceAsStream(resource)) {
            if (in != null) into.load(in);
        } catch (IOException | IllegalArgumentException e) {
            BurpCustomizer.logError("Could not read UI defaults from " + resource, e);
        }
    }

    private void collectDefinedKeys(Properties properties, Set<String> into) {
        for (String key : properties.stringPropertyNames()) {
            String defined = effectiveKey(key);
            if (defined != null) into.add(defined);
        }
    }

    /**
     * The key a property actually defines, or null if it does not apply here.
     * Keys may carry the condition they apply under, e.g. {@code [dark]Some.key} or
     * {@code [win]Some.key}; FlatLaf drops the ones which do not match before resolving
     * references, so a {@code [dark]} definition is no help to a light theme.
     */
    private String effectiveKey(String key) {
        String platformPrefix = SystemInfo.isWindows ? "[win]" : SystemInfo.isMacOS ? "[mac]" : "[linux]";
        while (key.startsWith("[")) {
            int end = key.indexOf(']');
            if (end < 0) return key;
            String prefix = key.substring(0, end + 1);
            switch (prefix) {
                case "[dark]" -> { if (!isDark()) return null; }
                case "[light]" -> { if (isDark()) return null; }
                case "[win]", "[mac]", "[linux]" -> { if (!prefix.equals(platformPrefix)) return null; }
                //Any other bracketed prefix (e.g. "[style]") is part of the key itself.
                default -> { return key; }
            }
            key = key.substring(end + 1);
        }
        return key;
    }

    /**
     * A stand-in value for a property Burp references but this theme does not define, picked
     * from the FlatLaf variables which every theme's base (FlatDarkLaf/FlatLightLaf) defines,
     * so it at least follows the theme's polarity. The Burp keys which actually matter are
     * re-mapped onto the theme's own colours by {@link #getBurpOverrides()} afterwards.
     */
    private static String fallbackFor(String key) {
        String name = key.toLowerCase(Locale.ROOT);
        if (containsAny(name, "separator", "border", "divider", "grid", "outline", "line"))
            return "@disabledForeground";
        if (containsAny(name, "disabled", "inactive")) return "@disabledForeground";
        if (containsAny(name, "selection", "selected")) return "@selectionBackground";
        if (containsAny(name, "focus", "accent", "underline", "highlight", "hover", "pressed", "link"))
            return "@accentFocusColor";
        if (containsAny(name, "foreground", "text", "caret", "icon", "arrow")) return "@foreground";
        return "@background";
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) return true;
        }
        return false;
    }

    private Properties getBurpOverrides() {
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
