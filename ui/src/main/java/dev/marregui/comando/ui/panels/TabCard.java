package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * A tab's card, in place of its tooltip: what the tooltip said; under it, set apart, "Summary:" and the panel's topic
 * in gold, wrapped to a few lines; at the bottom left, a button that reads the summary again and one that closes the
 * card. It floats in the window's popup layer under the tab it describes and stays until it is closed or clicked, or
 * until another tab's card takes its place; the window moves it with itself. EDT only.
 */
final class TabCard {

    static final int MAX_WIDTH = 640;
    /** The summary's width: about 70 characters a line, so a summary of 300 takes four lines at most. */
    static final int SUMMARY_WIDTH = 460;
    private static final int MARGIN = 8;

    private final JPanel card = new JPanel();
    /** Text from a session, a document and Haiku: never rendered as HTML. */
    private final JLabel status = Plain.only(new JLabel());
    private final JLabel heading = Plain.only(new JLabel("Summary:"));
    private final JTextArea topic = new JTextArea();
    private final JButton refresh = button("refresh", "tab-card-refresh", "Summarise the panel again with Haiku (about 2 s)",
            "Summarise again");
    private final JButton close = button("close", "tab-card-close", "Close the card", "Close the card");
    private PanelId shown;
    private JLayeredPane layer;
    /** Where the card's top left corner goes, in the tabs' coordinates: under the tab, at its left edge. */
    private Point anchor;
    private JComponent tabs;

    TabCard(Consumer<PanelId> onRefresh) {
        card.setName("tab-card");
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        Color background = UIManager.getColor("ToolTip.background");
        card.setBackground(background == null ? Theme.CARD : background);
        Color border = UIManager.getColor("Component.borderColor");
        card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(border == null ? Theme.CARD_BORDER : border),
                BorderFactory.createEmptyBorder(8, 10, 6, 10)));
        Font font = UIManager.getFont("ToolTip.font");
        Color foreground = UIManager.getColor("ToolTip.foreground");
        if (font != null) {
            status.setFont(font);
            heading.setFont(font.deriveFont(Font.BOLD));
            topic.setFont(font);
        }
        if (foreground != null) {
            status.setForeground(foreground);
        }
        heading.setForeground(Theme.GOLD);
        topic.setName("tab-card-topic");
        topic.setEditable(false);
        topic.setFocusable(false);
        topic.setOpaque(false);
        topic.setLineWrap(true);
        topic.setWrapStyleWord(true);
        topic.setBorder(BorderFactory.createEmptyBorder());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        actions.setOpaque(false);
        actions.add(refresh);
        actions.add(close);
        refresh.addActionListener(e -> {
            if (shown != null) {
                onRefresh.accept(shown);
            }
        });
        close.addActionListener(e -> hide());
        for (JComponent c : new JComponent[]{status, heading, topic, actions}) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        card.add(status);
        card.add(Box.createVerticalStrut(10));
        card.add(heading);
        card.add(Box.createVerticalStrut(2));
        card.add(topic);
        card.add(Box.createVerticalStrut(6));
        card.add(actions);
        MouseAdapter closeOnPress = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                hide();
            }
        };
        card.addMouseListener(closeOnPress);
        // the summary is a text area, which takes the press itself: a press on it closes the card as on the rest
        topic.addMouseListener(closeOnPress);
    }

    private static JButton button(String icon, String name, String tip, String accessible) {
        JButton b = new JButton(Icons.get(icon, 14));
        b.setName(name);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setToolTipText(tip);
        b.getAccessibleContext().setAccessibleName(accessible);
        // the keyboard stays where it was: the card is looked at, not typed into
        b.setFocusable(false);
        return b;
    }

    /** Shows the card of {@code id} under {@code tab}, a rectangle of {@code over}, replacing any card shown. */
    void show(PanelId id, JComponent over, Rectangle tab, String statusText, String topicText, boolean muted, boolean reading) {
        JRootPane root = SwingUtilities.getRootPane(over);
        if (root == null) {
            return;
        }
        if (layer != root.getLayeredPane()) {
            hide();
            layer = root.getLayeredPane();
        }
        shown = id;
        tabs = over;
        anchor = new Point(tab.x, tab.y + tab.height + 2);
        if (card.getParent() != layer) {
            layer.add(card, JLayeredPane.POPUP_LAYER);
        }
        update(statusText, topicText, muted, reading);
    }

    /** New text for the card shown, sized and placed again; nothing when none shows. */
    void update(String statusText, String topicText, boolean muted, boolean reading) {
        if (shown == null) {
            return;
        }
        status.setText(Plain.text(statusText));
        topic.setText(topicText);
        Font f = topic.getFont();
        topic.setFont(f.deriveFont(muted ? Font.ITALIC : Font.PLAIN));
        topic.setForeground(muted ? Theme.MUTED : Theme.GOLD);
        refresh.setEnabled(!reading);
        place();
    }

    /** Puts the card shown under {@code tab} again, a rectangle of the tabs it was shown over: the tab moved. */
    void moveUnder(Rectangle tab) {
        if (shown == null) {
            return;
        }
        Point now = new Point(tab.x, tab.y + tab.height + 2);
        if (!now.equals(anchor)) {
            anchor = now;
            place();
        }
    }

    /**
     * Sizes the card: as wide as the status line or the summary's width, whichever is wider, within the window; the
     * summary wraps to that width, and the card is as tall as its lines.
     */
    private void place() {
        Point at = SwingUtilities.convertPoint(tabs, anchor, layer);
        int room = Math.max(160, layer.getWidth() - 2 * MARGIN);
        Insets in = card.getInsets();
        int inner = Math.min(Math.max(status.getPreferredSize().width, SUMMARY_WIDTH), Math.min(MAX_WIDTH, room) - in.left - in.right);
        topic.setPreferredSize(null);
        topic.setMaximumSize(null);
        topic.setSize(inner, Short.MAX_VALUE);
        int topicHeight = topic.getPreferredSize().height;
        topic.setPreferredSize(new Dimension(inner, topicHeight));
        topic.setMaximumSize(new Dimension(inner, topicHeight));
        int w = inner + in.left + in.right;
        card.invalidate(); // the box layout keeps its children's sizes until told: the summary's lines changed
        int h = card.getPreferredSize().height;
        int x = Math.max(MARGIN, Math.min(at.x, layer.getWidth() - w - MARGIN));
        card.setBounds(x, at.y, w, h);
        card.validate();
        layer.repaint();
    }

    void hide() {
        if (shown == null) {
            return;
        }
        shown = null;
        if (layer != null && card.getParent() == layer) {
            Rectangle was = card.getBounds();
            layer.remove(card);
            layer.repaint(was);
        }
    }

    /** The panel whose card shows, null when none does. */
    PanelId shown() {
        return shown;
    }

    JComponent component() {
        return card;
    }

    String statusText() {
        return status.getText();
    }

    String topicText() {
        return topic.getText();
    }

    JButton refreshButton() {
        return refresh;
    }

    JButton closeButton() {
        return close;
    }

    /** The summary's own component, which wraps it; for tests. */
    JTextArea topicArea() {
        return topic;
    }
}
