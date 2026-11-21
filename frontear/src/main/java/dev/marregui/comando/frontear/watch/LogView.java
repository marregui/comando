package dev.marregui.comando.frontear.watch;

/**
 * Link {@code link}'s log: the next offset to deliver ({@code cursor}), the next an append takes ({@code tail}), the
 * bytes it retains on disk and the segment files holding them.
 */
public record LogView(String link, long cursor, long tail, long bytes, int segments) {
}
