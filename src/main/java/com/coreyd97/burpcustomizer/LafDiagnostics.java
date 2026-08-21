package com.coreyd97.burpcustomizer;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ContainerEvent;
import java.awt.event.KeyEvent;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TEMPORARY DIAGNOSTICS - REMOVE BEFORE MERGING.
 * <p>
 * Answers one question: what is different about a Burp component which is created after the
 * custom look and feel has been installed, compared with one which existed beforehand and was
 * updated into it.
 * <p>
 * It reports three things and changes nothing:
 * <ul>
 *   <li>every interesting component added to the UI, marked with whether it appeared before or
 *       after the theme was applied, and how long after;</li>
 *   <li>any exception thrown on the event dispatch thread, which is where a failing
 *       {@code installUI} would surface;</li>
 *   <li>on demand, the state of the component tree around whatever has focus - and, separately,
 *       what happens if that one container is given the current look and feel again.</li>
 * </ul>
 * Hotkeys, chosen to sit alongside the FlatLaf inspectors this extension already installs:
 * <pre>
 * ctrl shift alt D   dump the tree around the focused component
 * ctrl shift alt R   dump it, then call updateComponentTreeUI on the nearest enclosing
 *                    split pane only, and dump it again
 * </pre>
 */
final class LafDiagnostics {

    private static final int MAX_ENTRIES = 250;
    private static final int MAX_PER_CLASS = 3;
    private static final int MAX_TREE_DEPTH = 6;
    private static final int MAX_TREE_NODES = 60;

    private static volatile LafDiagnostics instance;

    private final long startedAt = System.currentTimeMillis();
    private final Map<String, Integer> seenPerClass = new ConcurrentHashMap<>();
    private final AtomicInteger entries = new AtomicInteger();
    private volatile long themeAppliedAt = -1;
    private volatile String themeName = "(none applied yet)";

    static synchronized void install() {
        if (instance != null) return;
        instance = new LafDiagnostics();
        //initialize() runs off the event dispatch thread; pushing an EventQueue belongs on it.
        SwingUtilities.invokeLater(instance::start);
    }

    /**
     * Called once the custom look and feel is in place, so everything after this point is a
     * component born under it.
     */
    static void themeApplied(String name) {
        LafDiagnostics diagnostics = instance;
        if (diagnostics == null) return;
        diagnostics.themeAppliedAt = System.currentTimeMillis();
        diagnostics.themeName = name;
        diagnostics.log("THEME APPLIED: \"" + name + "\" - look and feel is now "
                + UIManager.getLookAndFeel().getClass().getName()
                + "\n    everything logged from here on is created under the custom look and feel");
    }

    private void start() {
        log("diagnostics installed. Burp's look and feel is " + UIManager.getLookAndFeel().getClass().getName()
                + "\n    ctrl shift alt D = dump the tree around the focused component"
                + "\n    ctrl shift alt R = dump, re-apply the UI to the nearest split pane only, dump again");
        trapEventDispatchThreadExceptions();
        watchComponentCreation();
        installHotkeys();
    }

    // ------------------------------------------------------------------ exceptions

    private void trapEventDispatchThreadExceptions() {
        try {
            Toolkit.getDefaultToolkit().getSystemEventQueue().push(new EventQueue() {
                @Override
                protected void dispatchEvent(AWTEvent event) {
                    try {
                        super.dispatchEvent(event);
                    } catch (Throwable thrown) {
                        BurpCustomizer.logError(stamp() + " EDT EXCEPTION while dispatching "
                                + event.getClass().getName() + " on " + describeSource(event), thrown);
                        throw thrown;
                    }
                }
            });
        } catch (RuntimeException e) {
            BurpCustomizer.logError("Could not install the EDT exception trap.", e);
        }
    }

    private static String describeSource(AWTEvent event) {
        Object source = event.getSource();
        return source == null ? "(no source)" : source.getClass().getName();
    }

    // ------------------------------------------------------------------ creation

