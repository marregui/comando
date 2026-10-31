package dev.marregui.comando.testkit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Recorded Claude Code stream-JSON, one event per line, from {@code fixtures/stream/<name>.jsonl}. */
public final class Fixtures {

    private Fixtures() {
    }

    public static List<String> stream(final String name) {
        final String path = "/fixtures/stream/" + name + ".jsonl";
        try (InputStream in = Fixtures.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("no fixture " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).toList();
        } catch (final IOException e) {
            throw new IllegalStateException("cannot read fixture " + path, e);
        }
    }
}
