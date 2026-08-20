package burp.theme;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

import java.util.ArrayList;
import java.util.List;

/**
 * Stand-in for the look and feel older Burp releases install, under the class names the
 * extension used to hard code.
 * <p>
 * It is a real FlatLaf so tests can install it and then inspect what the extension makes of
 * it, and its {@code .properties} resources have the same shape as Burp's: they reference
 * named colours from Burp's own theme json, which no other theme defines.
 */
public class BurpLaf extends FlatLaf {

    @Override
    public String getName() {
        return "Burp";
    }

    @Override
    public String getDescription() {
        return "Burp stand-in";
    }

    @Override
    public boolean isDark() {
        return false;
    }

    @Override
    protected List<Class<?>> getLafClassesForDefaultsLoading() {
        List<Class<?>> lafClasses = new ArrayList<>();
        lafClasses.add(FlatLaf.class);
        lafClasses.add(isDark() ? FlatDarkLaf.class : FlatLightLaf.class);
        //Burp can see its own palette; the extension has no way to find it.
        lafClasses.add(BurpColors.class);
        lafClasses.add(BurpLaf.class);
        if (getClass() != BurpLaf.class) lafClasses.add(getClass());
        return lafClasses;
    }
}
