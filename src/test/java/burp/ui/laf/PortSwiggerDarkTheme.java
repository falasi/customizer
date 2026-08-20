package burp.ui.laf;

import javax.swing.*;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.InsetsUIResource;
import java.awt.*;

/**
 * The dark variant, which also defines a slice of its Burp specific defaults in code.
 * Those keys cannot be reloaded from a properties file by anybody, so they are what the
 * extension has to derive from the theme being applied.
 */
public class PortSwiggerDarkTheme extends PortSwiggerTheme {

    /**
     * Foreground/background pairs Burp paints text with. Colours are this stand-in's own -
     * the point is that they must not survive into a light theme unchanged.
     */
    public static final String[][] TEXT_PAIRS = {
            {"Burp.messageEditorForeground", "Burp.messageEditorBackground"},
            {"Burp.tableRowForeground", "Burp.tableRowBackground"},
            {"Burp.sidebarForeground", "Burp.sidebarBackground"},
            {"Burp.searchBarForeground", "Burp.searchBarBackground"},
            {"Burp.tooltipForeground", "Burp.tooltipBackground"},
            {"Burp.selectedRowForeground", "Burp.selectedRowBackground"},
            {"Burp.settingsPanelForeground", "Burp.settingsPanelBackground"},
            {"Burp.menuForeground", "Burp.menuBackground"},
            {"Burp.tabForeground", "Burp.tabBackground"},
    };

    @Override
    public boolean isDark() {
        return true;
    }

    @Override
    public UIDefaults getDefaults() {
        UIDefaults defaults = super.getDefaults();

        defaults.put("Burp.messageEditorBackground", new ColorUIResource(0x1e1e1e));
        defaults.put("Burp.messageEditorForeground", new ColorUIResource(0xdcdcdc));
        defaults.put("Burp.tableRowBackground", new ColorUIResource(0x2b2b2b));
        defaults.put("Burp.tableRowForeground", new ColorUIResource(0xd0d0d0));
        defaults.put("Burp.sidebarBackground", new ColorUIResource(0x313335));
        defaults.put("Burp.sidebarForeground", new ColorUIResource(0xc8c8c8));
        defaults.put("Burp.searchBarBackground", new ColorUIResource(0x45494a));
        defaults.put("Burp.searchBarForeground", new ColorUIResource(0xe0e0e0));
        defaults.put("Burp.tooltipBackground", new ColorUIResource(0x4b4d4e));
        defaults.put("Burp.tooltipForeground", new ColorUIResource(0xf0f0f0));
        defaults.put("Burp.selectedRowBackground", new ColorUIResource(0x4b6eaf));
        defaults.put("Burp.selectedRowForeground", new ColorUIResource(0xffffff));
        defaults.put("Burp.settingsPanelBackground", new ColorUIResource(0x3c3f41));
        defaults.put("Burp.settingsPanelForeground", new ColorUIResource(0xbbbbbb));
        defaults.put("Burp.menuBackground", new ColorUIResource(0x2f3133));
        defaults.put("Burp.menuForeground", new ColorUIResource(0xcccccc));
        defaults.put("Burp.tabBackground", new ColorUIResource(0x3c3f41));
        defaults.put("Burp.tabForeground", new ColorUIResource(0xbbbbbb));
        defaults.put("Burp.disabledText", new ColorUIResource(0x777777));
        defaults.put("Burp.searchBarBorder", new ColorUIResource(0x5e6060));

        //Burp's branding, used as ordinary chrome. None of this should survive theming.
        defaults.put("Burp.burpOrange", new ColorUIResource(0xff6633));
        defaults.put("Burp.tabFlashColour", new ColorUIResource(0xff6633));
        defaults.put("Burp.primaryButtonBackground", new ColorUIResource(0xe8613c));
        defaults.put("Burp.collapsibleSidebarSelectedLabelBackground", new ColorUIResource(0xff6633));

        //Keys whose name says Background. They name a surface, not the text on it.
        defaults.put("Burp.textEditorBackground", new ColorUIResource(0x1e1e1e));
        defaults.put("Burp.textEditorCurrentLineBackground", new ColorUIResource(0x323232));

        //Red because it means something. These have to stay red.
        defaults.put("Burp.issueSeverityHigh", new ColorUIResource(0xd6483b));
        defaults.put("Burp.errorForeground", new ColorUIResource(0xd6483b));
        defaults.put("Burp.warningForeground", new ColorUIResource(0xe8a33d));
        defaults.put("Colors.swatches.red.core", new ColorUIResource(0xd6483b));

        //Burp's grey ramps, which its components index into.
        for (int i = 0; i <= 8; i++)
            defaults.put("ColourPalette.mono" + i, new ColorUIResource(new Color(0x2b + i * 12, 0x2b + i * 12, 0x2b + i * 12)));
        for (int i = 0; i <= 12; i++)
            defaults.put("DesignSystemPalette.grey" + i, new ColorUIResource(new Color(0x25 + i * 11, 0x25 + i * 11, 0x25 + i * 11)));

        //Not a colour, and not theme dependent - it should carry over untouched.
        defaults.put("Burp.tabInsets", new InsetsUIResource(2, 8, 2, 8));

        return defaults;
    }
}
