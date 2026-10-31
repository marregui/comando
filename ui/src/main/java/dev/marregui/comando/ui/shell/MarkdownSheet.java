package dev.marregui.comando.ui.shell;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import dev.marregui.comando.ui.kit.format.KeyText;

/** Help ▸ Markdown sheet: what a panel's Markdown may hold and what its preview shows, beside the work. */
final class MarkdownSheet {

    private MarkdownSheet() {
    }

    /** The sheet's Markdown, its keys named as this desktop names them. */
    static String markdown() {
        try (InputStream in = MarkdownSheet.class.getResourceAsStream("markdown-sheet.md")) {
            if (in == null) {
                throw new IllegalStateException("markdown-sheet.md is missing from the build");
            }
            return KeyText.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read the Markdown sheet", e);
        }
    }
}
