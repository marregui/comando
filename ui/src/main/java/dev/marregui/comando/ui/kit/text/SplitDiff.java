package dev.marregui.comando.ui.kit.text;

import com.github.difflib.DiffUtils;
import com.github.difflib.algorithm.DiffAlgorithmListener;
import com.github.difflib.algorithm.myers.MyersDiff;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.theme.Theme;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JViewport;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Two versions of a text side by side, as IDEs compare them: each in an editor with its syntax colours and
 * line numbers, the lines one side lost in red and those the other gained in green, the words that changed inside a
 * changed line marked more strongly, and a band between the two joining each difference to its place on the other
 * side. Scrolling either side scrolls the other to the matching line; neither side is padded, so line numbers and
 * copied text are the file's own. The left side is read-only; the right one may be made editable ({@link #editable}),
 * its differences marked again from a comparison worked out for its new text ({@link #remark}).
 */
public final class SplitDiff {

    /** Changed stretches compared word by word at most this long, per side, in characters. */
    static final int MAX_WORD_DIFF_CHARS = 20_000;
    /** Texts with a longer line are not compared. */
    public static final int MAX_LINE_CHARS = 10_000;
    /** How many lines above a difference stay in view when it is scrolled to. */
    private static final int REVEAL_LINES = 3;

    /**
     * A difference: lines {@code [leftStart, leftEnd)} on the left became {@code [rightStart, rightEnd)} on the right.
     */
    public record Chunk(int leftStart, int leftEnd, int rightStart, int rightEnd) {

        boolean inserted() {
            return leftStart == leftEnd;
        }

        boolean deleted() {
            return rightStart == rightEnd;
        }
    }

    private static final Pattern WORDS = Pattern.compile("\\w+|\\s+|[^\\w\\s]");
    private static final int CONNECTOR_WIDTH = 28;

    private final RSyntaxTextArea left = Editors.viewer("");
    private final RSyntaxTextArea right = Editors.viewer("");
    private final RTextScrollPane leftScroll = Editors.scroll(left, true);
    private final RTextScrollPane rightScroll = Editors.scroll(right, true);
    private final JLabel leftTitle = new JLabel();
    private final JLabel rightTitle = new JLabel();
    /** Beside the right side's title: whether its edits wait to be written, or were. */
    private final JLabel rightStatus = new JLabel();
    private final Connector connector = new Connector();
    private final JPanel panel = new JPanel(new GridBagLayout());
    private List<Chunk> chunks = List.of();
    /** True while one side follows the other, so the follow does not echo back. */
    private boolean syncing;
    /** True while a difference is being scrolled to, so the scroll is not taken for the user's. */
    private boolean revealing;
    /** The difference last scrolled to, -1 when none or when the user scrolled since. */
    private int current = -1;
    /** The comparison shown. */
    private Model model = new Model("", "", List.of(), List.of(), List.of(), "", true);

    public SplitDiff() {
        for (JLabel l : List.of(leftTitle, rightTitle)) {
            l.setForeground(Theme.MUTED_STRONG);
            l.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        }
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;
        c.gridy = 0;
        c.weightx = 1;
        c.gridx = 0;
        panel.add(leftTitle, c);
        c.gridx = 2;
        JPanel rightHead = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
        rightHead.add(rightTitle);
        rightHead.add(rightStatus);
        panel.add(rightHead, c);
        c.gridy = 1;
        c.weighty = 1;
        c.gridx = 0;
        panel.add(leftScroll, c);
        c.gridx = 2;
        panel.add(rightScroll, c);
        c.gridx = 1;
        c.weightx = 0;
        panel.add(connector, c);
        leftScroll.getViewport().addChangeListener(e -> follow(leftScroll.getViewport(), rightScroll.getViewport(), true));
        rightScroll.getViewport().addChangeListener(e -> follow(rightScroll.getViewport(), leftScroll.getViewport(), false));
    }

    public JComponent component() {
        return panel;
    }

    public RSyntaxTextArea left() {
        return left;
    }

    public RSyntaxTextArea right() {
        return right;
    }

    public List<Chunk> chunks() {
        return chunks;
    }

    /** The comparison shown. */
    public Model model() {
        return model;
    }

    /**
     * What a comparison shows, worked out off the EDT by {@link #compute}: the two texts, their differences, the spans
     * of the words that changed (offsets into each text), and a note when the texts are not compared line by line or
     * not at all.
     *
     * @param compared false when the texts are not shown at all, {@code note} saying why (a line too long)
     */
    public record Model(String before, String after, List<Chunk> chunks, List<int[]> leftWords, List<int[]> rightWords,
                        String note, boolean compared) {
    }

    /** Signals that a diff ran past its deadline. */
    private static final class OutOfTime extends RuntimeException {
        OutOfTime() {
            super("diff out of time", null, false, false);
        }
    }

    /**
     * The comparison of {@code before} and {@code after}, within about {@code budgetMillis}: past it, the texts are
     * compared as a whole (one difference, no word marks) and the note says so. Texts with a line longer than
     * {@link #MAX_LINE_CHARS} are not compared: the editors lay such a line out in time that grows with its square.
     * Pure and thread-safe: run it off the EDT.
     */
    public static Model compute(String before, String after, long budgetMillis) {
        if (longestLine(before) > MAX_LINE_CHARS || longestLine(after) > MAX_LINE_CHARS) {
            return new Model("", "", List.of(), List.of(), List.of(),
                    "a line longer than " + MAX_LINE_CHARS + " characters (minified?): not compared", false);
        }
        List<String> a = lines(before);
        List<String> b = lines(after);
        long deadline = System.nanoTime() + budgetMillis * 1_000_000L;
        List<Chunk> chunks;
        try {
            chunks = chunks(a, b, deadline);
        } catch (OutOfTime e) {
            List<Chunk> whole = a.equals(b) ? List.of() : List.of(new Chunk(0, a.size(), 0, b.size()));
            return new Model(before, after, whole, List.of(), List.of(), "too many differences to match line by line: compared as a whole", true);
        }
        int[] la = lineStarts(before);
        int[] lb = lineStarts(after);
        List<int[]> lw = new ArrayList<>();
        List<int[]> rw = new ArrayList<>();
        try {
            for (Chunk c : chunks) {
                if (c.inserted() || c.deleted()) {
                    continue;
                }
                int l0 = la[c.leftStart()];
                int l1 = c.leftEnd() < la.length ? la[c.leftEnd()] : before.length();
                int r0 = lb[c.rightStart()];
                int r1 = c.rightEnd() < lb.length ? lb[c.rightEnd()] : after.length();
                if (l1 - l0 > MAX_WORD_DIFF_CHARS || r1 - r0 > MAX_WORD_DIFF_CHARS) {
                    continue;
                }
                List<int[]> cl = new ArrayList<>();
                List<int[]> cr = new ArrayList<>();
                wordChanges(before.substring(l0, l1), after.substring(r0, r1), cl, cr, deadline);
                cl.forEach(w -> lw.add(new int[]{l0 + w[0], l0 + w[1]}));
                cr.forEach(w -> rw.add(new int[]{r0 + w[0], r0 + w[1]}));
            }
        } catch (OutOfTime e) {
            // the line colours stand; the word marks found so far are kept
        }
        return new Model(before, after, chunks, lw, rw, "", true);
    }

    /** The characters of the longest line of {@code text}. */
    static int longestLine(String text) {
        int longest = 0;
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n') {
                longest = Math.max(longest, i - start);
                start = i + 1;
            }
        }
        return longest;
    }

    /** Where each line of {@code text} starts, as the editor counts lines; none for an empty text. */
    static int[] lineStarts(String text) {
        if (text.isEmpty()) {
            return new int[0];
        }
        int n = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                n++;
            }
        }
        int[] out = new int[n];
        int k = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                out[k++] = i + 1;
            }
        }
        return out;
    }

    /**
     * Shows {@code model} (worked out by {@link #compute}), coloured as {@code path}'s kind of file, with the sides'
     * titles, scrolled to the first difference; with {@code keepPlace}, scrolled where it was, for new versions of the
     * texts shown.
     */
    public void show(String path, String leftName, String rightName, Model model, boolean keepPlace) {
        Point place = rightScroll.getViewport().getViewPosition();
        String style = Editors.styleFor(Path.of(path.isEmpty() ? "text" : path));
        left.setSyntaxEditingStyle(style);
        right.setSyntaxEditingStyle(style);
        leftTitle.setText(Plain.text(leftName));
        rightTitle.setText(Plain.text(rightName));
        syncing = true;
        try {
            left.setText(model.before());
            right.setText(model.after());
            left.setCaretPosition(0);
            right.setCaretPosition(0);
            chunks = model.chunks();
            current = -1;
            this.model = model;
            mark(model);
        } finally {
            syncing = false;
        }
        connector.repaint();
        // after the layout that sizes the new texts, or the scroll is clamped to the old ones
        if (keepPlace) {
            Edt.later(() -> {
                syncing = true;
                try {
                    rightScroll.getViewport().setViewPosition(place);
                } finally {
                    syncing = false;
                }
                follow(rightScroll.getViewport(), leftScroll.getViewport(), false);
            });
        } else if (!chunks.isEmpty()) {
            Edt.later(() -> reveal(0));
        }
    }

    /** Says {@code text} beside the right side's title, in {@code colour}; empty says nothing. */
    public void rightStatus(String text, Color colour, String tip) {
        rightStatus.setText(text);
        rightStatus.setForeground(colour);
        rightStatus.setToolTipText(text.isEmpty() ? null : tip);
    }

    /** What the right side's status says; for tests. */
    public String rightStatus() {
        return rightStatus.getText();
    }

    /** Whether the right side may be edited. */
    public void editable(boolean on) {
        right.setEditable(on);
    }

    /**
     * Marks {@code model}'s differences on the texts shown, without setting them, so the right side keeps its caret,
     * scroll and undo history while it is edited; a model worked out for other texts than those shown is ignored.
     */
    public void remark(Model model) {
        if (!model.compared() || !left.getText().equals(model.before()) || !right.getText().equals(model.after())) {
            return;
        }
        chunks = model.chunks();
        current = -1;
        this.model = model;
        mark(model);
        connector.repaint();
    }

    /** {@link #compute} without a deadline, then {@link #show}; for tests and small texts. */
    void show(String path, String leftName, String before, String rightName, String after, boolean keepPlace) {
        show(path, leftName, rightName, compute(before, after, Long.MAX_VALUE / 2_000_000L), keepPlace);
    }

    /** The lines of {@code text} as the editor counts them; none for an empty text. */
    static List<String> lines(String text) {
        return text.isEmpty() ? List.of() : Arrays.asList(text.split("\n", -1));
    }

    /** The differences between two lists of lines, in order. */
    static List<Chunk> chunks(List<String> a, List<String> b) {
        return chunks(a, b, Long.MAX_VALUE);
    }

    private static List<Chunk> chunks(List<String> a, List<String> b, long deadline) {
        Patch<String> patch = DiffUtils.diff(a, b, new MyersDiff<>(), until(deadline));
        List<Chunk> out = new ArrayList<>(patch.getDeltas().size());
        for (AbstractDelta<String> d : patch.getDeltas()) {
            int ls = d.getSource().getPosition();
            int rs = d.getTarget().getPosition();
            out.add(new Chunk(ls, ls + d.getSource().size(), rs, rs + d.getTarget().size()));
        }
        return out;
    }

    /** A progress listener that stops the diff once {@code deadline} (a {@link System#nanoTime} value) has passed. */
    private static DiffAlgorithmListener until(long deadline) {
        return new DiffAlgorithmListener() {
            @Override
            public void diffStart() {
            }

            @Override
            public void diffStep(int value, int max) {
                if (System.nanoTime() - deadline > 0) {
                    throw new OutOfTime();
                }
            }

            @Override
            public void diffEnd() {
            }
        };
    }

    /**
     * The line on the other side that matches line {@code pos} (fractional, for a scroll position) on the left when
     * {@code fromLeft}, else on the right: the same distance into an unchanged stretch, the same share of a changed
     * one.
     */
    static double map(List<Chunk> chunks, double pos, boolean fromLeft) {
        double shift = 0;
        for (Chunk c : chunks) {
            int s = fromLeft ? c.leftStart() : c.rightStart();
            int e = fromLeft ? c.leftEnd() : c.rightEnd();
            int os = fromLeft ? c.rightStart() : c.leftStart();
            int oe = fromLeft ? c.rightEnd() : c.leftEnd();
            if (pos < s) {
                return pos + (os - s);
            }
            if (pos < e) {
                return os + (pos - s) * (oe - os) / (double) (e - s);
            }
            shift = oe - e;
        }
        return pos + shift;
    }

    /** Colours the lines each difference touches, then the words inside a changed stretch that differ. */
    private void mark(Model model) {
        left.removeAllLineHighlights();
        right.removeAllLineHighlights();
        left.getHighlighter().removeAllHighlights();
        right.getHighlighter().removeAllHighlights();
        Highlighter.HighlightPainter removed = new DefaultHighlighter.DefaultHighlightPainter(Theme.content().removedWord);
        Highlighter.HighlightPainter added = new DefaultHighlighter.DefaultHighlightPainter(Theme.content().addedWord);
        Color lost = Theme.content().removed;
        Color gained = Theme.content().added;
        try {
            for (Chunk c : chunks) {
                for (int i = c.leftStart(); i < c.leftEnd(); i++) {
                    left.addLineHighlight(i, lost);
                }
                for (int i = c.rightStart(); i < c.rightEnd(); i++) {
                    right.addLineHighlight(i, gained);
                }
            }
            for (int[] w : model.leftWords()) {
                left.getHighlighter().addHighlight(w[0], w[1], removed);
            }
            for (int[] w : model.rightWords()) {
                right.getHighlighter().addHighlight(w[0], w[1], added);
            }
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The character spans, in {@code a} and in {@code b}, of the words that differ between them, added to
     * {@code aSpans} and {@code bSpans}; none, and false, when more than half of either side's words differ, since
     * marking nearly all of a stretch tells nothing its line colour does not. Whitespace that differs is not marked.
     */
    static boolean wordChanges(String a, String b, List<int[]> aSpans, List<int[]> bSpans) {
        return wordChanges(a, b, aSpans, bSpans, Long.MAX_VALUE);
    }

    private static boolean wordChanges(String a, String b, List<int[]> aSpans, List<int[]> bSpans, long deadline) {
        List<int[]> aPos = new ArrayList<>();
        List<int[]> bPos = new ArrayList<>();
        List<String> aw = words(a, aPos);
        List<String> bw = words(b, bPos);
        Patch<String> patch = DiffUtils.diff(aw, bw, new MyersDiff<>(), until(deadline));
        int aChanged = 0;
        int bChanged = 0;
        List<int[]> as = new ArrayList<>();
        List<int[]> bs = new ArrayList<>();
        for (AbstractDelta<String> d : patch.getDeltas()) {
            aChanged += collect(d.getSource().getPosition(), d.getSource().size(), aw, aPos, as);
            bChanged += collect(d.getTarget().getPosition(), d.getTarget().size(), bw, bPos, bs);
        }
        if (aChanged * 2 > count(aw) || bChanged * 2 > count(bw)) {
            return false;
        }
        aSpans.addAll(as);
        bSpans.addAll(bs);
        return true;
    }

    private static int collect(int from, int size, List<String> words, List<int[]> pos, List<int[]> out) {
        int n = 0;
        for (int i = from; i < from + size; i++) {
            if (!words.get(i).isBlank()) {
                out.add(pos.get(i));
                n++;
            }
        }
        return n;
    }

    private static int count(List<String> words) {
        int n = 0;
        for (String w : words) {
            if (!w.isBlank()) {
                n++;
            }
        }
        return n;
    }

    private static List<String> words(String s, List<int[]> pos) {
        List<String> out = new ArrayList<>();
        Matcher m = WORDS.matcher(s);
        while (m.find()) {
            out.add(m.group());
            pos.add(new int[]{m.start(), m.end()});
        }
        return out;
    }

    /** Scrolls {@code to} to the line of {@code from}'s top that matches, and sideways as far. */
    private void follow(JViewport from, JViewport to, boolean fromLeft) {
        connector.repaint();
        if (!revealing) {
            // the user scrolled: the next difference is found from where the view is
            current = -1;
        }
        if (syncing) {
            return;
        }
        syncing = true;
        try {
            double lh = left.getLineHeight();
            Point p = from.getViewPosition();
            double line = map(chunks, p.y / lh, fromLeft);
            int maxY = Math.max(0, to.getView().getHeight() - to.getExtentSize().height);
            int maxX = Math.max(0, to.getView().getWidth() - to.getExtentSize().width);
            to.setViewPosition(new Point(Math.min(p.x, maxX), (int) Math.max(0, Math.min(Math.round(line * lh), maxY))));
        } finally {
            syncing = false;
        }
    }

    /**
     * Scrolls to the difference after the one last gone to, or, after the user scrolled, to the first below the
     * right side's top; false when there is none.
     */
    public boolean next() {
        int i = current;
        if (i < 0) {
            int top = topLine();
            i = chunks.size();
            for (int k = 0; k < chunks.size(); k++) {
                if (chunks.get(k).rightStart() > top) {
                    i = k;
                    break;
                }
            }
        } else {
            i++;
        }
        if (i >= chunks.size()) {
            return false;
        }
        reveal(i);
        return true;
    }

    /**
     * Scrolls to the difference before the one last gone to, or, after the user scrolled, to the last above the
     * right side's top; false when there is none.
     */
    public boolean previous() {
        int i = current;
        if (i < 0) {
            int top = topLine();
            i = -1;
            for (int k = chunks.size() - 1; k >= 0; k--) {
                if (chunks.get(k).rightStart() < top) {
                    i = k;
                    break;
                }
            }
        } else {
            i--;
        }
        if (i < 0) {
            return false;
        }
        reveal(i);
        return true;
    }

    /** The difference last gone to, or -1 when the user has scrolled since. */
    int current() {
        return current;
    }

    private int topLine() {
        // a difference is revealed a few lines below the top, so the top is read as that far down
        return (int) (rightScroll.getViewport().getViewPosition().y / (double) right.getLineHeight()) + REVEAL_LINES;
    }

    /** Puts difference {@code i} a few lines below the top of the right side; the left follows. */
    private void reveal(int i) {
        Chunk c = chunks.get(i);
        revealing = true;
        try {
            JViewport v = rightScroll.getViewport();
            int y = Math.max(0, (c.rightStart() - REVEAL_LINES) * right.getLineHeight());
            int maxY = Math.max(0, v.getView().getHeight() - v.getExtentSize().height);
            v.setViewPosition(new Point(0, Math.min(y, maxY)));
            if (y > maxY) {
                // the right side ends before the difference can reach the top: the left is placed on its own
                JViewport lv = leftScroll.getViewport();
                int ly = Math.max(0, (c.leftStart() - REVEAL_LINES) * left.getLineHeight());
                syncing = true;
                try {
                    lv.setViewPosition(new Point(0, Math.min(ly, Math.max(0, lv.getView().getHeight() - lv.getExtentSize().height))));
                } finally {
                    syncing = false;
                }
            }
        } finally {
            revealing = false;
        }
        current = i;
        connector.repaint();
    }

    /** The band between the sides: each difference joined from its lines on the left to its lines on the right. */
    private final class Connector extends JComponent {

        Connector() {
            setPreferredSize(new Dimension(CONNECTOR_WIDTH, 0));
            setMinimumSize(new Dimension(CONNECTOR_WIDTH, 0));
            setOpaque(true);
            setBackground(Theme.CARD);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setColor(left.getBackground());
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // the editors sit below their titles: the band's lines are measured from the editors' tops
                int top = leftScroll.getY() - getY();
                double lh = left.getLineHeight();
                int ly = leftScroll.getViewport().getViewPosition().y;
                int ry = rightScroll.getViewport().getViewPosition().y;
                int w = getWidth();
                for (Chunk c : chunks) {
                    int l0 = top + (int) (c.leftStart() * lh) - ly;
                    int l1 = top + (int) (c.leftEnd() * lh) - ly;
                    int r0 = top + (int) (c.rightStart() * lh) - ry;
                    int r1 = top + (int) (c.rightEnd() * lh) - ry;
                    if (Math.max(l1, r1) < 0 || Math.min(l0, r0) > getHeight()) {
                        continue;
                    }
                    Color color = c.inserted() ? Theme.VCS_ADDED : c.deleted() ? Theme.DANGER : Theme.VCS_MODIFIED;
                    g2.setColor(Theme.withAlpha(color, 90));
                    g2.fillPolygon(new Polygon(new int[]{0, w, w, 0}, new int[]{l0, r0, r1, l1}, 4));
                    g2.setColor(color);
                    g2.drawLine(0, l0, w, r0);
                    g2.drawLine(0, l1, w, r1);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
