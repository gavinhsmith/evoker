package com.gavinhsmith.evoker;

import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Windows evoker shows when Prism runs it before a launch: there is no console to ask in or print to there. */
final class Dialogs {
    private Dialogs() {}

    /** Only when Prism runs us (it sets INST_ID), without a console, and with a display. */
    static boolean available() {
        return System.console() == null && System.getenv("INST_ID") != null && !GraphicsEnvironment.isHeadless();
    }

    /** An optional entry to offer; selected when it is installed now. */
    record Choice(String key, String title, String description, boolean selected) {}

    /** A checkbox per entry; returns the chosen keys (none if the window is closed or cancelled). */
    static Set<String> choose(String pack, List<Choice> choices) {
        return onEdt(() -> {
            var panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.add(new JLabel(pack + " has optional content. Choose what to install:"));
            var boxes = new ArrayList<JCheckBox>();
            for (Choice c : choices) {
                panel.add(Box.createVerticalStrut(10));
                var box = new JCheckBox(c.title(), c.selected());
                boxes.add(box);
                panel.add(box);
                if (c.description() != null) {
                    var description = new JLabel("<html><div style='width:320px'>" + html(c.description()) + "</div></html>");
                    description.setBorder(BorderFactory.createEmptyBorder(0, 24, 0, 0));
                    panel.add(description);
                }
            }
            var chosen = new TreeSet<String>();
            if (show(panel, "evoker: " + pack, JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                for (int i = 0; i < choices.size(); i++) {
                    if (boxes.get(i).isSelected()) chosen.add(choices.get(i).key());
                }
            }
            return chosen;
        });
    }

    static void message(String pack, String text) {
        onEdt(() -> show(new JLabel(text), "evoker: " + pack, JOptionPane.DEFAULT_OPTION));
    }

    /** A dialog kept on top, so it doesn't open behind Prism's window. */
    private static int show(Object content, String title, int options) {
        var pane = new JOptionPane(content, JOptionPane.PLAIN_MESSAGE, options);
        JDialog dialog = pane.createDialog(null, title);
        dialog.setAlwaysOnTop(true);
        dialog.setVisible(true);
        dialog.dispose();
        return pane.getValue() instanceof Integer i ? i : JOptionPane.CLOSED_OPTION;
    }

    private static <T> T onEdt(Supplier<T> action) {
        var result = new ArrayList<T>(1);
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception ignored) {
                    // the default look works too
                }
                result.add(action.get());
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EvokerException("interrupted", e);
        } catch (InvocationTargetException e) {
            throw new EvokerException("cannot show a window: " + e.getCause(), e);
        }
        return result.getFirst();
    }

    private static String html(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
