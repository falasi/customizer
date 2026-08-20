package burp.ui.laf;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

import java.util.ArrayList;
import java.util.List;

/**
 * Stand-in for a Burp release which has moved its look and feel: different package, different
 * class names, and part of its UI defaults defined in code rather than in a properties file.
 * Nothing about it matches the names the extension used to look for, so it only works if the
 * extension discovers Burp's classes from the look and feel that is actually running.
 */
public class PortSwiggerTheme extends FlatLaf {

    @Override
    public String getName() {
        return "PortSwigger";
    }

    @Override
    public String getDescription() {
        return "Modern Burp stand-in";
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
        lafClasses.add(PortSwiggerColors.class);
        lafClasses.add(PortSwiggerTheme.class);
        if (getClass() != PortSwiggerTheme.class) lafClasses.add(getClass());
        return lafClasses;
    }
}
