package dev.marregui.comando.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a command line the way a POSIX shell splits words, without expansion: whitespace separates words,
 * single quotes keep everything literally, double quotes keep whitespace and let a backslash escape
 * {@code "} and {@code \\}, and a backslash outside quotes escapes the next character. So
 * {@code --tests 'com.x.FooTest'} and {@code --thread 'C2 CompilerThread*'} reach the tool as typed.
 */
final class Args {

    private Args() {
    }

    static List<String> split(final String line) {
        final List<String> out = new ArrayList<>();
        final StringBuilder word = new StringBuilder();
        boolean inWord = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            final char c = line.charAt(i);
            if (quote == '\'') {
                if (c == '\'') {
                    quote = 0;
                } else {
                    word.append(c);
                }
            } else if (quote == '"') {
                if (c == '"') {
                    quote = 0;
                } else if (c == '\\' && i + 1 < line.length() && (line.charAt(i + 1) == '"' || line.charAt(i + 1) == '\\')) {
                    word.append(line.charAt(++i));
                } else {
                    word.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                inWord = true;
            } else if (c == '\\' && i + 1 < line.length()) {
                word.append(line.charAt(++i));
                inWord = true;
            } else if (Character.isWhitespace(c)) {
                if (inWord) {
                    out.add(word.toString());
                    word.setLength(0);
                    inWord = false;
                }
            } else {
                word.append(c);
                inWord = true;
            }
        }
        if (quote != 0) {
            throw new IllegalArgumentException("unclosed " + quote + " in: " + line);
        }
        if (inWord) {
            out.add(word.toString());
        }
        return out;
    }
}
