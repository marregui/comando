package dev.marregui.comando.claude;


import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.marregui.comando.core.loop.Processes;

/**
 * The Claude Code versions comando is tested against, and a check of the installed one. The stream format is not
 * versioned, so a version outside the tested range gets a warning, not a refusal.
 */
public record ClaudeVersion(int major, int minor, int patch) implements Comparable<ClaudeVersion> {

    /** The oldest version the stream fixtures were recorded with. */
    public static final ClaudeVersion TESTED_FROM = new ClaudeVersion(2, 1, 296);
    /** The first version after the tested range. */
    public static final ClaudeVersion TESTED_UNTIL = new ClaudeVersion(2, 2, 0);

    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");

    public static Optional<ClaudeVersion> parse(final String text) {
        final Matcher m = VERSION.matcher(text == null ? "" : text);
        return m.find()
                ? Optional.of(new ClaudeVersion(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))))
                : Optional.empty();
    }

    public boolean tested() {
        return compareTo(TESTED_FROM) >= 0 && compareTo(TESTED_UNTIL) < 0;
    }

    @Override
    public int compareTo(final ClaudeVersion o) {
        int c = Integer.compare(major, o.major);
        if (c == 0) {
            c = Integer.compare(minor, o.minor);
        }
        return c == 0 ? Integer.compare(patch, o.patch) : c;
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }

    /**
     * Runs {@code <executable> --version}; the result is a message for the user, empty when the version is tested.
     * Blocks up to six seconds: five for the CLI to exit, one for it to be gone once ended; call it off the EDT and off
     * the reducer thread.
     */
    public static String check(final String executable, final RootLaunch launch) {
        Processes.Result r;
        try {
            r = Processes.run(List.of(executable, "--version"), launch::environment, null, null, TIMEOUT, MAX_OUT);
        } catch (final IOException e) {
            return "Claude Code not found at '" + executable + "'. Install it, or set COMANDO_CLAUDE to its path.";
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
        if (r.timedOut()) {
            return "'" + executable + " --version' did not answer within 5 s.";
        }
        // the version is on standard output; an error the CLI printed instead is shown with it
        final String out = (r.out() + r.stderr()).strip();
        return parse(out)
                .map(v -> v.tested() ? "" : "Claude Code " + v + " is outside the tested range " + TESTED_FROM + " to "
                        + TESTED_UNTIL + " (exclusive); the transcript may miss events.")
                .orElse("Cannot read the Claude Code version from: " + out);
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_OUT = 64 * 1024;
}
