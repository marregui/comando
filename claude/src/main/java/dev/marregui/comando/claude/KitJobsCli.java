package dev.marregui.comando.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.marregui.comando.core.loop.Processes;
import dev.marregui.comando.core.model.KitJob;
import dev.marregui.comando.core.model.KitJobs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The kit's scheduled jobs through the root's {@code install/kit.py} (record 117), which alone writes {@code
 * comando-kit.json}'s jobs and talks to the scheduler: {@link #read} asks {@code jobs --json}, {@link #save} hands it the
 * whole list ({@code jobs set}), {@link #run} starts one through the scheduler ({@code job run}), so a job never runs as
 * a child of comando, whose {@link Processes} would end it at its limit. Each call blocks up to {@link #LIMIT}: off the
 * EDT.
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
     * @param exit 0 done; 1 done in part (a running job left as it was, a load that failed); 2 refused, nothing written;
     *             3 an unexpected error, part may be done
     * @param text what it printed, standard output then standard error
     */
    public record Result(int exit, String text) {
    }

    /** The jobs of the kit at {@code root}, run with {@code python3} ({@code python} on Windows) from comando's {@code PATH}. */
    public KitJobsCli(Path root) {
        this(root, List.of(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows") ? "python" : "python3"));
    }

    KitJobsCli(Path root, List<String> python) {
        this.root = root;
        this.python = List.copyOf(python);
    }

    /** The installer that owns the jobs. */
    public Path script() {
        return root.resolve("install").resolve("kit.py");
    }

    /** The jobs as {@code kit.py} reports them; a {@link KitJobs} carrying the reason when it cannot be asked or answers no list. */
    public KitJobs read() {
        if (!Files.isRegularFile(script())) {
            return new KitJobs(true, List.of(), "the kit has no install/kit.py");
        }
        try {
            Processes.Result r = call(List.of("jobs", "--json"), null);
            if (r.exitCode() != 0) {
                return new KitJobs(true, List.of(), firstLine(r.stderr().isBlank() ? r.out() : r.stderr(), "kit.py jobs exited " + r.exitCode()));
            }
            return parse(r.out());
        } catch (IOException e) {
            return new KitJobs(true, List.of(), e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /** Hands {@code jobs}, the whole list, to {@code kit.py jobs set}: checked, written to the manifest, reconciled. */
    public Result save(List<KitJob> jobs) throws IOException {
        byte[] in = JSON.writeValueAsBytes(manifestJobs(jobs));
        return result(call(List.of("jobs", "set"), in));
    }

    /** Starts job {@code name} now through the scheduler. */
    public Result run(String name) throws IOException {
        return result(call(List.of("job", "run", name), null));
    }

    private Processes.Result call(List<String> args, byte[] stdin) throws IOException {
        List<String> argv = new ArrayList<>(python);
        argv.add(script().toString());
        argv.addAll(args);
        argv.add("--root");
        argv.add(root.toString());
        try {
            Processes.Result r = Processes.run(argv, null, root, stdin, LIMIT, MAX_OUT);
            if (r.timedOut()) {
                throw new IOException("kit.py " + args.getFirst() + " took longer than " + LIMIT.toSeconds()
                        + " s and was ended; it may have done part of it: the jobs are read again");
            }
            return r;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while kit.py ran", e);
        }
    }

    private static Result result(Processes.Result r) {
        String text = (r.out().strip() + "\n" + r.stderr().strip()).strip();
        return new Result(r.exitCode(), text);
    }

    private static String firstLine(String text, String otherwise) {
        String t = text.strip();
        if (t.isEmpty()) {
            return otherwise;
        }
        int nl = t.indexOf('\n');
        return nl < 0 ? t : t.substring(0, nl);
    }

    /** {@code kit.py jobs --json} read. */
    static KitJobs parse(String json) throws IOException {
        JsonNode doc = JSON.readTree(json);
        if (doc == null || !doc.path("jobs").isArray()) {
            throw new IOException("kit.py jobs --json gave no jobs");
        }
        List<KitJobs.Entry> out = new ArrayList<>();
        for (JsonNode j : doc.path("jobs")) {
            List<String> days = strings(j.path("days"));
            KitJob job = new KitJob(j.path("name").asText(), j.path("description").asText(""),
                    days.isEmpty() ? KitJob.WEEKDAYS : days, j.path("time").asText(""), strings(j.path("command")),
                    env(j.path("env")), j.path("log").asText(""), j.path("enabled").asBoolean(true));
            JsonNode last = j.path("last");
            out.add(new KitJobs.Entry(job, j.path("installed").asBoolean(false), j.path("current").asBoolean(false),
                    j.path("loaded").isBoolean() ? j.path("loaded").asBoolean() : null, j.path("running").asBoolean(false),
                    last.isObject() ? last.path("start").asText("") : "", last.isObject() && last.path("exit").isInt() ? last.path("exit").asInt() : null,
                    j.path("next").isTextual() ? j.path("next").asText() : "", j.path("logPath").asText("")));
        }
        return new KitJobs(true, out, "");
    }

    /** {@code jobs} in the manifest's form, which {@code jobs set} reads: defaults left out. */
    static ArrayNode manifestJobs(List<KitJob> jobs) {
        ArrayNode out = JSON.createArrayNode();
        for (KitJob j : jobs) {
            ObjectNode o = out.addObject();
            o.put("name", j.name());
            if (!j.description().isBlank()) {
                o.put("description", j.description());
            }
            ObjectNode schedule = o.putObject("schedule");
            if (j.daily()) {
                schedule.put("daily", j.paddedTime());
            } else {
                schedule.put("weekly", KitJob.daysText(j.days()) + " " + j.paddedTime());
            }
            ArrayNode command = o.putArray("command");
            j.command().forEach(command::add);
            if (!j.env().isEmpty()) {
                ObjectNode env = o.putObject("env");
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

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static Map<String, String> env(JsonNode object) {
        Map<String, String> out = new LinkedHashMap<>();
        object.properties().forEach(e -> out.put(e.getKey(), e.getValue().asText()));
        return out;
    }
}
