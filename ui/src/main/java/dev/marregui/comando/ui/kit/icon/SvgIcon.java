package dev.marregui.comando.ui.kit.icon;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.RGBImageFilter;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import javax.swing.Icon;
import javax.swing.UIManager;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.util.Graphics2DProxy;
import com.formdev.flatlaf.util.GrayFilter;
import com.formdev.flatlaf.util.UIScale;
import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.view.FloatSize;
import org.jetbrains.annotations.TestOnly;

import dev.marregui.comando.ui.kit.theme.Theme;

/**
 * An SVG on the classpath as an icon of a fixed size, scaled by FlatLaf's user scale, each colour it paints passed
 * through a filter at every paint: {@link Icons#gilded} for the icon set, a blend for {@link BlendIcon}. The SVG is
 * drawn into a picture at the scale of the graphics it is painted on (2 on a Retina screen), and that picture is
 * painted again while the scale, the size and the filtered colours stay what they were; another scale, another tint or
 * another theme draws it anew. Its disabled twin greys each filtered colour as FlatLaf greys a disabled icon (the
 * theme's {@code Component.grayFilter}, else FlatLaf's own for a light or a dark theme). The document is read once per
 * path, on first use, and shared by every icon of that path; a path that holds no SVG paints a square in the theme's
 * danger colour. Paintable from any thread: each picture is one immutable value, replaced whole, one per scale.
 */
final class SvgIcon implements Icon, FlatLaf.DisabledIconProvider {

    private static final System.Logger LOG = System.getLogger(SvgIcon.class.getName());
    /** The documents read, by classpath path; empty for a path that holds no SVG jsvg can read. */
    private static final ConcurrentHashMap<String, Optional<SVGDocument>> DOCUMENTS = new ConcurrentHashMap<>();

    private final boolean disabled;
    private final UnaryOperator<Color> filter;
    private final int height;
    private final String path;
    private final int width;
    /**
     * The last picture drawn at each scale (one per screen scale the icon has been painted at, 1 and 2 on a Mac with a
     * Retina and an external screen). Written and read by whichever thread paints (the EDT in the application, a test's
     * own thread); the map publishes a picture after it is drawn whole, and two threads that paint at once each draw
     * their own and the last one stays.
     */
    private final ConcurrentHashMap<Scale, Picture> drawn = new ConcurrentHashMap<>();

    /** {@code path}'s SVG at {@code width} by {@code height} points, each colour through {@code filter} at each paint. */
    SvgIcon(final String path, final int width, final int height, final UnaryOperator<Color> filter) {
        this(path, width, height, filter, false);
    }

    private SvgIcon(
            final String path,
            final int width,
            final int height,
            final UnaryOperator<Color> filter,
            final boolean disabled) {
        this.path = path;
        this.width = width;
        this.height = height;
        this.filter = filter;
        this.disabled = disabled;
    }

    /** This icon greyed, through the same filter first; the disabled icon itself. */
    @Override
    public Icon getDisabledIcon() {
        return disabled ? this : new SvgIcon(path, width, height, filter, true);
    }

    @Override
    public int getIconHeight() {
        return UIScale.scale(height);
    }

    @Override
    public int getIconWidth() {
        return UIScale.scale(width);
    }

