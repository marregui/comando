package dev.marregui.comando.tools;

import dev.marregui.comando.core.view.ViewSpec;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One pass over a JFR recording: event counts by type with first and last time, and the CPU-load and
 * heap-after-GC series for plots. Questions beyond that go to jfrq.
 */
final class Recordings {

    private Recordings() {
    }

    /** Series of time (epoch millis) and value. */
    static final class Series {
        private double[] x = new double[256];
        private double[] y = new double[256];
        private int n;

        void add(double t, double v) {
            if (n == x.length) {
                x = Arrays.copyOf(x, n * 2);
                y = Arrays.copyOf(y, n * 2);
            }
            x[n] = t;
            y[n] = v;
            n++;
        }

        int size() {
            return n;
        }

        ViewSpec.Series spec(String name) {
            return new ViewSpec.Series(name, Arrays.copyOf(x, n), Arrays.copyOf(y, n));
        }
    }

    private static final class Count {
        long count;
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
    }

    record Summary(ViewSpec.Table events, ViewSpec.Plot cpu, ViewSpec.Plot heap) {
    }

    static Summary read(Path file) throws IOException {
        Map<String, Count> counts = new HashMap<>();
        Series jvmUser = new Series();
        Series jvmSystem = new Series();
        Series machine = new Series();
        Series heap = new Series();
        try (RecordingFile rf = new RecordingFile(file)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();
                String type = e.getEventType().getName();
                long t = e.getStartTime().toEpochMilli();
                Count c = counts.computeIfAbsent(type, k -> new Count());
                c.count++;
                c.first = Math.min(c.first, t);
                c.last = Math.max(c.last, t);
                switch (type) {
                    case "jdk.CPULoad" -> {
                        jvmUser.add(t, 100 * e.getFloat("jvmUser"));
                        jvmSystem.add(t, 100 * e.getFloat("jvmSystem"));
                        machine.add(t, 100 * e.getFloat("machineTotal"));
                    }
                    case "jdk.GCHeapSummary" -> {
                        if ("After GC".equals(e.getString("when"))) {
                            heap.add(t, e.getLong("heapUsed") / (1024.0 * 1024.0));
                        }
                    }
                    default -> {
                        // counted only
                    }
                }
            }
        }
        List<Map.Entry<String, Count>> rows = new ArrayList<>(counts.entrySet());
        rows.sort(Comparator.comparingLong((Map.Entry<String, Count> r) -> r.getValue().count).reversed());
        List<List<String>> table = new ArrayList<>();
        for (Map.Entry<String, Count> r : rows) {
            Count c = r.getValue();
            table.add(List.of(r.getKey(), Long.toString(c.count), Instant.ofEpochMilli(c.first).toString(), Instant.ofEpochMilli(c.last).toString()));
        }
        ViewSpec.Table events = new ViewSpec.Table(List.of("event", "count", "first", "last"), table);
        ViewSpec.Plot cpu = jvmUser.size() == 0 ? null : new ViewSpec.Plot("time", "CPU %", true,
                List.of(jvmUser.spec("JVM user"), jvmSystem.spec("JVM system"), machine.spec("machine")));
        ViewSpec.Plot heapPlot = heap.size() == 0 ? null : new ViewSpec.Plot("time", "heap after GC (MB)", true, List.of(heap.spec("heap used")));
        return new Summary(events, cpu, heapPlot);
    }
}
