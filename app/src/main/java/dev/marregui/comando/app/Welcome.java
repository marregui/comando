package dev.marregui.comando.app;

/** The text of the first panel on a fresh data root. */
final class Welcome {

    static final String TEXT = """
            -- Welcome to comando.
            -- A block is a run of lines between blank lines. Put the caret in a block and press ⌘⏎:
            -- SQL goes to the panel's connection, anything else to the panel's Claude session.
            -- ⌘⇧⏎ forces Claude, ⌥⏎ forces SQL, ⌘. stops. ⌘⇧P lists every command.

            SELECT now(), version();

            Explain what the query above returns, in one sentence.
            """;

    private Welcome() {
    }
}
