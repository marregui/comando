package dev.marregui.comando.db;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** One SQL run on a connection's lane, from dequeue to its last row or its error. */
@Name("comando.SqlRun")
@Label("SQL run")
@Category("comando")
@Description("A statement run on a connection lane: execute and fetch")
@StackTrace(false)
final class SqlRunEvent extends Event {

    @Label("Connection")
    String connection;

    @Label("Request")
    long requestId;

    @Label("SQL")
    String sql;

    @Label("Outcome")
    String outcome;
}
