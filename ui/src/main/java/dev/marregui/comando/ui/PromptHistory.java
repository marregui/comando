package dev.marregui.comando.ui;

import java.util.List;

/** The prompts sent to each session, kept across runs of comando so ↑ in the prompt bar brings them back. */
public interface PromptHistory {

    /** Prompts sent to {@code session}, oldest first. */
    List<String> load(String session);

    /** {@code prompt} was sent to {@code session}; the store keeps the most recent ones. */
    void add(String session, String prompt);

    /** A history that remembers nothing: tests and tools. */
    PromptHistory NONE = new PromptHistory() {
        @Override
        public List<String> load(String session) {
            return List.of();
        }

        @Override
        public void add(String session, String prompt) {
            // nothing kept
        }
    };
}
