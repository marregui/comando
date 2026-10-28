package dev.marregui.comando.core.model;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The kit's scheduled jobs as {@code kit.py jobs --json} last reported them (record 117), or why they could not be read.
 *
 * @param read  whether the root has a kit whose jobs were asked for; false hides the Files tab's Jobs row
 * @param error why {@code kit.py} could not be asked or answered; empty when it answered
 */
public record KitJobs(boolean read, List<Entry> jobs, String error) {

    /** No kit: no jobs, no row. */
    public static final KitJobs NONE = new KitJobs(false, List.of(), "");

    /**
     * One job and how the scheduler holds it.
     *
     * @param installed whether the scheduler's files are there
     * @param current   whether they are what {@code kit.py apply} writes for the job now
     * @param loaded    whether the scheduler has the job; null when {@code kit.py} could not tell (a dry scheduler)
     * @param running   whether its runner runs now
     * @param lastStart when its last run started, ISO; empty when it has not run since the runner kept a record
     * @param lastExit  its last run's exit code; null when none
     * @param next      when it runs next, ISO; empty when paused
     * @param logPath   the file its output goes to, absolute
     */
    public record Entry(KitJob job, boolean installed, boolean current, Boolean loaded, boolean running, String lastStart,
                        Integer lastExit, String next, String logPath) {

        public Entry {
            Objects.requireNonNull(job, "job");
            lastStart = lastStart == null ? "" : lastStart;
            next = next == null ? "" : next;
            logPath = logPath == null ? "" : logPath;
        }

        /**
         * The scheduler not as the manifest says: an enabled job's files missing, out of date or not loaded; a paused
         * job still installed or loaded, as when it was paused while it ran.
         */
        public boolean pending() {
            return job.enabled() ? !installed || !current || Boolean.FALSE.equals(loaded) : installed || Boolean.TRUE.equals(loaded);
        }

        /** An enabled job whose last run ended with an exit code other than 0. */
        public boolean failed() {
            return job.enabled() && lastExit != null && lastExit != 0;
        }
    }

    public KitJobs {
        jobs = List.copyOf(jobs);
        error = error == null ? "" : error;
    }

    /** The jobs as defined, in the manifest's order. */
    public List<KitJob> definitions() {
        return jobs.stream().map(Entry::job).toList();
    }

    /** Whether any job waits for the scheduler to follow the manifest. */
    public boolean pending() {
        return jobs.stream().anyMatch(Entry::pending);
    }

    /** The names of the jobs whose last run failed. */
    public List<String> failed() {
        return jobs.stream().filter(Entry::failed).map(e -> e.job().name()).toList();
    }

    /** The names of the jobs running now. */
    public List<String> running() {
        return jobs.stream().filter(Entry::running).map(e -> e.job().name()).toList();
    }

    /** The names of the paused jobs. */
    public List<String> paused() {
        return jobs.stream().filter(e -> !e.job().enabled()).map(e -> e.job().name()).toList();
    }

    /** The enabled job that runs next, if any. */
    public Optional<Entry> nextUp() {
        return jobs.stream().filter(e -> e.job().enabled() && !e.next().isEmpty()).min(Comparator.comparing(Entry::next));
    }
}
