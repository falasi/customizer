package com.coreyd97.burpcustomizer;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ContainerEvent;
import java.awt.event.KeyEvent;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * TEMPORARY DIAGNOSTICS - REMOVE BEFORE MERGING.
 * <p>
 * Narrowed to one question: of the containers Burp creates after the custom look and feel is
 * installed, which one is the Proxy request/response viewer, what state is it in when it fails
 * to render, and does giving that subtree - and only that subtree - the look and feel again
 * repair it.
 * <p>
 * Only split panes, tabbed panes and text editors are tracked. Renderers, labels, buttons and
 * menu items are ignored: they were burying the entries that matter.
 * <pre>
 * ctrl shift alt D   dump every tracked container which is currently on screen
 * ctrl shift alt R   dump them, call updateComponentTreeUI on each one only, dump again
 * </pre>
 * Everything, exceptions included, goes to the extension's Output log so it is one stream.
 */
final class LafDiagnostics {

    private static final int MAX_TRACKED = 60;
    private static final int MAX_PARENT_DEPTH = 6;

    private static volatile LafDiagnostics instance;

    private final long startedAt = System.currentTimeMillis();
    private final List<Tracked> tracked = Collections.synchronizedList(new ArrayList<>());
    private volatile long themeAppliedAt = -1;
    private volatile String themeName = "(none applied yet)";

    private record Tracked(WeakReference<JComponent> component, long createdAfterTheme, String parentChain) {
    }

    static synchronized void install() {
        if (instance != null) return;
        instance = new LafDiagnostics();
        //initialize() runs off the event dispatch thread; pushing an EventQueue belongs on it.
        SwingUtilities.invokeLater(instance::start);
    }

    static void themeApplied(String name) {
        LafDiagnostics diagnostics = instance;
        if (diagnostics == null) return;
        diagnostics.themeAppliedAt = System.currentTimeMillis();
        diagnostics.themeName = name;
        diagnostics.log("THEME APPLIED: \"" + name + "\" - look and feel is now "
                + UIManager.getLookAndFeel().getClass().getName());
    }

