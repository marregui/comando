package dev.marregui.comando.core.port;

import dev.marregui.comando.core.model.ConnectionSpec;

/** The database adapter. Every call returns at once; outcomes come back as intents. */
public interface DatabasePort {

    void open(ConnectionSpec spec);

    void close(String name);

    void test(ConnectionSpec spec);

    /** Runs {@code sql}, opening the connection first when needed; rows go to the result registry. */
    void execute(long requestId, ConnectionSpec spec, String sql);

    /** Stops the run: a queued run never starts, a running one gets {@code Statement.cancel()}. */
    void cancel(long requestId);

    void release(long requestId);

    /** Reads the tables and columns into the schema registry; {@code show} also opens them as a tree. */
    void loadSchema(ConnectionSpec spec, boolean show);
}
