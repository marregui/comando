package dev.marregui.comando.core.diag;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** One intent through the reducer: the transition, its effects handed to the adapters, the listeners. */
@Name("comando.Intent")
@Label("Intent")
@Category("comando")
@Description("An intent applied on the reducer thread, with its effects and state listeners")
@StackTrace(false)
public final class IntentEvent extends Event {

    @Label("Type")
    public String type;

    @Label("Effects")
    public int effects;

    @Label("State changed")
    public boolean changed;
}