    private void start() {
        log("diagnostics installed (split panes, tabbed panes and text editors only)."
                + "\n    Burp's look and feel is " + UIManager.getLookAndFeel().getClass().getName()
                + "\n    ctrl shift alt D = dump every tracked container currently on screen"
                + "\n    ctrl shift alt R = dump, re-apply the UI to each of them only, dump again");
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
                        StringWriter trace = new StringWriter();
                        thrown.printStackTrace(new PrintWriter(trace));
                        //Deliberately the output log, not the error log, so the whole sequence
                        //reads in one place.
                        log("EDT EXCEPTION dispatching " + event.getClass().getName()
                                + " on " + (event.getSource() == null ? "(no source)" : event.getSource().getClass().getName())
                                + "\n" + trace);
                        throw thrown;
                    }
                }
            });
        } catch (RuntimeException e) {
            log("Could not install the EDT exception trap: " + e);
        }
    }

    // ------------------------------------------------------------------ creation

    private void watchComponentCreation() {
        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (!(event instanceof ContainerEvent containerEvent)) return;
            if (containerEvent.getID() != ContainerEvent.COMPONENT_ADDED) return;
            if (themeAppliedAt < 0) return;

            Component child = containerEvent.getChild();
            if (!(child instanceof JComponent candidate) || !isCandidate(candidate)) return;
            if (tracked.size() >= MAX_TRACKED) return;

            String parentChain = parentChain(containerEvent.getContainer());
            tracked.add(new Tracked(new WeakReference<>(candidate), System.currentTimeMillis() - themeAppliedAt, parentChain));
            log("CREATED AFTER THEME  " + candidate.getClass().getName() + "  ui=" + uiClassOf(candidate)
                    + "\n    parents: " + parentChain);
        }, AWTEvent.CONTAINER_EVENT_MASK);
    }

    /**
     * The containers this bug could live in, and nothing else. A component is a candidate if it
     * is a split pane or tabbed pane (whatever Burp calls the subclass), if Burp's own split
     * pane or tabbed pane delegate is installed on it, or if it is a text editor.
     */
    private static boolean isCandidate(JComponent component) {
        if (component instanceof JSplitPane || component instanceof JTabbedPane) return true;
        if (component instanceof JEditorPane || component instanceof JTextArea || component instanceof JTextPane) return true;

        String ui = uiClassOf(component);
        return ui.contains("SplitPaneUI") || ui.contains("TabbedPaneUI");
    }

    private static String parentChain(Container from) {
        StringBuilder chain = new StringBuilder();
        int depth = 0;
        for (Container parent = from; parent != null && depth < MAX_PARENT_DEPTH; parent = parent.getParent(), depth++) {
            if (depth > 0) chain.append(" < ");
            chain.append(parent.getClass().getName());
        }
        return chain.length() == 0 ? "(none)" : chain.toString();
    }

    // ------------------------------------------------------------------ hotkeys

    private void installHotkeys() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventPostProcessor(event -> {
            if (event.getID() != KeyEvent.KEY_PRESSED) return false;
            if (!event.isControlDown() || !event.isShiftDown() || !event.isAltDown()) return false;

            if (event.getKeyCode() == KeyEvent.VK_D) {
                dumpOnScreen("SNAPSHOT");
                return true;
            }
            if (event.getKeyCode() == KeyEvent.VK_R) {
                dumpOnScreen("BEFORE REPAIR");
                repairOnScreen();
                dumpOnScreen("AFTER REPAIR");
                return true;
            }
            return false;
        });
    }

    /**
     * The tracked containers which are actually on screen right now - with Proxy open and a
     * history row selected, that is the viewer in question and its ancestors.
     */
    private List<JComponent> onScreen() {
        List<JComponent> showing = new ArrayList<>();
        synchronized (tracked) {
            for (Tracked entry : tracked) {
                JComponent component = entry.component().get();
                if (component != null && component.isShowing()) showing.add(component);
            }
        }
        return showing;
    }

    private void dumpOnScreen(String heading) {
        List<JComponent> showing = onScreen();
        StringBuilder report = new StringBuilder(stamp() + " " + heading
                + "\n  theme \"" + themeName + "\", look and feel " + UIManager.getLookAndFeel().getClass().getName()
                + "\n  tracked since the theme was applied: " + tracked.size()
                + ", on screen now: " + showing.size());

        if (showing.isEmpty()) {
            report.append("\n  Nothing tracked is on screen. Open Proxy > HTTP history and select a row first.");
        }
        for (JComponent component : showing) {
            report.append("\n  ------------------------------------------------------------");
            appendDetail(component, report);
        }
        BurpCustomizer.logOutput(report.toString());
    }

    private void repairOnScreen() {
        List<JComponent> showing = onScreen();
        log("REPAIR: calling updateComponentTreeUI on " + showing.size()
                + " tracked container(s) only - nothing else in the UI is touched");
        for (JComponent component : showing) {
            try {
                SwingUtilities.updateComponentTreeUI(component);
                component.revalidate();
                component.repaint();
            } catch (RuntimeException e) {
                StringWriter trace = new StringWriter();
                e.printStackTrace(new PrintWriter(trace));
                log("REPAIR threw on " + component.getClass().getName() + "\n" + trace);
            }
        }
    }

    // ------------------------------------------------------------------ description

    private void appendDetail(JComponent component, StringBuilder into) {
        long createdAfterTheme = -1;
        String parentChain = "(not recorded)";
        synchronized (tracked) {
            for (Tracked entry : tracked) {
                if (entry.component().get() == component) {
                    createdAfterTheme = entry.createdAfterTheme();
                    parentChain = entry.parentChain();
                    break;
                }
            }
        }

        into.append("\n  ").append(component.getClass().getName())
                .append("   created +").append(createdAfterTheme).append("ms after theme")
                .append("\n    ui=").append(uiClassOf(component))
                .append("\n    ").append(stateOf(component))
                .append("\n    parents: ").append(parentChain);

        if (component instanceof JSplitPane splitPane) {
            into.append("\n    SPLIT divider=").append(splitPane.getDividerLocation())
                    .append(" last=").append(splitPane.getLastDividerLocation())
                    .append(" dividerSize=").append(splitPane.getDividerSize())
                    .append(" weight=").append(splitPane.getResizeWeight())
                    .append(" orientation=").append(splitPane.getOrientation() == JSplitPane.VERTICAL_SPLIT ? "VERTICAL" : "HORIZONTAL")
                    .append("\n      top    ").append(childState(splitPane.getTopComponent()))
                    .append("\n      bottom ").append(childState(splitPane.getBottomComponent()));
        }
        if (component instanceof JTabbedPane tabbedPane) {
            into.append("\n    TABS count=").append(tabbedPane.getTabCount())
                    .append(" selected=").append(tabbedPane.getSelectedIndex());
            for (int i = 0; i < tabbedPane.getTabCount(); i++) {
                into.append("\n      [").append(i).append("] \"").append(tabbedPane.getTitleAt(i)).append("\" ")
                        .append(i == tabbedPane.getSelectedIndex() ? "(selected) " : "")
                        .append(childState(tabbedPane.getComponentAt(i)));
            }
        }
    }

    private static String childState(Component child) {
        return child == null ? "NULL" : child.getClass().getName() + " " + stateOf(child);
    }

    private static String stateOf(Component component) {
        Rectangle bounds = component.getBounds();
        StringBuilder state = new StringBuilder();
        state.append("vis=").append(component.isVisible())
                .append(" showing=").append(component.isShowing())
                .append(" enabled=").append(component.isEnabled())
                .append(" bounds=").append(bounds.width).append("x").append(bounds.height)
                .append("@").append(bounds.x).append(",").append(bounds.y)
                .append(" pref=").append(sizeOf(component.getPreferredSize()))
                .append(" min=").append(sizeOf(component.getMinimumSize()))
                .append(" bg=").append(colourOf(component.getBackground()))
                .append(" fg=").append(colourOf(component.getForeground()));
        if (component instanceof JComponent jComponent)
            state.append(" opaque=").append(jComponent.isOpaque())
                    .append(" children=").append(jComponent.getComponentCount());
        return state.toString();
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
