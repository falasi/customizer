package com.coreyd97.burpcustomizer;

import com.formdev.flatlaf.FlatDefaultsAddon;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.IntelliJTheme;
import com.formdev.flatlaf.util.SystemInfo;

import javax.swing.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /**
     * The names Burp's look and feel classes have historically had. Modern Burp releases are
     * discovered from the running look and feel instead, so these are only a fallback for
     * versions where the extension is initialised without one.
     */
    private static final String[] LEGACY_BURP_LAF_CLASSES = {
            "burp.theme.BurpLaf", "burp.theme.BurpDarkLaf", "burp.theme.BurpLightLaf",
    };

    /**
     * Packages Burp's own classes live in. Used to pick Burp's classes out of the running
     * look and feel's hierarchy without depending on any particular class name.
     */
    private static final String[] BURP_PACKAGE_PREFIXES = {"burp.", "com.portswigger."};

    /**
     * How FlatLaf names the reference it could not resolve while loading properties.
     */
    private static final Pattern MISSING_REFERENCE_MESSAGE =
            Pattern.compile("variable or property '([^']+)' not found");

    /**
     * A cap on how many times loading Burp's defaults is retried, so a look and feel which
     * keeps producing new failures cannot spin.
     */
    private static final int MAX_LEARNED_REFERENCES = 64;

    /**
     * A {@code $property} or {@code @variable} reference inside a properties value. FlatLaf
     * only resolves one at the start of a value or of a function argument, so a '$' anywhere
     * else - notably the one in an inner class name such as
     * {@code com.formdev.flatlaf.ui.FlatRootPaneUI$FlatWindowBorder} - is part of the value.
     * {@code $?name} is optional, but that is not the same as harmless: FlatLaf resolves an
     * unresolved optional reference to null, and {@code UIDefaults.put(key, null)} deletes the
     * key. A theme which sets that key only through its {@code "*"} wildcard cannot put it
     * back, because the wildcard only replaces keys which already exist.
     */
    private static final Pattern PROPERTY_REFERENCE = Pattern.compile("(?:^|[(,])\\s*([$@])(\\??)([A-Za-z0-9_.\\[\\]-]+)");

    /**
     * Burp's look and feel as it was before the extension touched anything, captured during
     * initialisation. It is the only reliable handle on both the classes Burp's UI defaults
     * live in and the class loader which can see them.
     */
    private static volatile LookAndFeel burpLookAndFeel;
    private static volatile BurpDefaults burpDefaults = BurpDefaults.none();
    private static volatile BurpLafClasses burpLafClasses;
    private static volatile boolean burpLafDiscoveryDone;

    /**
     * Registers Burp's own look and feel and captures the UI defaults it has installed.
     * Must be called while Burp's look and feel is still installed, i.e. during extension
     * initialisation and before any theme is applied.
     */
    public static synchronized void setBurpLookAndFeel(LookAndFeel lookAndFeel) {
        burpLookAndFeel = lookAndFeel;
        burpLafClasses = null;
        burpLafDiscoveryDone = false;
        burpDefaults = BurpDefaults.captureFromInstalledLookAndFeel();
    }

    /**
     * The classes Burp keeps its UI defaults in, or null if they cannot be found. A miss is
     * never fatal: the defaults captured before theming are re-expressed in the theme's own
     * palette instead.
     */
    private static synchronized BurpLafClasses getBurpLafClasses() {
        if (burpLafDiscoveryDone) return burpLafClasses;
        burpLafDiscoveryDone = true;
        burpLafClasses = discoverBurpLafClasses();
        logDiscovery();
        return burpLafClasses;
    }

    private static BurpLafClasses discoverBurpLafClasses() {
        //Burp's own look and feel is the authority on both where its defaults live and which
        //class loader can see them, whatever the classes happen to be called in this release.
        LookAndFeel lookAndFeel = burpLookAndFeel;
        if (lookAndFeel != null) {
            List<Class<?>> hierarchy = burpClassesIn(lookAndFeel.getClass());
            if (!hierarchy.isEmpty()) {
                Class<?> mostSpecific = hierarchy.get(hierarchy.size() - 1);
                Class<?> opposite = oppositePolarityClass(mostSpecific);
                boolean specificIsDark = lookAndFeel instanceof FlatLaf flatLaf
                        ? flatLaf.isDark()
                        : mostSpecific.getSimpleName().toLowerCase(Locale.ROOT).contains("dark");

                return new BurpLafClasses(
                        hierarchy.subList(0, hierarchy.size() - 1),
                        specificIsDark ? mostSpecific : opposite,
                        specificIsDark ? opposite : mostSpecific,
                        "the running look and feel " + lookAndFeel.getClass().getName());
            }
        }

        //Older Burp releases, or an extension loaded without a look and feel to inspect.
        for (ClassLoader classLoader : candidateClassLoaders()) {
            if (classLoader == null) continue;
            try {
                return new BurpLafClasses(
                        List.of(classLoader.loadClass(LEGACY_BURP_LAF_CLASSES[0])),
                        classLoader.loadClass(LEGACY_BURP_LAF_CLASSES[1]),
                        classLoader.loadClass(LEGACY_BURP_LAF_CLASSES[2]),
                        "the class names used by older Burp releases");
            } catch (ClassNotFoundException | LinkageError ignored) {
                //Try the next candidate.
            }
        }

        return null;
    }

    /**
     * Burp's own classes in the hierarchy of the given class, least specific first.
     */
    private static List<Class<?>> burpClassesIn(Class<?> lookAndFeelClass) {
        ArrayList<Class<?>> burpClasses = new ArrayList<>();
        for (Class<?> current = lookAndFeelClass; current != null && current != Object.class; current = current.getSuperclass()) {
            if (isBurpClass(current)) burpClasses.add(0, current);
        }
        return burpClasses;
    }

    private static boolean isBurpClass(Class<?> candidate) {
        for (String prefix : BURP_PACKAGE_PREFIXES) {
            if (candidate.getName().startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * The dark counterpart of a light look and feel class, or vice versa, if Burp ships one
     * under the matching name. Burp's own look and feel only tells us about the polarity Burp
     * is currently using, and a theme may be the other one.
     */
    private static Class<?> oppositePolarityClass(Class<?> lookAndFeelClass) {
        String name = lookAndFeelClass.getName();
        String opposite = name.contains("Dark") ? name.replace("Dark", "Light")
                : name.contains("Light") ? name.replace("Light", "Dark")
                : null;
        if (opposite == null) return null;

        try {
            return Class.forName(opposite, false, lookAndFeelClass.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    private static List<ClassLoader> candidateClassLoaders() {
        LookAndFeel burpLaf = burpLookAndFeel;
        LookAndFeel currentLookAndFeel = UIManager.getLookAndFeel();
        return Arrays.asList(
                burpLaf != null ? burpLaf.getClass().getClassLoader() : null,
                currentLookAndFeel != null ? currentLookAndFeel.getClass().getClassLoader() : null,
                CustomTheme.class.getClassLoader(),
                Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader());
    }

    /**
     * Reports what was found, so a Burp release which moves its look and feel can be
     * diagnosed from the extension's log rather than guessed at.
     */
    private static void logDiscovery() {
        String report = describeBurpLookAndFeel();
        if (burpLafClasses == null && burpDefaults.size() == 0) BurpCustomizer.logError(report, null);
        else BurpCustomizer.logOutput(report);
    }

    /**
     * What the extension found when it inspected Burp: the look and feel that was running,
     * where it came from, and which of its classes and defaults are being used.
     */
    static String describeBurpLookAndFeel() {
        LookAndFeel lookAndFeel = burpLookAndFeel;
        StringBuilder report = new StringBuilder("Burp Customizer: inspecting Burp's look and feel\n");
        if (lookAndFeel == null) {
            report.append("  active look and feel: none captured (the extension was initialised without one)\n");
        } else {
            Class<?> lafClass = lookAndFeel.getClass();
            report.append("  active look and feel: ").append(lafClass.getName())
                    .append(" (\"").append(safeName(lookAndFeel)).append("\")\n");
            report.append("  class loader: ").append(describe(lafClass.getClassLoader())).append('\n');
            report.append("  superclasses: ").append(superclassNames(lafClass)).append('\n');
        }

        BurpLafClasses found = burpLafClasses;
        if (found != null) {
            report.append("  Burp defaults classes: ").append(found.describe())
                    .append(" (found via ").append(found.source()).append(")\n");
        } else {
            report.append("  Burp defaults classes: not found\n");
        }
        report.append("  Burp specific defaults captured before theming: ").append(burpDefaults.size());

        if (found == null && burpDefaults.size() == 0)
            report.append("\n  Burp specific components may not match the theme: neither Burp's defaults "
                    + "classes nor its installed defaults could be read.");
        else if (found == null)
            report.append("\n  Burp specific UI defaults will be derived from each theme's own palette.");

        return report.toString();
    }

    private static String safeName(LookAndFeel lookAndFeel) {
        try {
            return String.valueOf(lookAndFeel.getName());
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private static String superclassNames(Class<?> type) {
        StringBuilder names = new StringBuilder();
        for (Class<?> current = type.getSuperclass(); current != null; current = current.getSuperclass()) {
            if (names.length() > 0) names.append(" <- ");
            names.append(current.getName());
        }
        return names.length() > 0 ? names.toString() : "(none)";
    }

    private static String describe(ClassLoader classLoader) {
        if (classLoader == null) return "bootstrap class loader";
        String name = classLoader.getName();
        return classLoader.getClass().getName()
                + (name != null ? " [" + name + "]" : "")
                + "@" + Integer.toHexString(System.identityHashCode(classLoader));
    }

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

    /**
     * References Burp makes which nothing defines, learned from FlatLaf as it rejects them.
     * The scanner catches these from the properties files it can read; anything contributed
     * from somewhere else - an addon, an application defaults source - only shows up when
     * FlatLaf refuses it, so it is added here and the load is retried.
     */
    private final Set<String> learnedMissingReferences = new LinkedHashSet<>();

    public CustomTheme(IntelliJTheme.ThemeLaf base, boolean isPreview) {
        super(base.getTheme());
        this.isPreview = isPreview;
    }

    @Override
    protected ArrayList<Class<?>> getLafClassesForDefaultsLoading() {
        ArrayList<Class<?>> lafClasses = super.getLafClassesForDefaultsLoading();
        if (burpDefaultsDisabled) return lafClasses;

        BurpLafClasses burpClasses = getBurpLafClasses();
        if (burpClasses == null) return lafClasses;

        //Burp's defaults are loaded after FlatLaf's, so the theme's own values (applied
        //afterwards from the theme json) still win. Only the variant matching this theme's
        //polarity is loaded - Burp's other one would bring the wrong colours, and anything it
        //would have defined is derived from the theme instead.
        for (Class<?> shared : burpClasses.shared()) {
            if (!lafClasses.contains(shared)) lafClasses.add(shared);
        }
        Class<?> polarity = isDark() ? burpClasses.dark() : burpClasses.light();
        if (polarity != null && !lafClasses.contains(polarity)) lafClasses.add(polarity);
        return lafClasses;
    }

    private record BurpLafClasses(List<Class<?>> shared, Class<?> dark, Class<?> light, String source) {

        String describe() {
            List<String> names = new ArrayList<>();
            for (Class<?> shared : shared()) names.add(shared.getName());
            if (dark != null) names.add(dark.getName() + " (dark)");
            if (light != null) names.add(light.getName() + " (light)");
            return names.isEmpty() ? "(none)" : String.join(", ", names);
        }
    }

    /**
     * Burp's defaults must never be able to take the whole look and feel down with them.
     * A reference Burp makes which this theme does not provide is learned and the load is
     * retried, so Burp's own defaults keep being used; only if that stops making progress are
     * they dropped, and even then the values Burp had are resolved against the theme.
     */
    @Override
    public UIDefaults getDefaults() {
        while (true) {
            try {
                return withBurpDefaults(super.getDefaults());
            } catch (RuntimeException e) {
                if (learnMissingReference(e)) continue;
                if (burpDefaultsDisabled) throw e;

                burpDefaultsDisabled = true;
                BurpCustomizer.logError("Burp's own UI defaults could not be applied to the theme \"" + getName()
                        + "\" (" + e.getMessage() + "). Applying the theme without them - the values Burp had before "
                        + "theming will be resolved against the theme instead.", e);
                return withBurpDefaults(super.getDefaults());
            }
        }
    }

    /**
     * Gives every Burp key which did not survive theming a value from the theme, and reports
     * anything still carrying Burp's own branding so it can be tracked down.
     */
    private UIDefaults withBurpDefaults(UIDefaults defaults) {
        int resolved = burpDefaults.applyTo(defaults);
        if (resolved > 0 && !isPreview)
            BurpCustomizer.logOutput("Burp Customizer: resolved " + resolved + " Burp specific UI default(s) against "
                    + "the theme \"" + getName() + "\".");

        if (!isPreview) {
            List<String> branded = burpDefaults.brandColouredKeysAfterTheming(defaults);
            if (!branded.isEmpty())
                BurpCustomizer.logOutput("Burp Customizer: Burp UI defaults still using Burp's own branding colour "
                        + "under \"" + getName() + "\" (key -> Burp value -> themed value):\n  "
                        + String.join("\n  ", branded));
        }

        return defaults;
    }

    /**
     * Burp keys which came through theming still wearing Burp's own branding colour, as
     * {@code key -> Burp value -> themed value}. Useful for tracking down a component which
     * is still painted orange in a themed Burp.
     */
    static List<String> brandColouredBurpKeys(UIDefaults themeDefaults) {
        return burpDefaults.brandColouredKeysAfterTheming(themeDefaults);
    }

    /**
     * Takes the name of an unresolvable reference out of FlatLaf's complaint, so it can be
     * defined and the load retried.
     *
     * @return true if something new was learned and retrying is worthwhile
     */
    private boolean learnMissingReference(RuntimeException e) {
        if (burpDefaultsDisabled || learnedMissingReferences.size() >= MAX_LEARNED_REFERENCES) return false;

        String message = e.getMessage();
        if (message == null) return false;
        Matcher matcher = MISSING_REFERENCE_MESSAGE.matcher(message);
        if (!matcher.find()) return false;

        return learnedMissingReferences.add(matcher.group(1));
    }

    @Override
    protected Properties getAdditionalDefaults() {
        Properties defaults = new Properties();
        Properties burpOverrides = getBurpOverrides();
        //Placeholders first: anything Burp genuinely defines, and every override below,
        //has to win over them.
        defaults.putAll(missingReferenceFallbacks(burpOverrides));
        for (String reference : learnedMissingReferences)
            defaults.put(reference, BurpDefaults.unresolvedPlaceholder());
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

        //Addon values reference Burp's colours too, and are just as likely to be missing them.
        Properties referencing = new Properties();
        referencing.putAll(properties);
        referencing.putAll(definedByAddons);

        Properties fallbacks = new Properties();
        for (Map.Entry<Object, Object> property : referencing.entrySet()) {
            //A reference inside a key which does not apply here is never resolved either.
            if (effectiveKey((String) property.getKey()) == null) continue;

            Matcher matcher = PROPERTY_REFERENCE.matcher((String) property.getValue());
            while (matcher.find()) {
                //A variable reference keeps its '@', a property reference drops its '$'.
                String key = matcher.group(1).equals("@") ? "@" + matcher.group(3) : matcher.group(3);
                //Optional references are defined too: left alone they resolve to null, which
                //deletes the key the property defines rather than leaving it as it was.
                if (defined.contains(key) || fallbacks.containsKey(key)) continue;
                fallbacks.put(key, BurpDefaults.unresolvedPlaceholder());
            }
        }

        if (!fallbacks.isEmpty())
            BurpCustomizer.logOutput("Burp Customizer: Burp references " + fallbacks.size() + " UI propertie(s) which "
                    + "this theme does not provide; the keys using them are resolved against the theme afterwards: "
                    + fallbacks.keySet());

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
