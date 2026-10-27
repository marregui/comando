package dev.marregui.comando.ui.kit.theme;

import java.awt.Color;

/**
 * Colours in OKLCH, Björn Ottosson's perceptual space: lightness 0 to 1, chroma from 0 (grey) up, hue in degrees.
 * Colours of one lightness and chroma read as one weight whatever their hue, which is what the content palettes need
 * (record 101). A colour outside sRGB loses chroma, never lightness or hue, until it fits.
 */
final class Oklch {

    private Oklch() {
    }

    /** The sRGB colour at lightness {@code l}, chroma {@code c} and hue {@code h} degrees, its chroma cut to fit sRGB. */
    static Color of(double l, double c, double h) {
        double rad = Math.toRadians(h);
        double lo = 0;
        double hi = c;
        double[] rgb = rgb(l, c * Math.cos(rad), c * Math.sin(rad));
        if (inGamut(rgb)) {
            return color(rgb);
        }
        for (int i = 0; i < 24; i++) {
            double mid = (lo + hi) / 2;
            if (inGamut(rgb(l, mid * Math.cos(rad), mid * Math.sin(rad)))) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return color(rgb(l, lo * Math.cos(rad), lo * Math.sin(rad)));
    }

    /** {@code c} as {@code {lightness, chroma, hue}}. */
    static double[] lch(Color c) {
        double[] lab = lab(c);
        double chroma = Math.hypot(lab[1], lab[2]);
        double hue = Math.toDegrees(Math.atan2(lab[2], lab[1]));
        return new double[]{lab[0], chroma, hue < 0 ? hue + 360 : hue};
    }

    /** The Euclidean distance between {@code a} and {@code b} in OKLab: 0.02 is a just-noticeable difference. */
    static double distance(Color a, Color b) {
        double[] x = lab(a);
        double[] y = lab(b);
        return Math.sqrt((x[0] - y[0]) * (x[0] - y[0]) + (x[1] - y[1]) * (x[1] - y[1]) + (x[2] - y[2]) * (x[2] - y[2]));
    }

    /** The angle between hues {@code a} and {@code b}, 0 to 180. */
    static double hueGap(double a, double b) {
        double d = Math.abs(a - b) % 360;
        return d > 180 ? 360 - d : d;
    }

    private static double[] lab(Color c) {
        double r = linear(c.getRed());
        double g = linear(c.getGreen());
        double b = linear(c.getBlue());
        double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
        double m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
        double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
        return new double[]{
                0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s};
    }

    private static double[] rgb(double lightness, double a, double b) {
        double l = Math.pow(lightness + 0.3963377774 * a + 0.2158037573 * b, 3);
        double m = Math.pow(lightness - 0.1055613458 * a - 0.0638541728 * b, 3);
        double s = Math.pow(lightness - 0.0894841775 * a - 1.2914855480 * b, 3);
        return new double[]{
                4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
                -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
                -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s};
    }

    private static boolean inGamut(double[] rgb) {
        for (double v : rgb) {
            if (v < -1e-4 || v > 1 + 1e-4) {
                return false;
            }
        }
        return true;
    }

    private static Color color(double[] rgb) {
        return new Color(gamma(rgb[0]), gamma(rgb[1]), gamma(rgb[2]));
    }

    private static double linear(int v) {
        double c = v / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static int gamma(double v) {
        double c = Math.clamp(v, 0, 1);
        double s = c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
        return (int) Math.round(s * 255);
    }
}
