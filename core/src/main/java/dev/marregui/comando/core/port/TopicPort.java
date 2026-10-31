package dev.marregui.comando.core.port;

import java.util.function.Consumer;

import dev.marregui.comando.core.model.Topic;

/**
 * Reads a panel's topic: one line on what the panel is about, from a text that describes it. A topic is derived view
 * data, like a drawn diagram, so it goes from the reader to the view that asked and never through the state.
 */
public interface TopicPort {

    /**
     * Asks for the topic of {@code key} from {@code source}, and returns at once. Asks wait in one queue and are read
     * one at a time; an ask for a key already waiting replaces it, its source and its {@code done}, so the queue never
     * holds more asks than there are keys. An {@code urgent} ask goes first. {@code done} is called once per ask read,
     * on the reader's thread, and not at all for an ask replaced or dropped at close.
     */
    void read(String key, String source, boolean urgent, Consumer<Topic> done);

    /** A reader that has none to read with: every ask fails at once, on the caller's thread. */
    TopicPort NONE = (key, source, urgent, done) -> done.accept(Topic.failed("no topic reader", System.currentTimeMillis()));
}