    @Override
    public void paintIcon(final Component c, final Graphics g, final int x, final int y) {
        final int w = getIconWidth();
        final int h = getIconHeight();
        final Rectangle clip = g.getClipBounds();
        if (clip != null && !clip.intersects(x, y, w, h)) {
            return;
        }
        final Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.translate(x, y);
            final Optional<SVGDocument> document = document(path);
            final AffineTransform t = g2.getTransform();
            if (document.isEmpty()) {
                g2.setColor(Theme.DANGER);
                g2.fillRect(0, 0, w, h);
            } else if (t.getShearX() != 0 || t.getShearY() != 0 || t.getScaleX() <= 0 || t.getScaleY() <= 0) {
                // turned or mirrored, a picture's pixels would not meet the target's: drawn as shapes, nothing kept
                draw(document.get(), new Filtering(g2, shade(), new LinkedHashMap<>()), w, h);
            } else {
                final Picture p = picture(document.get(), t.getScaleX(), t.getScaleY(), w, h);
                g2.scale(1 / p.scaleX(), 1 / p.scaleY());
                g2.drawImage(p.image(), 0, 0, null);
            }
        } finally {
            g2.dispose();
        }
    }

    /** Whether the path holds an SVG this icon draws; when not, it paints a square in the danger colour. */
    @TestOnly
    boolean hasFound() {
        return document(path).isPresent();
    }

    /** How many pictures this icon keeps, one per scale it has been painted at. */
    @TestOnly
    int pictures() {
        return drawn.size();
    }

    private static Optional<SVGDocument> document(final String path) {
        return DOCUMENTS.computeIfAbsent(path, SvgIcon::read);
    }

    private static Optional<SVGDocument> read(final String path) {
        final URL url = SvgIcon.class.getClassLoader().getResource(path);
        final SVGDocument document = url == null ? null : new SVGLoader().load(url);
        if (document == null) {
            LOG.log(System.Logger.Level.WARNING, "SVG icon not found [path=" + path + ']');
        }
        return Optional.ofNullable(document);
    }

    /**
     * The picture to paint at that scale and size: the last one while it still fits, else one drawn now, of
     * {@code w × scaleX} by {@code h × scaleY} pixels.
     */
    private Picture picture(
            final SVGDocument document,
            final double scaleX,
            final double scaleY,
            final int w,
            final int h) {
        final UnaryOperator<Color> shade = shade();
        final Scale scale = new Scale(scaleX, scaleY);
        final Picture last = drawn.get(scale);
        if (last != null && last.fits(scaleX, scaleY, w, h, shade)) {
            return last;
        }
        final BufferedImage image = new BufferedImage(Math.max(1, (int) Math.ceil(w * scaleX)),
                Math.max(1, (int) Math.ceil(h * scaleY)), BufferedImage.TYPE_INT_ARGB);
        final Map<Color, Color> seen = new LinkedHashMap<>();
        final Filtering g = new Filtering(image.createGraphics(), shade, seen);
        try {
            g.scale(scaleX, scaleY);
            draw(document, g, w, h);
        } finally {
            g.dispose();
        }
        final Picture p = new Picture(scaleX, scaleY, w, h, List.copyOf(seen.keySet()), List.copyOf(seen.values()),
                image);
        drawn.put(scale, p);
        return p;
    }

    /** The document drawn at the origin, clipped to {@code w} by {@code h} and fitted to the icon's points. */
    private void draw(final SVGDocument document, final Filtering g, final int w, final int h) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.clipRect(0, 0, w, h);
        UIScale.scaleGraphics(g);
        final FloatSize size = document.size();
        g.scale(width / (double) size.width, height / (double) size.height);
        try {
            document.render(null, g);
        } catch (final RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot draw the SVG icon [path=" + path + ']', e);
            g.setColorAsIs(Theme.DANGER);
            g.fill(new Rectangle(0, 0, (int) Math.ceil(size.width), (int) Math.ceil(size.height)));
        }
    }

    /** The filter, and for the disabled twin the grey FlatLaf would paint the filtered colour in, read now. */
    private UnaryOperator<Color> shade() {
        if (!disabled) {
            return filter;
        }
        final RGBImageFilter grey = UIManager.get("Component.grayFilter") instanceof final RGBImageFilter f
                ? f
                : GrayFilter.createDisabledIconFilter(FlatLaf.isLafDark());
        return c -> {
            final Color filtered = filter.apply(c);
            final int rgb = filtered.getRGB();
            final int greyed = grey.filterRGB(0, 0, rgb);
            return greyed == rgb ? filtered : Theme.argb(greyed);
        };
    }

    /** The scale a picture is drawn at, one key per screen scale. */
    private record Scale(double x, double y) {
    }

    /**
     * A drawing at one scale and size, with the colours the SVG set while it was drawn and what the shade made of each,
     * in the order they were first set.
     */
    private record Picture(
            double scaleX,
            double scaleY,
            int width,
            int height,
            List<Color> asked,
            List<Color> shaded,
            BufferedImage image) {

        /** Whether drawing at that scale and size through {@code shade} would paint this drawing again. */
        boolean fits(final double sx, final double sy, final int w, final int h, final UnaryOperator<Color> shade) {
            if (scaleX != sx || scaleY != sy || width != w || height != h) {
                return false;
            }
            for (int i = 0; i < asked.size(); i++) {
                if (!shade.apply(asked.get(i)).equals(shaded.get(i))) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Graphics that pass every colour set on them through a shade, noting each colour set and what it became; the
     * graphics they create do the same into the same notes. Used by one thread, for one drawing.
     */
    private static final class Filtering extends Graphics2DProxy {

        private final Map<Color, Color> seen;
        private final UnaryOperator<Color> shade;

        Filtering(final Graphics2D delegate, final UnaryOperator<Color> shade, final Map<Color, Color> seen) {
            super(delegate);
            this.shade = shade;
            this.seen = seen;
        }

        @Override
        public Graphics create() {
            return new Filtering((Graphics2D) super.create(), shade, seen);
        }

        @Override
        public Graphics create(final int x, final int y, final int width, final int height) {
            return new Filtering((Graphics2D) super.create(x, y, width, height), shade, seen);
        }

        @Override
        public void setColor(final Color c) {
            super.setColor(c == null ? null : shaded(c));
        }

        @Override
        public void setPaint(final Paint paint) {
            super.setPaint(paint instanceof final Color c ? shaded(c) : paint);
        }

        /** {@code c} as it is: the red of a drawing that failed, which no theme or grey changes. */
        void setColorAsIs(final Color c) {
            super.setColor(c);
        }

        private Color shaded(final Color c) {
            return seen.computeIfAbsent(c, shade);
        }
    }
}
