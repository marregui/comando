package dev.marregui.comando.core.port;

/**
 * Development tools run outside the JVM or read from disk: Gradle builds, jfrq on a recording, JFR
 * summaries, the scratchpad. Calls return at once; output goes to the run's console and results come
 * back as intents.
 */
public interface ToolPort {

    /** Runs Gradle {@code tasks} in the build that contains {@code workDir}. */
    void runGradle(long runId, String workDir, String tasks);

    /** Runs jfrq with {@code args} (a command and its options) on {@code recording}. */
    void runJfrq(long runId, String recording, String args);

    /** Stops a run and the processes it started. */
    void cancelRun(long runId);

    /** Frees a finished run's output. */
    void forgetRun(long runId);

    /** Reads a JFR recording and shows its event summary and its CPU and heap series. */
    void openRecording(String path);

    /** Shows the scratchpad's day folders and documents as a tree. */
    void browseScratchpad();
}
