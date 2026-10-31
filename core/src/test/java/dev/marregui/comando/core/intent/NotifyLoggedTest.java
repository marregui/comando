package dev.marregui.comando.core.intent;

import java.io.IOException;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.state.Notice;

import static org.assertj.core.api.Assertions.assertThat;

/// [Intent.Notify#logged] records a failure and returns a non-desktop notice linked to the log entry time.
///
/// - errors use the error logger level
/// - warnings use the warning logger level
/// - the notice subject names the recorded entry
class NotifyLoggedTest {

    /** One entry: its level, message and cause. */
    private record Entry(System.Logger.Level level, String message, Throwable cause, long atMillis) {
    }

    private static final class Recording implements System.Logger {
        final List<Entry> entries = new CopyOnWriteArrayList<>();

        @Override
        public String getName() {
            return "test";
        }

        @Override
        public boolean isLoggable(final Level level) {
            return true;
        }

        @Override
        public void log(final Level level, final ResourceBundle bundle, final String msg, final Throwable thrown) {
            entries.add(new Entry(level, msg, thrown, System.currentTimeMillis()));
        }

        @Override
        public void log(final Level level, final ResourceBundle bundle, final String format, final Object... params) {
            entries.add(new Entry(level, format, null, System.currentTimeMillis()));
        }
    }

    /// Logged errors retain their cause and get a `log:` subject from the timestamp read before logging; warnings
    /// record without a cause.
    @Test
    void aLoggedFailureNamesItsLogEntryWhichHoldsTheNoticeAndItsCause() {
        final Recording log = new Recording();
        final IOException cause = new IOException("Permission denied");
        final Intent.Notify n = Intent.Notify.logged(log, Notice.Level.ERROR, "Cannot export to /r/x.csv: Permission denied", cause);
        assertThat(log.entries).hasSize(1);
        final Entry e = log.entries.getFirst();
        assertThat(e.level()).isEqualTo(System.Logger.Level.ERROR);
        assertThat(e.message()).isEqualTo(n.text());
        assertThat(e.cause()).isSameAs(cause);
        assertThat(n.desktop()).isFalse();
        final Notice notice = new Notice(n.level(), n.text(), 1L, n.subject());
        assertThat(notice.kind()).isEqualTo("log");
        assertThat(Long.parseLong(notice.key())).as("read before the entry").isLessThanOrEqualTo(e.atMillis())
                .isGreaterThan(e.atMillis() - 1_000);

        final Intent.Notify warn = Intent.Notify.logged(log, Notice.Level.WARN, "Cannot save the panels: disk full", null);
        assertThat(log.entries.get(1).level()).isEqualTo(System.Logger.Level.WARNING);
        assertThat(log.entries.get(1).cause()).isNull();
        assertThat(warn.subject()).startsWith("log:");
    }
}
