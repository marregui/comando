package dev.marregui.comando.ui.widgets.plot;

import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Series over shared axes. Points are sorted by x once per data change; a paint finds the visible range
 * by binary search and reduces each pixel column to its minimum and maximum, so a series of millions of
 * points costs one linear pass and draws at most two vertices per pixel.
 */
final class PlotCanvas extends JComponent {

    private static final int LEFT = 70;
    private static final int RIGHT = 20;
    private static final int TOP = 26;
    private static final int BOTTOM = 42;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIME_MILLIS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    /** A series with x ascending. */
    private record Sorted(String name, double[] x, double[] y, Color color) {
    }

    private final List<Sorted> series = new ArrayList<>();
    private String xLabel = "";
    private String yLabel = "";
    private boolean timeX;
    private double dataX0, dataX1, dataY0, dataY1;
    private double viewX0, viewX1, viewY0, viewY1;
    private int dragStart = -1;
    private int dragNow = -1;
    private int hoverX = -1;
    private float fontScale = 1f;

    PlotCanvas() {
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow(); // zoom and export keys act on the focused widget
                dragStart = e.getX();
                dragNow = e.getX();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                dragNow = e.getX();
                hoverX = e.getX();
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (dragStart >= 0 && Math.abs(dragNow - dragStart) > 6) {
                    double a = toX(Math.min(dragStart, dragNow));
                    double b = toX(Math.max(dragStart, dragNow));
                    zoomTo(a, b);
                }
                dragStart = -1;
                dragNow = -1;
                repaint();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    reset();
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                hoverX = e.getX();
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hoverX = -1;
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        setFocusable(true);
    }

    void setData(ViewSpec.Plot plot) {
        series.clear();
        xLabel = plot.xLabel();
        yLabel = plot.yLabel();
        timeX = plot.timeX();
        int i = 0;
        for (ViewSpec.Series s : plot.series()) {
            series.add(sorted(s, Theme.SERIES[i++ % Theme.SERIES.length]));
        }
        dataX0 = Double.POSITIVE_INFINITY;
        dataX1 = Double.NEGATIVE_INFINITY;
        dataY0 = Double.POSITIVE_INFINITY;
        dataY1 = Double.NEGATIVE_INFINITY;
        for (Sorted s : series) {
            for (int k = 0; k < s.x.length; k++) {
                if (Double.isFinite(s.x[k]) && Double.isFinite(s.y[k])) {
                    dataX0 = Math.min(dataX0, s.x[k]);
                    dataX1 = Math.max(dataX1, s.x[k]);
                    dataY0 = Math.min(dataY0, s.y[k]);
                    dataY1 = Math.max(dataY1, s.y[k]);
                }
            }
        }
        if (!Double.isFinite(dataX0)) {
            dataX0 = 0;
            dataX1 = 1;
            dataY0 = 0;
            dataY1 = 1;
        }
        if (dataX0 == dataX1) {
            // one instant: half a minute either side reads as time; a number gets one unit
            double half = timeX ? 30_000 : 1;
            dataX0 -= half;
            dataX1 += half;
        }
        if (dataY0 == dataY1) {
            dataY0 -= 1;
            dataY1 += 1;
        }
        reset();
    }

