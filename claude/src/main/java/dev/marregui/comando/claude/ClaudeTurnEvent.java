package dev.marregui.comando.claude;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Timespan;

/** A Claude turn ended: the {@code result} event, with the duration Claude Code reports. */
@Name("comando.ClaudeTurn")
@Label("Claude turn")
@Category("comando")
@Description("A turn of a Claude Code session ended with a result event")
@StackTrace(false)
final class ClaudeTurnEvent extends Event {

    @Label("Session")
    String session;

    @Label("Turn duration")
    @Timespan(Timespan.MILLISECONDS)
    long turnMillis;

    @Label("Conversation cost (USD)")
    double costUsd;

    @Label("Context tokens")
    long contextTokens;

    @Label("Error")
    boolean error;
}