    private void watchComponentCreation() {
        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (!(event instanceof ContainerEvent containerEvent)) return;
            if (containerEvent.getID() != ContainerEvent.COMPONENT_ADDED) return;

            Component child = containerEvent.getChild();
            if (!isInteresting(child)) return;
            if (entries.get() >= MAX_ENTRIES) return;

            String className = child.getClass().getName();
            int seen = seenPerClass.merge(className, 1, Integer::sum);
            if (seen > MAX_PER_CLASS) return;
            entries.incrementAndGet();

            log((themeAppliedAt < 0 ? "BEFORE THEME" : "AFTER THEME") + "  component added"
                    + "\n    " + describe(child)
                    + "\n    added to " + (containerEvent.getContainer() == null ? "(none)"
                            : containerEvent.getContainer().getClass().getName()));
        }, AWTEvent.CONTAINER_EVENT_MASK);
    }

    /**
     * Burp's own components, and the container types this bug lives in. Everything else would
     * bury the interesting entries.
     */
    private static boolean isInteresting(Component component) {
        if (component == null) return false;
        if (component instanceof JTabbedPane || component instanceof JSplitPane
                || component instanceof JEditorPane || component instanceof JTextArea
                || component instanceof JTextPane) return true;
        String name = component.getClass().getName();
        return name.startsWith("burp.") || name.startsWith("com.portswigger.");
    }

    // ------------------------------------------------------------------ hotkeys

    private void installHotkeys() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventPostProcessor(event -> {
            if (event.getID() != KeyEvent.KEY_PRESSED) return false;
            if (!event.isControlDown() || !event.isShiftDown() || !event.isAltDown()) return false;

            if (event.getKeyCode() == KeyEvent.VK_D) {
                dumpFocused("DUMP");
                return true;
            }
            if (event.getKeyCode() == KeyEvent.VK_R) {
                dumpFocused("BEFORE RE-APPLY");
                reapplyUiToNearestSplitPane();
                dumpFocused("AFTER RE-APPLY");
                return true;
            }
            return false;
        });
    }

    private Component focused() {
        Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (focusOwner != null) return focusOwner;
        Window window = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        return window;
    }

    private void dumpFocused(String heading) {
        Component focusOwner = focused();
        if (focusOwner == null) {
            log(heading + ": nothing has focus");
            return;
        }

        StringBuilder report = new StringBuilder(stamp() + " " + heading
                + "\n  theme: \"" + themeName + "\", look and feel " + UIManager.getLookAndFeel().getClass().getName()
                + "\n  focus owner: " + describe(focusOwner)
                + "\n  --- ancestors ---");

        int depth = 0;
        for (Component parent = focusOwner.getParent(); parent != null && depth < 14; parent = parent.getParent(), depth++)
            report.append("\n  ").append("  ".repeat(depth)).append(describe(parent));

        JSplitPane splitPane = nearestSplitPane(focusOwner);
        if (splitPane == null) {
            report.append("\n  --- no enclosing split pane ---");
        } else {
            report.append("\n  --- nearest enclosing split pane ---\n  ").append(describe(splitPane));
            report.append("\n  --- top/left subtree ---");
            appendTree(splitPane.getTopComponent(), 1, new int[]{0}, report);
            report.append("\n  --- bottom/right subtree ---");
            appendTree(splitPane.getBottomComponent(), 1, new int[]{0}, report);
        }
        BurpCustomizer.logOutput(report.toString());
    }

    private void reapplyUiToNearestSplitPane() {
        Component focusOwner = focused();
        JSplitPane splitPane = nearestSplitPane(focusOwner);
        Component target = splitPane != null ? splitPane
                : focusOwner != null ? focusOwner.getParent() : null;
        if (target == null) {
            log("RE-APPLY: nothing to re-apply to");
            return;
        }
        log("RE-APPLY: calling updateComponentTreeUI on " + target.getClass().getName() + " only");
        try {
            SwingUtilities.updateComponentTreeUI(target);
            target.invalidate();
            target.validate();
            target.repaint();
        } catch (RuntimeException e) {
            BurpCustomizer.logError("RE-APPLY threw", e);
        }
    }

    private static JSplitPane nearestSplitPane(Component from) {
        for (Component parent = from; parent != null; parent = parent.getParent())
            if (parent instanceof JSplitPane splitPane) return splitPane;
        return null;
    }

    // ------------------------------------------------------------------ description

    private void appendTree(Component component, int depth, int[] count, StringBuilder into) {
        if (component == null) {
            into.append("\n  ").append("  ".repeat(depth)).append("(null)");
            return;
        }
        if (count[0]++ >= MAX_TREE_NODES) return;
        into.append("\n  ").append("  ".repeat(depth)).append(describe(component));
        if (depth >= MAX_TREE_DEPTH || !(component instanceof Container container)) return;
        for (Component child : container.getComponents()) appendTree(child, depth + 1, count, into);
    }

    private static String describe(Component component) {
        StringBuilder description = new StringBuilder(component.getClass().getName());
        description.append(" ui=").append(uiClassOf(component));
        description.append(" vis=").append(component.isVisible())
                .append(" showing=").append(component.isShowing())
                .append(" enabled=").append(component.isEnabled());
        Rectangle bounds = component.getBounds();
        description.append(" bounds=").append(bounds.width).append("x").append(bounds.height)
                .append("@").append(bounds.x).append(",").append(bounds.y);
        description.append(" pref=").append(sizeOf(component.getPreferredSize()))
                .append(" min=").append(sizeOf(component.getMinimumSize()));
        description.append(" bg=").append(colourOf(component.getBackground()))
                .append(" fg=").append(colourOf(component.getForeground()));
        if (component instanceof JComponent jComponent)
            description.append(" opaque=").append(jComponent.isOpaque());

        if (component instanceof JTabbedPane tabbedPane) {
            description.append(" TABS=").append(tabbedPane.getTabCount())
                    .append(" selected=").append(tabbedPane.getSelectedIndex())
                    .append(" titles=").append(titlesOf(tabbedPane));
        }
        if (component instanceof JSplitPane splitPane) {
            description.append(" SPLIT divider=").append(splitPane.getDividerLocation())
                    .append(" last=").append(splitPane.getLastDividerLocation())
                    .append(" size=").append(splitPane.getDividerSize())
                    .append(" weight=").append(splitPane.getResizeWeight())
                    .append(" orientation=").append(splitPane.getOrientation() == JSplitPane.VERTICAL_SPLIT ? "VERTICAL" : "HORIZONTAL")
                    .append(" top=").append(classOf(splitPane.getTopComponent()))
                    .append(" bottom=").append(classOf(splitPane.getBottomComponent()));
        }
        return description.toString();
    }

    private static String uiClassOf(Component component) {
        if (!(component instanceof JComponent)) return "(not a JComponent)";
        try {
            Method getUI = component.getClass().getMethod("getUI");
            Object ui = getUI.invoke(component);
            return ui == null ? "NULL" : ui.getClass().getName();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "(no getUI)";
        }
    }

    private static List<String> titlesOf(JTabbedPane tabbedPane) {
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < tabbedPane.getTabCount(); i++) titles.add(String.valueOf(tabbedPane.getTitleAt(i)));
        return titles;
    }

    private static String classOf(Component component) {
        return component == null ? "NULL" : component.getClass().getName();
    }

    private static String sizeOf(Dimension size) {
        return size == null ? "null" : size.width + "x" + size.height;
    }

    private static String colourOf(Color colour) {
        return colour == null ? "NULL" : BurpDefaults.hex(colour);
    }

    // ------------------------------------------------------------------ logging

    private void log(String message) {
        BurpCustomizer.logOutput(stamp() + " " + message);
    }

    private String stamp() {
        long now = System.currentTimeMillis();
        String sinceTheme = themeAppliedAt < 0 ? "theme not applied yet" : "+" + (now - themeAppliedAt) + "ms after theme";
        return "[diag +" + (now - startedAt) + "ms | " + sinceTheme + "]";
    }
}