    private static Sorted sorted(ViewSpec.Series s, Color color) {
        double[] x = s.x();
        double[] y = s.y();
        boolean ascending = true;
        for (int i = 1; i < x.length && ascending; i++) {
            ascending = x[i - 1] <= x[i];
        }
        if (ascending) {
            return new Sorted(s.name(), x, y, color);
        }
        Integer[] idx = new Integer[x.length];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, (a, b) -> Double.compare(x[a], x[b]));
        double[] sx = new double[x.length];
        double[] sy = new double[y.length];
        for (int i = 0; i < idx.length; i++) {
            sx[i] = x[idx[i]];
            sy[i] = y[idx[i]];
        }
        return new Sorted(s.name(), sx, sy, color);
    }

    void reset() {
        double padY = (dataY1 - dataY0) * 0.05;
        viewX0 = dataX0;
        viewX1 = dataX1;
        viewY0 = dataY0 - padY;
        viewY1 = dataY1 + padY;
        repaint();
    }

    void zoomTo(double x0, double x1) {
        if (x1 - x0 <= 0) {
            return;
        }
        viewX0 = x0;
        viewX1 = x1;
        fitY();
        repaint();
    }

    void zoom(int steps) {
        double mid = (viewX0 + viewX1) / 2;
        double half = (viewX1 - viewX0) / 2 * Math.pow(0.8, steps);
        zoomTo(mid - half, mid + half);
    }

    void scaleFont(int steps) {
        fontScale = steps == 0 ? 1f : Math.max(0.6f, Math.min(2.5f, fontScale + 0.1f * steps));
        repaint();
    }

    /** Fits y to the points inside the visible x range. */
    private void fitY() {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (Sorted s : series) {
            int a = lowerBound(s.x, viewX0);
            int b = lowerBound(s.x, viewX1 + Math.ulp(viewX1));
            for (int i = a; i < b; i++) {
                if (Double.isFinite(s.y[i])) {
                    lo = Math.min(lo, s.y[i]);
                    hi = Math.max(hi, s.y[i]);
                }
            }
        }
        if (Double.isFinite(lo)) {
            double pad = Math.max((hi - lo) * 0.05, 1e-9);
            viewY0 = lo - pad;
            viewY1 = hi + pad;
        }
    }

    static int lowerBound(double[] a, double key) {
        int lo = 0;
        int hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] < key) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static Rectangle area(int width, int height) {
        return new Rectangle(LEFT, TOP, Math.max(1, width - LEFT - RIGHT), Math.max(1, height - TOP - BOTTOM));
    }

    private double toX(int px) {
        Rectangle a = area(getWidth(), getHeight());
        return viewX0 + (px - a.x) * (viewX1 - viewX0) / a.width;
    }

    private int px(double x, Rectangle a) {
        return a.x + (int) Math.round((x - viewX0) / (viewX1 - viewX0) * a.width);
    }

    private int py(double y, Rectangle a) {
        return a.y + a.height - (int) Math.round((y - viewY0) / (viewY1 - viewY0) * a.height);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            paintPlot(g, getWidth(), getHeight());
        } finally {
            g.dispose();
        }
    }

    BufferedImage snapshot() {
        BufferedImage img = new BufferedImage(Math.max(getWidth(), 800), Math.max(getHeight(), 500), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(getBackground() == null ? Theme.CARD : getBackground());
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            int saved = hoverX;
            hoverX = -1;
            paintPlot(g, img.getWidth(), img.getHeight());
            hoverX = saved;
        } finally {
            g.dispose();
        }
        return img;
    }

    private void paintPlot(Graphics2D g, int width, int height) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Font font = getFont() == null ? new Font(Font.SANS_SERIF, Font.PLAIN, 12) : getFont();
        g.setFont(font.deriveFont(font.getSize2D() * fontScale));
        FontMetrics fm = g.getFontMetrics();
        Rectangle a = area(width, height);
        g.setColor(Theme.CARD_BORDER);
        g.draw(a);
        // grid and ticks
        double[] yTicks = ticks(viewY0, viewY1, Math.max(2, a.height / 50));
        double[] xTicks = timeX ? timeTicks(viewX0, viewX1, Math.max(2, a.width / 110)) : ticks(viewX0, viewX1, Math.max(2, a.width / 110));
        g.setStroke(new BasicStroke(1f));
        for (double t : yTicks) {
            int y = py(t, a);
            g.setColor(Theme.CARD_BORDER);
            g.drawLine(a.x, y, a.x + a.width, y);
            g.setColor(Theme.MUTED);
            String label = number(t, viewY1 - viewY0);
            g.drawString(label, a.x - fm.stringWidth(label) - 6, y + fm.getAscent() / 2 - 1);
        }
        for (double t : xTicks) {
            int x = px(t, a);
            g.setColor(Theme.CARD_BORDER);
            g.drawLine(x, a.y, x, a.y + a.height);
            g.setColor(Theme.MUTED);
            String label = timeX ? time(t, viewX1 - viewX0) : number(t, viewX1 - viewX0);
            g.drawString(label, x - fm.stringWidth(label) / 2, a.y + a.height + fm.getAscent() + 4);
        }
        g.setColor(Theme.MUTED);
        if (!xLabel.isEmpty()) {
            g.drawString(xLabel, a.x + a.width - fm.stringWidth(xLabel), height - 6);
        }
        if (!yLabel.isEmpty()) {
            g.drawString(yLabel, 6, TOP - 8);
        }
        // series
        Graphics2D clip = (Graphics2D) g.create();
        clip.clip(a);
        clip.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (Sorted s : series) {
            clip.setColor(s.color);
            clip.draw(path(s, a));
            dots(clip, s, a);
        }
        clip.dispose();
        // legend, after the y label
        int lx = Math.max(a.x + 8, yLabel.isEmpty() ? 0 : 6 + fm.stringWidth(yLabel) + 16);
        for (Sorted s : series) {
            g.setColor(s.color);
            g.fillRect(lx, 8, 10, 10);
            g.setColor(Theme.MUTED);
            g.drawString(s.name, lx + 14, 8 + fm.getAscent() - 2);
            lx += fm.stringWidth(s.name) + 30;
        }
        // zoom box and crosshair
        if (dragStart >= 0 && dragNow >= 0) {
            g.setColor(Theme.withAlpha(Theme.ACCENT, 50));
            g.fillRect(Math.min(dragStart, dragNow), a.y, Math.abs(dragNow - dragStart), a.height);
        } else if (hoverX >= a.x && hoverX <= a.x + a.width) {
            paintCrosshair(g, a, fm);
        }
    }

    /** Marks each point when few are visible, so a sparse or single-point series shows. */
    private void dots(Graphics2D g, Sorted s, Rectangle a) {
        int from = Math.max(0, lowerBound(s.x, viewX0) - 1);
        int to = Math.min(s.x.length, lowerBound(s.x, viewX1) + 1);
        if (to - from > Math.max(2, a.width / 12)) {
            return;
        }
        for (int i = from; i < to; i++) {
            if (Double.isFinite(s.y[i]) && Double.isFinite(s.x[i])) {
                g.fillOval(px(s.x[i], a) - 3, py(s.y[i], a) - 3, 6, 6);
            }
        }
    }

    /** One vertex pair (min, max) per pixel column for the visible points. */
    private Path2D path(Sorted s, Rectangle a) {
        Path2D.Double p = new Path2D.Double();
        int from = Math.max(0, lowerBound(s.x, viewX0) - 1);
        int to = Math.min(s.x.length, lowerBound(s.x, viewX1) + 1);
        int column = Integer.MIN_VALUE;
        double lo = 0;
        double hi = 0;
        double first = 0;
        double last = 0;
        boolean started = false;
        for (int i = from; i < to; i++) {
            double y = s.y[i];
            if (!Double.isFinite(y) || !Double.isFinite(s.x[i])) {
                continue; // a point without an x has no place on the axis
            }
            int c = px(s.x[i], a);
            if (c != column) {
                if (column != Integer.MIN_VALUE) {
                    started = emit(p, column, first, lo, hi, last, a, started);
                }
                column = c;
                lo = hi = first = last = y;
            } else {
                lo = Math.min(lo, y);
                hi = Math.max(hi, y);
                last = y;
            }
        }
        if (column != Integer.MIN_VALUE) {
            emit(p, column, first, lo, hi, last, a, started);
        }
        return p;
    }

    private boolean emit(Path2D.Double p, int column, double first, double lo, double hi, double last, Rectangle a, boolean started) {
        if (!started) {
            p.moveTo(column, py(first, a));
        } else {
            p.lineTo(column, py(first, a));
        }
        if (lo != hi) {
            p.lineTo(column, py(lo, a));
            p.lineTo(column, py(hi, a));
        }
        p.lineTo(column, py(last, a));
        return true;
    }

    private void paintCrosshair(Graphics2D g, Rectangle a, FontMetrics fm) {
        g.setColor(Theme.MUTED);
        g.drawLine(hoverX, a.y, hoverX, a.y + a.height);
        double x = toX(hoverX);
        List<String> lines = new ArrayList<>();
        lines.add(timeX ? Instant.ofEpochMilli((long) x).toString() : number(x, viewX1 - viewX0));
        for (Sorted s : series) {
            int i = lowerBound(s.x, x);
            int best = i;
            if (i > 0 && (i == s.x.length || Math.abs(s.x[i - 1] - x) < Math.abs(s.x[i] - x))) {
                best = i - 1;
            }
            // points without a finite x sort last; the nearest point is the last finite one before them
            while (best > 0 && best < s.x.length && !Double.isFinite(s.x[best])) {
                best--;
            }
            if (best < s.x.length && Double.isFinite(s.x[best]) && Double.isFinite(s.y[best])) {
                lines.add(s.name + ": " + number(s.y[best], viewY1 - viewY0));
                g.setColor(s.color);
                g.fillOval(px(s.x[best], a) - 3, py(s.y[best], a) - 3, 7, 7);
            }
        }
        int w = 0;
        for (String l : lines) {
            w = Math.max(w, fm.stringWidth(l));
        }
        int h = lines.size() * fm.getHeight() + 8;
        int bx = hoverX + 12 + w + 16 > a.x + a.width ? hoverX - w - 28 : hoverX + 12;
        g.setColor(Theme.CARD);
        g.fillRoundRect(bx, a.y + 8, w + 16, h, 8, 8);
        g.setColor(Theme.CARD_BORDER);
        g.drawRoundRect(bx, a.y + 8, w + 16, h, 8, 8);
        g.setColor(getForeground());
        int y = a.y + 8 + fm.getAscent() + 4;
        for (String l : lines) {
            g.drawString(l, bx + 8, y);
            y += fm.getHeight();
        }
    }

    /** "Nice" tick values: steps of 1, 2 or 5 times a power of ten. */
    static double[] ticks(double lo, double hi, int target) {
        double range = hi - lo;
        if (!(range > 0) || !Double.isFinite(range)) {
            return new double[0];
        }
        double raw = range / target;
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        double norm = raw / mag;
        double step = (norm < 1.5 ? 1 : norm < 3.5 ? 2 : norm < 7.5 ? 5 : 10) * mag;
        double start = Math.ceil(lo / step) * step;
        int n = (int) Math.floor((hi - start) / step) + 1;
        double[] out = new double[Math.max(0, Math.min(n, 1000))];
        for (int i = 0; i < out.length; i++) {
            out[i] = start + i * step;
        }
        return out;
    }

    static String number(double v, double range) {
        if (Math.abs(v) < 1e-12 * Math.max(1, Math.abs(range))) {
            return "0";
        }
        double abs = Math.abs(v);
        if (abs >= 1e7 || abs < 1e-4) {
            return String.format(Locale.ROOT, "%.3g", v);
        }
        int decimals = range >= 100 ? 0 : range >= 1 ? 2 : 4;
        return String.format(Locale.ROOT, "%." + decimals + "f", v);
    }

    /** Steps a clock reads well: milliseconds, seconds, minutes, hours and days in 1-2-5 or clock divisions. */
    private static final long[] TIME_STEPS = {
            1, 2, 5, 10, 20, 50, 100, 200, 500,
            1_000, 2_000, 5_000, 10_000, 15_000, 30_000,
            60_000, 120_000, 300_000, 600_000, 900_000, 1_800_000,
            3_600_000, 7_200_000, 10_800_000, 21_600_000, 43_200_000,
            86_400_000L, 172_800_000L, 604_800_000L, 2_592_000_000L, 31_536_000_000L};

    /** Time ticks on epoch milliseconds, aligned to whole steps in UTC, at most about {@code target} of them. */
    static double[] timeTicks(double lo, double hi, int target) {
        double range = hi - lo;
        if (!(range > 0) || !Double.isFinite(range)) {
            return new double[0];
        }
        long step = TIME_STEPS[TIME_STEPS.length - 1];
        for (long s : TIME_STEPS) {
            if (range / s <= target) {
                step = s;
                break;
            }
        }
        double start = Math.ceil(lo / step) * step;
        int n = (int) Math.floor((hi - start) / step) + 1;
        double[] out = new double[Math.max(0, Math.min(n, 1000))];
        for (int i = 0; i < out.length; i++) {
            out[i] = start + (double) i * step;
        }
        return out;
    }

    static String time(double millis, double range) {
        Instant t = Instant.ofEpochMilli((long) millis);
        if (range < 10_000L) {
            return TIME_MILLIS.format(t);
        }
        if (range < 86_400_000L) {
            return TIME.format(t);
        }
        if (range < 30L * 86_400_000L) {
            return DATE_TIME.format(t);
        }
        return DATE.format(t);
    }
}
