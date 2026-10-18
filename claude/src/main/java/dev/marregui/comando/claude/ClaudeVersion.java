package dev.marregui.comando.claude;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Claude Code version comando was tested against, and a check of the installed one. The stream
 * format is not versioned, so a version outside the tested line gets a warning, not a refusal; features
 * are detected from the {@code capabilities} of the {@code system/init} event.
 */
public record ClaudeVersion(int major, int minor, int patch) implements Comparable<ClaudeVersion> {

    /** The oldest version the stream fixtures were recorded with. */
    public static final ClaudeVersion TESTED_FROM = new ClaudeVersion(2, 1, 296);
    /** The first version outside the tested line. */
    public static final ClaudeVersion TESTED_UNTIL = new ClaudeVersion(2, 2, 0);

    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");

    public static Optional<ClaudeVersion> parse(String text) {
        Matcher m = VERSION.matcher(text == null ? "" : text);
        return m.find()
                ? Optional.of(new ClaudeVersion(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))))
                : Optional.empty();
    }

    public boolean tested() {
        return compareTo(TESTED_FROM) >= 0 && compareTo(TESTED_UNTIL) < 0;
    }

    @Override
    public int compareTo(ClaudeVersion o) {
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
     * Blocks up to six seconds: five for the CLI to exit, one more for its output; call it off the EDT and off the
     * reducer thread.
     */
    public static String check(String executable) {
        Process p;
        try {
            p = new ProcessBuilder(executable, "--version").redirectErrorStream(true).start();
        } catch (IOException e) {
            return "Claude Code not found at '" + executable + "'. Install it, or set COMANDO_CLAUDE to its path.";
        }
        try (InputStream in = p.getInputStream()) {
            p.getOutputStream().close();
            // read on a thread of its own: a child the CLI left running can hold the output open after the exit
            StringBuffer read = new StringBuffer();
            Thread reader = Thread.ofVirtual().name("claude-version").start(() -> {
                try (BufferedReader lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                        read.append(line).append('\n');
                    }
                } catch (IOException e) {
                    // closed while reading: what was read stands
                }
            });
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "'" + executable + " --version' did not answer within 5 s.";
            }
            // what the CLI wrote before it exited is read at once; a pipe still held open is not waited for
            reader.join(Duration.ofSeconds(1));
            String out = read.toString().strip();
            return parse(out)
                    .map(v -> v.tested() ? "" : "Claude Code " + v + " is outside the tested range " + TESTED_FROM + " to "
                            + TESTED_UNTIL + " (exclusive); the transcript may miss events.")
                    .orElse("Cannot read the Claude Code version from: " + out);
        } catch (IOException e) {
            return "Cannot read the Claude Code version: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }
}
