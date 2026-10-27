package dev.marregui.comando.ui.widgets.transcript;

import dev.marregui.comando.core.state.BackgroundTask;
import dev.marregui.comando.core.view.Clip;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.icon.SpinnerIcon;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.widgets.transcript.render.TranscriptCanvas.RunningAgent;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * One line above the transcript while sub-agents run: a chip per running top-level Agent card (spinner, description,
 * elapsed time), which a click scrolls to and opens, then a chip per background sub-agent of the session that has no
 * running card (hourglass, description, elapsed time). Hidden when there is none. One line: the chips that do not fit
 * are counted in a last "+N" chip, whose tooltip names them. Its timer runs only while it shows.
 */
final class AgentStrip extends JPanel {

    static final int DESCRIPTION_CHARS = 28;
    static final String LOCAL_AGENT = "local_agent";
    private static final int GAP = 6;
    private static final int VGAP = 3;

    private final Consumer<String> reveal;
    private final LongSupplier clock;
    private final SpinnerIcon spinner = new SpinnerIcon(12);
    private final Timer timer = new Timer(SpinnerIcon.STEP_MILLIS, e -> tick());
    private final List<Chip> chips = new ArrayList<>();
    private final JLabel more = new JLabel();
    private List<RunningAgent> agents = List.of();
    private List<BackgroundTask> tasks = List.of();
    private long lastSecond = -1;

