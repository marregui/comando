package dev.marregui.comando.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.action.Actions;
import dev.marregui.comando.core.intent.Intent;

import static org.assertj.core.api.Assertions.assertThat;

/// A session has every action the user has (record 121): every kind of intent is classified once, and every intent the
/// window sends on a gesture is offered as a tool. A feature added to the window without its action fails here.
class ActionParityTest {

    /** {@code new Intent.X(} and {@code Intent.X::new} in the window's sources. */
    private static final Pattern SENT = Pattern.compile("(?:new\\s+Intent\\.(\\w+)\\s*\\(|Intent\\.(\\w+)::new)");

    /// Each kind of intent is in exactly one class: offered as a tool, an event, a window gesture with its reason, or
    /// not yet offered with its reason; an intent in none or in two fails.
    @Test
    void everyKindOfIntentIsClassifiedOnce() {
        final List<String> unclassified = new ArrayList<>();
        final List<String> twice = new ArrayList<>();
        for (final Class<?> c : Intent.class.getPermittedSubclasses()) {
            @SuppressWarnings("unchecked")
            final Class<? extends Intent> kind = (Class<? extends Intent>) c;
            final int places = (Actions.offered(kind) ? 1 : 0) + (Actions.EVENTS.contains(kind) ? 1 : 0)
                    + (Actions.WINDOW.containsKey(kind) ? 1 : 0) + (Actions.NOT_YET.containsKey(kind) ? 1 : 0);
            if (places == 0) {
                unclassified.add(c.getSimpleName());
            } else if (places > 1) {
                twice.add(c.getSimpleName());
            }
        }
        assertThat(unclassified).as("intents in no class: offer an action, or list them as an event, window or not yet").isEmpty();
        assertThat(twice).as("intents in two classes").isEmpty();
    }

    /// Scanning the window's sources for the intents it sends finds more than forty, and each is offered as a tool or
    /// listed as window-only or not yet offered, with its reason.
    @Test
    void everyIntentTheWindowSendsIsOfferedOrSaysWhyNot() throws IOException {
        final Set<String> sent = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("../ui/src/main/java"))) {
            for (final Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                final Matcher m = SENT.matcher(Files.readString(f));
                while (m.find()) {
                    sent.add(m.group(1) != null ? m.group(1) : m.group(2));
                }
            }
        }
        assertThat(sent).as("the scan found the window's intents").hasSizeGreaterThan(40);
        final List<String> missing = new ArrayList<>();
        for (final Class<?> c : Intent.class.getPermittedSubclasses()) {
            @SuppressWarnings("unchecked")
            final Class<? extends Intent> kind = (Class<? extends Intent>) c;
            if (sent.contains(c.getSimpleName()) && !Actions.offered(kind) && !Actions.WINDOW.containsKey(kind) && !Actions.NOT_YET.containsKey(kind)) {
                missing.add(c.getSimpleName());
            }
        }
        assertThat(missing).as("intents the window sends that no action offers").isEmpty();
    }
}
