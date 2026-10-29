package dev.marregui.comando.ui.kit.format;

import dev.marregui.comando.ui.kit.desktop.Platform;

import java.util.ArrayList;
import java.util.List;

/**
 * Keys named in text, as the desktop names them. comando's texts write keys as macOS does ("⌘⇧T", "⌃2", "⌥⏎");
 * with the other desktops' keys ({@link Platform#macKeys()} false) each chord reads as there: ⌘ is Ctrl, ⌥ Alt,
 * ⇧ Shift, modifiers in the order Ctrl, Alt, Shift, then the key ("Ctrl+Shift+T"). macOS's ⌃ keys move where Ctrl
 * would make them another command's: ⌃ with a digit (the lower tabs) is Alt, ⌃⏎ (the line to the terminal) is
 * Ctrl+Alt+⏎; any other ⌃ is Ctrl.
 */
public final class KeyText {

    private static final String MODIFIERS = "⌃⌥⇧⌘";

    private KeyText() {
    }

    /** {@code text} with its keys named as this desktop names them; unchanged with macOS's keys. */
    public static String of(String text) {
        return text == null || Platform.macKeys() ? text : other(text);
    }

    /** {@code text} with its keys named as the desktops other than macOS name them. */
    static String other(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (MODIFIERS.indexOf(c) < 0) {
                out.append(c);
                i++;
                continue;
            }
            int start = i;
            while (i < text.length() && MODIFIERS.indexOf(text.charAt(i)) >= 0) {
                i++;
            }
            String mods = text.substring(start, i);
            String key = i < text.length() && isKey(text.charAt(i)) ? String.valueOf(text.charAt(i)) : "";
            i += key.length();
            out.append(chord(mods, key));
        }
        return out.toString();
    }

    private static boolean isKey(char c) {
        return !Character.isWhitespace(c) && c != ',' && c != ';' && c != ')' && c != '(' && c != '|' && c != '–' && c != '…';
    }

    private static String chord(String mods, String key) {
        boolean control = mods.indexOf('⌃') >= 0;
        boolean ctrl = mods.indexOf('⌘') >= 0;
        boolean alt = mods.indexOf('⌥') >= 0;
        boolean shift = mods.indexOf('⇧') >= 0;
        if (control) {
            boolean digit = key.length() == 1 && Character.isDigit(key.charAt(0));
            boolean enter = key.equals("⏎") && !ctrl && !alt && !shift;
            if (digit) {
                alt = true; // the lower tabs: Ctrl with a digit is a panel
            } else {
                ctrl = true;
                alt |= enter; // the line to the terminal: Ctrl+⏎ is Run, Alt+⏎ Run as SQL
            }
        }
        List<String> parts = new ArrayList<>(4);
        if (ctrl) {
            parts.add("Ctrl");
        }
        if (alt) {
            parts.add("Alt");
        }
        if (shift) {
            parts.add("Shift");
        }
        if (!key.isEmpty()) {
            parts.add(name(key));
        }
        return String.join("+", parts);
    }

    /** A key as a chord on the other desktops names it: Enter, Tab, Esc, Backspace, else itself. */
    public static String name(String key) {
        return switch (key) {
            case "⏎" -> "Enter";
            case "⇥" -> "Tab";
            case "⎋" -> "Esc";
            case "⌫" -> "Backspace";
            default -> key;
        };
    }
}
