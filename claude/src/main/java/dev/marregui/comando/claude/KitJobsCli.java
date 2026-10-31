package dev.marregui.comando.claude;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import dev.marregui.comando.core.loop.Processes;
import dev.marregui.comando.core.model.KitJob;
import dev.marregui.comando.core.model.KitJobs;

/**
 * The kit's scheduled jobs through the root's {@code install/kit.py} (record 117), which alone writes
 * {@code comando-kit.json}'s jobs and talks to the scheduler: {@link #read} asks {@code jobs --json}, {@link #save}
 * hands it the whole list ({@code jobs set}), {@link #run} starts one through the scheduler ({@code job run}), so a job
 * never runs as a child of comando, whose {@link Processes} would end it at its limit. Each call blocks up to
 * {@link #LIMIT}: off the EDT.
 */
public final class KitJobsCli {

    /** How long one call of {@code kit.py} may take: a reload waits a second per job at most. */
    static final Duration LIMIT = Duration.ofSeconds(60);
    private static final int MAX_OUT = 1 << 20;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path root;
    private final List<String> python;

    /**
     * What {@code kit.py} answered.
     *
     * @param exit 0 done; 1 done in part (a running job left as it was, a load that failed); 2 refused, nothing
     *             written; 3 an unexpected error, part may be done
     * @param text what it printed, standard output then standard error
     */
    public record Result(int exit, String text) {
    }

    /** The jobs of the kit at {@code root}, through {@code python3} ({@code python} on Windows) on the PATH. */
    public KitJobsCli(final Path root) {
        this(root, List.of(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows") ? "python" : "python3"));
    }

    KitJobsCli(final Path root, final List<String> python) {
        this.root = root;
        this.python = List.copyOf(python);
    }

    /** The installer that owns the jobs. */
    public Path script() {
        return root.resolve("install").resolve("kit.py");
    }

    /** The jobs as {@code kit.py} reports them, or a {@link KitJobs} with the reason it gave no list. */
    public KitJobs read() {
        if (!Files.isRegularFile(script())) {
            return new KitJobs(true, List.of(), "the kit has no install/kit.py");
        }
        try {
            final Processes.Result r = call(List.of("jobs", "--json"), null);
            if (r.exitCode() != 0) {
                return new KitJobs(true, List.of(), firstLine(r.stderr().isBlank() ? r.out() : r.stderr(), "kit.py jobs exited " + r.exitCode()));
            }
            return parse(r.out());
        } catch (final IOException e) {
            return new KitJobs(true, List.of(), e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /** Hands {@code jobs}, the whole list, to {@code kit.py jobs set}: checked, written to the manifest, reconciled. */
    public Result save(final List<KitJob> jobs) throws IOException {
        final byte[] in = JSON.writeValueAsBytes(manifestJobs(jobs));
        return result(call(List.of("jobs", "set"), in));
    }

    /** Starts job {@code name} now through the scheduler. */
    public Result run(final String name) throws IOException {
        return result(call(List.of("job", "run", name), null));
    }

    private Processes.Result call(final List<String> args, final byte[] stdin) throws IOException {
        final List<String> argv = new ArrayList<>(python);
        argv.add(script().toString());
        argv.addAll(args);
        argv.add("--root");
        argv.add(root.toString());
        try {
            final Processes.Result r = Processes.run(argv, null, root, stdin, LIMIT, MAX_OUT);
            if (r.timedOut()) {
                throw new IOException("kit.py " + args.getFirst() + " took longer than " + LIMIT.toSeconds()
                        + " s and was ended; it may have done part of it: the jobs are read again");
            }
            return r;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while kit.py ran", e);
        }
    }

    private static Result result(final Processes.Result r) {
        final String text = (r.out().strip() + "\n" + r.stderr().strip()).strip();
        return new Result(r.exitCode(), text);
    }

    private static String firstLine(final String text, final String otherwise) {
        final String t = text.strip();
        if (t.isEmpty()) {
            return otherwise;
        }
        final int nl = t.indexOf('\n');
        return nl < 0 ? t : t.substring(0, nl);
    }

    /** {@code kit.py jobs --json} read. */
    static KitJobs parse(final String json) throws IOException {
        JsonNode doc;
        try {
            doc = JSON.readTree(json);
        } catch (final JacksonException e) {
            throw new IOException(e.getMessage(), e);
        }
        if (doc == null || !doc.path("jobs").isArray()) {
            throw new IOException("kit.py jobs --json gave no jobs");
        }
        final List<KitJobs.Entry> out = new ArrayList<>();
        for (final JsonNode j : doc.path("jobs")) {
            final List<String> days = strings(j.path("days"));
            final KitJob job = new KitJob(j.path("name").asString(""), j.path("description").asString(""),
                    days.isEmpty() ? KitJob.WEEKDAYS : days, j.path("time").asString(""), strings(j.path("command")),
                    env(j.path("env")), j.path("log").asString(""), j.path("enabled").asBoolean(true));
            final JsonNode last = j.path("last");
            out.add(new KitJobs.Entry(job, j.path("installed").asBoolean(false), j.path("current").asBoolean(false),
                    j.path("loaded").isBoolean() ? j.path("loaded").asBoolean(false) : null, j.path("running").asBoolean(false),
                    last.isObject() ? last.path("start").asString("") : "", last.isObject() && last.path("exit").isInt() ? last.path("exit").asInt(0) : null,
                    j.path("next").isString() ? j.path("next").asString("") : "", j.path("logPath").asString("")));
        }
        return new KitJobs(true, out, "");
    }

    /** {@code jobs} in the manifest's form, which {@code jobs set} reads: defaults left out. */
    static ArrayNode manifestJobs(final List<KitJob> jobs) {
        final ArrayNode out = JSON.createArrayNode();
        for (final KitJob j : jobs) {
            final ObjectNode o = out.addObject();
            o.put("name", j.name());
            if (!j.description().isBlank()) {
                o.put("description", j.description());
            }
            final ObjectNode schedule = o.putObject("schedule");
            if (j.daily()) {
                schedule.put("daily", j.paddedTime());
            } else {
                schedule.put("weekly", KitJob.daysText(j.days()) + " " + j.paddedTime());
            }
            final ArrayNode command = o.putArray("command");
            j.command().forEach(command::add);
            if (!j.env().isEmpty()) {
                final ObjectNode env = o.putObject("env");
                j.env().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> env.put(e.getKey(), e.getValue()));
            }
            if (!j.log().isEmpty()) {
                o.put("log", j.log());
            }
            if (!j.enabled()) {
                o.put("enabled", false);
            }
        }
        return out;
    }

    private static List<String> strings(final JsonNode array) {
        final List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString("")));
        return out;
    }

    private static Map<String, String> env(final JsonNode object) {
        final Map<String, String> out = new LinkedHashMap<>();
        object.properties().forEach(e -> out.put(e.getKey(), e.getValue().asString("")));
        return out;
    }
}
