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
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * A tab's card, in place of its tooltip: what the tooltip said, the panel's topic under it, and a button that reads the
 * topic again. It floats in the window's popup layer under the tab it describes and stays until it is clicked, or
 * until another tab's card takes its place; the window moves it with itself. EDT only.
 */
final class TabCard {

    static final int MAX_WIDTH = 640;
    private static final int MARGIN = 8;

    private final JPanel card = new JPanel(new BorderLayout(Theme.GAP, 2));
    /** Text from a session, a document and Haiku: never rendered as HTML. */
    private final JLabel status = Plain.only(new JLabel());
    private final JLabel topic = Plain.only(new JLabel());
    private final JButton refresh = new JButton(Icons.get("refresh", 14));
    private PanelId shown;
    private JLayeredPane layer;
    /** Where the card's top left corner goes, in the tabs' coordinates: under the tab, at its left edge. */
    private Point anchor;
    private JComponent tabs;

    TabCard(Consumer<PanelId> onRefresh) {
        card.setName("tab-card");
        Color background = UIManager.getColor("ToolTip.background");
        card.setBackground(background == null ? Theme.CARD : background);
        Color border = UIManager.getColor("Component.borderColor");
        card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(border == null ? Theme.CARD_BORDER : border),
                BorderFactory.createEmptyBorder(6, 8, 6, 4)));
        Font font = UIManager.getFont("ToolTip.font");
        if (font != null) {
            status.setFont(font);
            topic.setFont(font.deriveFont(Font.BOLD));
        }
        Color foreground = UIManager.getColor("ToolTip.foreground");
        if (foreground != null) {
            status.setForeground(foreground);
            topic.setForeground(foreground);
        }
        topic.setName("tab-card-topic");
        JPanel lines = new JPanel();
        lines.setOpaque(false);
        lines.setLayout(new BoxLayout(lines, BoxLayout.Y_AXIS));
        lines.add(status);
        lines.add(Box.createVerticalStrut(3));
        lines.add(topic);
        card.add(lines, BorderLayout.CENTER);
        refresh.setName("tab-card-refresh");
        refresh.putClientProperty("JButton.buttonType", "toolBarButton");
        refresh.setToolTipText("Read the panel's topic again with Haiku (about 2 s)");
        refresh.getAccessibleContext().setAccessibleName("Read the topic again");
        // the keyboard stays where it was: the card is looked at, not typed into
        refresh.setFocusable(false);
        refresh.addActionListener(e -> {
            if (shown != null) {
                onRefresh.accept(shown);
            }
        });
        card.add(refresh, BorderLayout.EAST);
        card.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                hide();
            }
        });
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
        topic.setText(Plain.text(topicText));
        Font f = topic.getFont();
        topic.setFont(f.deriveFont(muted ? Font.ITALIC : Font.BOLD));
        Color foreground = UIManager.getColor("ToolTip.foreground");
        topic.setForeground(muted ? Theme.MUTED : foreground == null ? status.getForeground() : foreground);
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

    private void place() {
        Point at = SwingUtilities.convertPoint(tabs, anchor, layer);
        int room = Math.max(120, layer.getWidth() - 2 * MARGIN);
        Dimension size = card.getPreferredSize();
        int w = Math.min(size.width, Math.min(MAX_WIDTH, room));
        int x = Math.max(MARGIN, Math.min(at.x, layer.getWidth() - w - MARGIN));
        card.setBounds(x, at.y, w, size.height);
        card.revalidate();
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
}