    AgentStrip(Consumer<String> reveal, LongSupplier clock) {
        super(null);
        this.reveal = reveal;
        this.clock = clock;
        setOpaque(false);
        setVisible(false);
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                updateTimer();
            }
        });
    }

    /**
     * Shows {@code agents}, the running top-level sub-agent cards, and the session's background sub-agents among
     * {@code tasks}; rebuilds the chips only when what they show changed.
     */
    void show(List<RunningAgent> agents, List<BackgroundTask> tasks) {
        List<BackgroundTask> background = background(agents, tasks);
        if (agents.equals(this.agents) && background.equals(this.tasks)) {
            return;
        }
        this.agents = List.copyOf(agents);
        this.tasks = background;
        removeAll();
        chips.clear();
        for (RunningAgent a : this.agents) {
            Chip c = new Chip(spinner, a.description(), a.startedMillis(), a.toolUseId());
            c.setToolTipText("Show this sub-agent's card");
            c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            c.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    reveal.accept(a.toolUseId());
                }
            });
            chips.add(c);
        }
        for (BackgroundTask t : background) {
            Chip c = new Chip(Icons.get("hourglass", 12), t.description(), t.startedMillis() > 0 ? t.startedMillis() : -1, "");
            c.setToolTipText("A sub-agent working in the background");
            chips.add(c);
        }
        chips.forEach(this::add);
        more.setFont(chips.isEmpty() ? more.getFont() : chips.getFirst().getFont());
        add(more);
        lastSecond = -1;
        relabel();
        setVisible(!chips.isEmpty());
        updateTimer();
        revalidate();
        repaint();
    }

    /** The background sub-agents that no running card shows already, by id or by description. */
    private static List<BackgroundTask> background(List<RunningAgent> agents, List<BackgroundTask> tasks) {
        Set<String> shown = new HashSet<>();
        for (RunningAgent a : agents) {
            shown.add(a.toolUseId());
            shown.add(a.agentId());
            shown.add(a.description());
        }
        return tasks.stream().filter(t -> t.kind().equals(LOCAL_AGENT) && !shown.contains(t.id()) && !shown.contains(t.description())).toList();
    }

    /** The chips in order; for tests. */
    List<JLabel> chips() {
        return List.copyOf(chips);
    }

    /** The chips laid out on the line, and the "+N" text, empty when all fit; for tests. */
    List<JLabel> shownChips() {
        return chips.stream().<JLabel>map(c -> c).filter(c -> c.getWidth() > 0).toList();
    }

    String moreText() {
        return more.getWidth() > 0 ? more.getText() : "";
    }

    @Override
    public Dimension getPreferredSize() {
        Insets in = getInsets();
        int w = 0;
        int h = 0;
        for (Chip c : chips) {
            Dimension d = c.getPreferredSize();
            w += d.width + GAP;
            h = Math.max(h, d.height);
        }
        return new Dimension(in.left + in.right + w + GAP, in.top + in.bottom + h + 2 * VGAP);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(0, getPreferredSize().height);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    /** Left to right on one line; what does not fit, keeping room for the "+N" chip, is counted in it. */
    @Override
    public void doLayout() {
        Insets in = getInsets();
        int right = getWidth() - in.right - GAP;
        int x = in.left + GAP;
        int y = in.top + VGAP;
        int fitted = 0;
        for (int i = 0; i < chips.size(); i++) {
            Dimension d = chips.get(i).getPreferredSize();
            int left = chips.size() - i - 1;
            int reserve = left == 0 ? 0 : GAP + moreWidth(left);
            if (x + d.width + reserve > right) {
                break;
            }
            chips.get(i).setBounds(x, y, d.width, d.height);
            x += d.width + GAP;
            fitted++;
        }
        StringBuilder hidden = new StringBuilder();
        for (int i = fitted; i < chips.size(); i++) {
            chips.get(i).setBounds(0, 0, 0, 0);
            hidden.append(hidden.isEmpty() ? "" : ", ").append(chips.get(i).description);
        }
        int rest = chips.size() - fitted;
        if (rest == 0) {
            more.setBounds(0, 0, 0, 0);
            return;
        }
        more.setText("+" + rest);
        more.setToolTipText(hidden.toString());
        Dimension d = more.getPreferredSize();
        more.setBounds(x, y + 2, d.width, d.height);
    }

    private int moreWidth(int n) {
        return more.getFontMetrics(more.getFont()).stringWidth("+" + n) + 2;
    }

    /** For tests: whether the timer runs. */
    boolean ticking() {
        return timer.isRunning();
    }

    private void updateTimer() {
        boolean want = isVisible() && isShowing() && !chips.isEmpty();
        if (want && !timer.isRunning()) {
            timer.start();
        } else if (!want && timer.isRunning()) {
            timer.stop();
        }
    }

    private void tick() {
        if (!isShowing() || chips.isEmpty()) {
            timer.stop();
            return;
        }
        if (!agents.isEmpty()) {
            spinner.advance();
            for (Chip c : chips) {
                if (c.getIcon() == spinner) {
                    c.repaint();
                }
            }
        }
        relabel();
    }

    /** The elapsed times, once a second. */
    private void relabel() {
        long now = clock.getAsLong();
        if (now / 1000 == lastSecond) {
            return;
        }
        lastSecond = now / 1000;
        for (Chip c : chips) {
            c.relabel(now);
        }
    }

    /** One chip: an icon, the description clipped, and the time since {@code startedMillis} when known. */
    private static final class Chip extends JLabel {
        private final String description;
        private final long startedMillis;
        final String toolUseId;

        Chip(Icon icon, String description, long startedMillis, String toolUseId) {
            super(icon);
            this.description = Clip.keepStart(description.isEmpty() ? "sub-agent" : description, DESCRIPTION_CHARS);
            this.startedMillis = startedMillis;
            this.toolUseId = toolUseId;
            setFont(Theme.font(getFont(), Theme.TextSize.SMALL));
            setIconTextGap(Theme.SPACE_XS);
            setBorder(BorderFactory.createEmptyBorder(2, Theme.SPACE_S, 2, Theme.SPACE_S));
            setOpaque(false);
        }

        void relabel(long now) {
            String text = startedMillis < 0 ? description : description + " · " + Labels.elapsed(now - startedMillis);
            if (!text.equals(getText())) {
                setText(text);
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Theme.CARD);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }
}
