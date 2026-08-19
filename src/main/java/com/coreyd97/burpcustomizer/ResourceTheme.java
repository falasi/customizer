package com.coreyd97.burpcustomizer;

import javax.swing.*;
import java.io.FileNotFoundException;
import java.io.InputStream;

/**
 * A theme which is bundled with the extension as an IntelliJ {@code .theme.json} resource,
 * rather than as a {@link com.formdev.flatlaf.IntelliJTheme.ThemeLaf} subclass.
 * <p>
 * It extends {@link UIManager.LookAndFeelInfo} so bundled JSON themes can sit in the same
 * theme list, combo box and preferences as the FlatLaf IntelliJ themes. The "class name"
 * is a synthetic identifier ({@code resource:/themes/Something.theme.json}) which is only
 * used to remember the selection.
 */
public class ResourceTheme extends UIManager.LookAndFeelInfo {

    private static final String ID_PREFIX = "resource:";

    private final String resourcePath;

    public ResourceTheme(String name, String resourcePath) {
        super(name, ID_PREFIX + resourcePath);
        this.resourcePath = resourcePath;
    }

    public String getResourcePath() {
        return resourcePath;
    }

    /**
     * Opens the bundled theme file. The caller is responsible for closing the stream.
     */
    public InputStream openStream() throws FileNotFoundException {
        InputStream in = ResourceTheme.class.getResourceAsStream(resourcePath);
        if (in == null)
            throw new FileNotFoundException("The bundled theme \"" + getName() + "\" is missing from the extension jar (" + resourcePath + ").");
        return in;
    }
}
