package dev.marregui.comando.frontear.store;

import java.io.IOException;
import java.io.Serial;
import java.nio.file.Path;

/**
 * An append refused because the log's retained bytes would pass {@link LogOptions#maxBytes()}, or the frames waiting
 * for the forcer {@link LogOptions#maxRoundBytes()}. Nothing was appended: the append can be tried again once
 * acknowledged segments are deleted, or once the forcer has written and forced the round.
 * <p>
 * A log refuses for as long as its forcer is behind, once per message offered, so it keeps one instance and builds
 * its message again on each refusal: the instance has no stack trace, its message is the last refusal's and is read
 * before the log's next one, and it never carries a suppressed exception or a cause, which would stay on every later
 * refusal of the log.
 */
public final class QuotaExceededException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String dir;
    // guarded by this: written by a refusal under the log's lock, read by whoever reads the message
    private final StringBuilder message = new StringBuilder();

    QuotaExceededException(final Path dir) {
        this.dir = dir.toString();
    }

    @Override
    public synchronized String getMessage() {
        return message.toString();
    }

    /** No stack trace: the log's one instance is thrown for every refusal, from wherever the append came. */
    @Override
    public Throwable fillInStackTrace() {
        return this;
    }

    // a refusal past the log's quota
    synchronized QuotaExceededException pastQuota(final long bytes, final long need, final long maxBytes) {
        message.setLength(0);
        message.append("refuse append past the log's quota [dir=").append(dir).append(", bytes=").append(bytes)
                .append(", need=").append(need).append(", maxBytes=").append(maxBytes).append(']');
        return this;
    }

    // a refusal past the round the forcer has not yet taken
    synchronized QuotaExceededException pastRound(final long roundBytes, final long need, final long maxRoundBytes) {
        message.setLength(0);
        message.append("refuse append past the log's round, the forcer is behind [dir=").append(dir)
                .append(", roundBytes=").append(roundBytes).append(", need=").append(need).append(", maxRoundBytes=")
                .append(maxRoundBytes).append(']');
        return this;
    }
}
