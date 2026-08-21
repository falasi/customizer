package com.coreyd97.burpcustomizer;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

public class CustomizerPanel extends JPanel {

    private static final String NO_CUSTOM_THEME = "No theme file loaded";

    private static final String ORIGINAL_PROJECT_URL = "https://github.com/CoreyD97/BurpCustomizer";
    private static final String PORTSWIGGER_PROJECT_URL = "https://github.com/PortSwigger/customizer";
    private static final String FORK_PROJECT_URL = "https://github.com/falasi/customizer";

    /**
     * Keeps the controls a readable width rather than letting them stretch the whole way
     * across a maximised Burp window.
     */
    private static final int CONTROL_WIDTH = 320;

    private final BurpCustomizer customizer;
    private File selectedThemeFile;
    public final PreviewPanel previewPanel;
    private final JComboBox<UIManager.LookAndFeelInfo> lookAndFeelSelector;
    private final JLabel customThemeLabel;
    private final JLabel titleLabel;
    private final JLabel subtitleLabel;

    /**
     * The parts of this panel whose font and colour come from the theme rather than from a
     * fixed value, so they can be worked out again whenever the theme changes.
     */
    private final List<JComponent> mutedText = new ArrayList<>();
    private final List<JComponent> linkText = new ArrayList<>();
    private final List<JComponent> widthLimited = new ArrayList<>();

