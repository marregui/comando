package dev.marregui.comando.core.port;

import dev.marregui.comando.core.model.FolderRoot;

import java.util.List;

/**
 * Development tools run outside the JVM or read from disk: Gradle builds and the Files tab's folders. Calls return at
 * once; output goes to the run's console and results come back as intents.
 */
public interface ToolPort {

    /** Runs Gradle {@code tasks} in the build that contains {@code workDir}. */
    void runGradle(long runId, String workDir, String tasks);

    /** Stops a run and the processes it started. */
    void cancelRun(long runId);

    /** Frees a finished run's output. */
    void forgetRun(long runId);

    /** Reads {@code folders} once, now, each as a tree. */
    void readFolders(List<FolderRoot> folders);

    /** Reads {@code folders} every few seconds from now on, and stops reading any other. */
    void watchFolders(List<FolderRoot> folders);
}