    public CustomizerPanel(BurpCustomizer customizer) {
        this.customizer = customizer;
        this.setLayout(new BorderLayout());

        titleLabel = new JLabel("Burp Customizer");
        subtitleLabel = new JLabel("Catppuccin themes for Burp Suite");

        //The preview is no longer part of the layout, but the selector still builds a theme
        //for it, which is what reports a theme that cannot be loaded.
        previewPanel = new PreviewPanel();

        lookAndFeelSelector = new JComboBox<>();
        lookAndFeelSelector.setRenderer(new LookAndFeelRenderer());
        for (UIManager.LookAndFeelInfo theme : customizer.getThemeManager().getSelectableThemes()) {
            lookAndFeelSelector.addItem(theme);
        }
        lookAndFeelSelector.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) {
                selectedThemeFile = null;
                try {
                    LookAndFeel theme = customizer.createThemeFromDefaults((UIManager.LookAndFeelInfo) e.getItem(), true);
                    previewPanel.setPreviewTheme(theme);
                } catch (ThemeLoadException | UnsupportedLookAndFeelException ex) {
                    BurpCustomizer.logError("Could not load theme for preview.", ex);
                    previewPanel.reset();
                    JOptionPane.showMessageDialog(CustomizerPanel.this, ex.getMessage(), "Burp Customizer", JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        JButton loadThemeFileButton = new JButton(new AbstractAction("Load Theme File...") {
            @Override
            public void actionPerformed(ActionEvent e) {
                JFileChooser fileChooser = new JFileChooser();
                fileChooser.setFileFilter(new FileNameExtensionFilter("IntelliJ/FlatLaf Theme File (*.theme.json, *.json)", "json"));
                if (fileChooser.showOpenDialog(CustomizerPanel.this) != JFileChooser.APPROVE_OPTION) return;

                //Applied immediately - no need to press Apply, and no need to restart Burp.
                selectedThemeFile = fileChooser.getSelectedFile();
                lookAndFeelSelector.setSelectedItem(null);
                customizer.setTheme(selectedThemeFile);
            }
        });

        customThemeLabel = new JLabel(NO_CUSTOM_THEME);
        mutedText.add(customThemeLabel);

        JButton applyThemeButton = new JButton(new AbstractAction("Apply") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (lookAndFeelSelector.getSelectedItem() != null) {
                    customizer.setTheme((UIManager.LookAndFeelInfo) lookAndFeelSelector.getSelectedItem());
                } else if (selectedThemeFile != null) {
                    customizer.setTheme(selectedThemeFile);
                } else {
                    JOptionPane.showMessageDialog(CustomizerPanel.this, "No theme selected!", "Burp Customizer", JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        if (customizer.getThemeSource() == ThemeManager.ThemeSource.BUILTIN && customizer.getSelectedBuiltIn() != null) {
            lookAndFeelSelector.setSelectedItem(customizer.getSelectedBuiltIn());
        } else if (customizer.getThemeSource() == ThemeManager.ThemeSource.FILE && customizer.getSelectedThemeFile() != null) {
            lookAndFeelSelector.setSelectedItem(null);
            selectedThemeFile = customizer.getSelectedThemeFile();
        }
        updateCustomThemeLabel();

        widthLimited.add(lookAndFeelSelector);
        widthLimited.add(loadThemeFileButton);
        widthLimited.add(applyThemeButton);

        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setOpaque(false);
        //Gives the column its width, so the controls below are laid out against it.
        column.add(Box.createRigidArea(new Dimension(CONTROL_WIDTH, 0)));
        column.add(centred(titleLabel));
        column.add(Box.createVerticalStrut(4));
        column.add(centred(subtitleLabel));
        column.add(Box.createVerticalStrut(8));
        column.add(centred(buildAttribution()));
        column.add(Box.createVerticalStrut(28));
        column.add(centred(lookAndFeelSelector));
        column.add(Box.createVerticalStrut(18));
        column.add(centred(loadThemeFileButton));
        column.add(Box.createVerticalStrut(6));
        column.add(centred(customThemeLabel));
        column.add(Box.createVerticalStrut(22));
        column.add(centred(applyThemeButton));

        //Holds the column at the top of the tab and centred across whatever width Burp gives it.
        JPanel centred = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.weightx = 1;
        constraints.weighty = 1;
        constraints.anchor = GridBagConstraints.NORTH;
        centred.add(column, constraints);
        centred.setBorder(new EmptyBorder(40, 20, 20, 20));

        applyThemeStyling();

        JScrollPane scrollPane = new JScrollPane(centred);
        scrollPane.setBorder(null);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

        this.add(scrollPane, BorderLayout.CENTER);
    }

    /**
     * Credit for the extension, kept to one line. The full history and licence live in the
     * project's README.
     */
    private JComponent buildAttribution() {
        JPanel attribution = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        attribution.setOpaque(false);
        attribution.add(mutedLabel("Original extension by "));
        attribution.add(link("CoreyD97", ORIGINAL_PROJECT_URL));
        attribution.add(mutedLabel(" · maintained by "));
        attribution.add(link("PortSwigger", PORTSWIGGER_PROJECT_URL));
        attribution.add(mutedLabel(" · fork by "));
        attribution.add(link("falasi", FORK_PROJECT_URL));
        return attribution;
    }

    private JLabel mutedLabel(String text) {
        JLabel label = new JLabel(text);
        mutedText.add(label);
        return label;
    }

    private JButton link(String text, String url) {
        JButton button = new JButton(text);
        button.setBorder(BorderFactory.createEmptyBorder());
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setMargin(new Insets(0, 0, 0, 0));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setToolTipText(url);
        button.addActionListener(e -> browse(url));
        linkText.add(button);
        return button;
    }

    private static void browse(String url) {
        try {
            if (!Desktop.isDesktopSupported()) return;
            Desktop.getDesktop().browse(new URI(url));
        } catch (IOException | URISyntaxException | RuntimeException e) {
            BurpCustomizer.logError("Could not open " + url + " in a browser.", e);
        }
    }

    private static <T extends JComponent> T centred(T component) {
        component.setAlignmentX(Component.CENTER_ALIGNMENT);
        return component;
    }

    /**
     * Called after the applied theme changed, so the theme dependent parts of this panel stay
     * in sync. Must be called on the EDT.
     */
    public void themeChanged() {
        applyThemeStyling();
        updateCustomThemeLabel();
        revalidate();
        repaint();
    }

    /**
     * Sizes and colours the text from the theme's own defaults rather than from fixed values,
     * so this panel follows a light theme and a dark one alike. Reapplied on every theme
     * change, because switching the look and feel puts every component back on the theme's
     * own font and colour.
     */
    private void applyThemeStyling() {
        Font base = UIManager.getFont("Label.font");
        if (base == null) base = titleLabel.getFont();
        Font small = base.deriveFont(Math.max(base.getSize2D() - 1f, 9f));

        titleLabel.setFont(base.deriveFont(Font.BOLD, base.getSize2D() + 10f));
        subtitleLabel.setFont(base);
        subtitleLabel.setForeground(mutedForeground());

        for (JComponent component : mutedText) {
            component.setFont(small);
            component.setForeground(mutedForeground());
        }
        for (JComponent component : linkText) {
            component.setFont(small);
            component.setForeground(linkForeground());
        }
        for (JComponent component : widthLimited) {
            component.setMaximumSize(new Dimension(CONTROL_WIDTH, component.getPreferredSize().height));
        }
    }

    /**
     * The theme's own quiet text colour, for text which should not compete with the controls.
     */
    private static Color mutedForeground() {
        Color muted = UIManager.getColor("Label.disabledForeground");
        if (muted == null) muted = UIManager.getColor("Component.disabledColor");
        if (muted == null) muted = UIManager.getColor("Label.foreground");
        return muted;
    }

    private static Color linkForeground() {
        Color link = UIManager.getColor("Component.linkColor");
        if (link == null) link = UIManager.getColor("Component.accentColor");
        if (link == null) link = UIManager.getColor("Label.foreground");
        return link;
    }

    private void updateCustomThemeLabel() {
        String customThemeName = customizer.getThemeManager().getCustomThemeName();
        if (customizer.getThemeSource() == ThemeManager.ThemeSource.FILE && customThemeName != null) {
            customThemeLabel.setText("Custom: " + customThemeName);
        } else if (selectedThemeFile != null) {
            customThemeLabel.setText(selectedThemeFile.getName());
        } else {
            customThemeLabel.setText(NO_CUSTOM_THEME);
        }
    }

    private static class LookAndFeelRenderer extends DefaultListCellRenderer {

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            UIManager.LookAndFeelInfo lookAndFeelInfo = ((UIManager.LookAndFeelInfo) value);
            return super.getListCellRendererComponent(list, lookAndFeelInfo != null ? lookAndFeelInfo.getName() : "Unknown", index, isSelected, cellHasFocus);
        }
    }
}
