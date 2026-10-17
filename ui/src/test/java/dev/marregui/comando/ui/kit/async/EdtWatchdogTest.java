package dev.marregui.comando.ui.kit.async;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class EdtWatchdogTest {

    @Test
    void aHangIsReportedOnceAtItsLimitWithItsStack() throws Exception {
        List<EdtWatchdog.Stall> reported = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        EdtWatchdog w = new EdtWatchdog(reported::add, (stall, millis) -> done.countDown(), EdtWatchdog::drawingOnly, 400, 2_000);
        try {
            assertThat(w.awaitFirstAnswer(5, TimeUnit.SECONDS)).isTrue();
            assertThat(reported).isEmpty();
            SwingUtilities.invokeAndWait(() -> holdEdtUntil(() -> !reported.isEmpty()));
            assertThat(done.await(5, TimeUnit.SECONDS)).as("the stall's end is reported").isTrue();
        } finally {
            w.close();
        }
        assertThat(reported).singleElement().satisfies(s -> {
            assertThat(s.reported()).isTrue();
            assertThat(s.millis()).isGreaterThanOrEqualTo(400L);
            assertThat(s.kind()).isEqualTo(EdtWatchdog.Kind.COMANDO);
            // the hold, wherever in its loop the sample finds the EDT: sleeping, or checking whether it may stop
            assertThat(s.stack()).contains("EdtWatchdogTest.holdEdtUntil");
        });
    }

    /** A stall below its limit leaves no stack: one line when it ends, saying what held the EDT and where. */
    @Test
    void aShortStallIsToldOnlyWhenItEndsWithItsKindAndWhere() throws Exception {
        List<EdtWatchdog.Stall> reported = new CopyOnWriteArrayList<>();
        List<EdtWatchdog.Stall> ended = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        EdtWatchdog w = new EdtWatchdog(reported::add, (stall, millis) -> {
            ended.add(stall);
            done.countDown();
        }, EdtWatchdog::drawingOnly, 2_000, 2_000);
        try {
            assertThat(w.awaitFirstAnswer(5, TimeUnit.SECONDS)).isTrue();
            // past the 200 ms threshold three times over, so the watchdog's 100 ms checks see it however late they run
            SwingUtilities.invokeAndWait(() -> holdEdtUntil(() -> w.probeWaitMillis() >= 600));
            assertThat(done.await(5, TimeUnit.SECONDS)).as("the stall's end is reported").isTrue();
        } finally {
            w.close();
        }
        assertThat(reported).isEmpty();
        assertThat(ended).singleElement().satisfies(s -> {
            assertThat(s.reported()).isFalse();
            assertThat(s.kind()).isEqualTo(EdtWatchdog.Kind.COMANDO);
            assertThat(s.where()).startsWith("in=").contains("comando=EdtWatchdogTest.sleep:");
        });
    }

    /** The stall notice needs the whole length: the threshold report comes at about 200 ms, the end one after it. */
    @Test
    void theEndOfAReportedStallCarriesItsWholeLength() throws Exception {
        List<Long> ended = new CopyOnWriteArrayList<>();
        EdtWatchdog w = new EdtWatchdog(stall -> { }, (stall, millis) -> ended.add(millis));
        try {
            Thread.sleep(300);
            SwingUtilities.invokeAndWait(() -> holdEdtUntil(() -> w.probeWaitMillis() >= 1_100));
            Thread.sleep(300);
        } finally {
            w.close();
        }
        assertThat(ended).singleElement().satisfies(m -> assertThat(m).isGreaterThanOrEqualTo(1_000L));
    }

    @Test
    void aWaitBelowTheThresholdIsNotReported() throws Exception {
        // the EDT is busy for 130 ms when the first probe is posted: it answers between the watchdog's
        // checks at 100 ms and 200 ms, so nothing may be reported
        List<String> stacks = new CopyOnWriteArrayList<>();
        SwingUtilities.invokeAndWait(() -> { }); // the EDT exists before the probes start
        for (int round = 0; round < 5; round++) {
            SwingUtilities.invokeLater(() -> sleep(130));
            EdtWatchdog w = new EdtWatchdog(stall -> stacks.add(stall.millis() + " " + stall.stack()));
            try {
                Thread.sleep(400);
            } finally {
                w.close();
            }
        }
        assertThat(stacks).isEmpty();
    }

    @Test
    void aStallBeforeTheFirstProbeIsStartup() throws Exception {
        List<EdtWatchdog.Stall> ended = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> { }); // the EDT exists before the probes start
        AtomicReference<EdtWatchdog> watching = new AtomicReference<>();
        // the window's build, before the watchdog's first probe, held until the probe has waited well past the threshold
        SwingUtilities.invokeLater(() -> holdEdtUntil(() -> watching.get() != null && watching.get().probeWaitMillis() >= 600));
        EdtWatchdog w = new EdtWatchdog(stall -> { }, (stall, millis) -> {
            ended.add(stall);
            done.countDown();
        });
        watching.set(w);
        try {
            assertThat(done.await(5, TimeUnit.SECONDS)).as("the stall's end is reported").isTrue();
        } finally {
            w.close();
        }
        assertThat(ended).singleElement().satisfies(s -> {
            assertThat(s.kind()).isEqualTo(EdtWatchdog.Kind.STARTUP);
            assertThat(s.where()).isEmpty();
        });
    }

    /** Drawing has a limit of its own, past the hang limit: Java2D waiting on macOS's GPU flush is not comando's. */
    @Test
    void aStallInTheJdksDrawingIsReportedOnlyWhenItEndsMarkedAsDrawing() throws Exception {
        List<EdtWatchdog.Stall> reported = new CopyOnWriteArrayList<>();
        List<EdtWatchdog.Stall> ended = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        EdtWatchdog w = new EdtWatchdog(reported::add, (stall, millis) -> {
            ended.add(stall);
            done.countDown();
        }, frames -> true, 400, 2_000);
        try {
            assertThat(w.awaitFirstAnswer(5, TimeUnit.SECONDS)).isTrue();
            SwingUtilities.invokeAndWait(() -> holdEdtUntil(() -> w.probeWaitMillis() >= 700));
            assertThat(done.await(5, TimeUnit.SECONDS)).as("the stall's end is reported").isTrue();
        } finally {
            w.close();
        }
        assertThat(reported).as("no stack below the drawing limit, though past the hang limit").isEmpty();
        assertThat(ended).singleElement().satisfies(s -> {
            assertThat(s.drawing()).isTrue();
            assertThat(s.reported()).isFalse();
        });
    }

    @Test
    void aDrawingStallPastItsLimitIsReportedLikeAHang() throws Exception {
        List<EdtWatchdog.Stall> reported = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        EdtWatchdog w = new EdtWatchdog(reported::add, (stall, millis) -> done.countDown(), frames -> true, 300, 500);
        try {
            assertThat(w.awaitFirstAnswer(5, TimeUnit.SECONDS)).isTrue();
            SwingUtilities.invokeAndWait(() -> holdEdtUntil(() -> !reported.isEmpty()));
            assertThat(done.await(5, TimeUnit.SECONDS)).as("the stall's end is reported").isTrue();
        } finally {
            w.close();
        }
        assertThat(reported).singleElement().satisfies(s -> {
            assertThat(s.drawing()).isTrue();
            assertThat(s.reported()).isTrue();
            assertThat(s.millis()).isGreaterThanOrEqualTo(500L);
            assertThat(s.stack()).contains("EdtWatchdogTest");
        });
    }

    @Test
    void waitingOnJava2dsRenderQueueIsDrawingWhoeverPaints() {
        assertThat(EdtWatchdog.drawingOnly(new StackTraceElement[]{PARK, LOCK, QUEUE, REPAINT})).isTrue();
        assertThat(EdtWatchdog.drawingOnly(new StackTraceElement[]{PARK, LOCK, QUEUE, FILL, OURS, REPAINT}))
                .as("comando's paint waiting on the queue").isTrue();
        assertThat(EdtWatchdog.drawingOnly(new StackTraceElement[]{WAIT, FLUSH, OURS, REPAINT}))
                .as("comando's paint waiting for a flush").isTrue();
        assertThat(EdtWatchdog.drawingOnly(new StackTraceElement[]{MARLIN, FILL, OURS, REPAINT}))
                .as("Java2D working for comando, not waiting").isFalse();
        assertThat(EdtWatchdog.drawingOnly(new StackTraceElement[]{MARLIN, REPAINT})).as("Java2D alone").isTrue();
        assertThat(EdtWatchdog.drawingOnly(new StackTraceElement[]{PARK, LOCK, REPAINT})).as("the AWT lock without Java2D").isFalse();
        assertThat(EdtWatchdog.drawingOnly(new StackTraceElement[]{CREATE, REPAINT})).as("Swing alone").isFalse();
    }

    @Test
    void whereNamesTheFirstFrameOutsideJavaBaseAndTheFirstComandoFrame() {
        assertThat(EdtWatchdog.where(new StackTraceElement[]{PARK, LOCK, QUEUE, FILL, OURS, REPAINT}))
                .isEqualTo("in=SunToolkit.awtLock comando=Items.fillRound:77");
        assertThat(EdtWatchdog.where(new StackTraceElement[]{CREATE, REPAINT})).isEqualTo("in=Graphics.create comando=-");
        assertThat(EdtWatchdog.where(new StackTraceElement[]{PARK, OURS})).isEqualTo("in=Items.fillRound comando=Items.fillRound:77");
    }

    private static final StackTraceElement PARK = frame("java.base", "jdk.internal.misc.Unsafe", "park", -2);
    private static final StackTraceElement WAIT = frame("java.base", "java.lang.Object", "wait0", -2);
    private static final StackTraceElement LOCK = frame("java.desktop", "sun.awt.SunToolkit", "awtLock", 233);
    private static final StackTraceElement QUEUE = frame("java.desktop", "sun.java2d.pipe.RenderQueue", "lock", 112);
    private static final StackTraceElement FLUSH = frame("java.desktop", "sun.java2d.metal.MTLRenderQueue$QueueFlusher", "flushNow", 179);
    private static final StackTraceElement MARLIN = frame("java.desktop", "sun.java2d.marlin.Renderer", "_endRendering", 1277);
    private static final StackTraceElement FILL = frame("java.desktop", "sun.java2d.SunGraphics2D", "fillRoundRect", 2276);
    private static final StackTraceElement CREATE = frame("java.desktop", "java.awt.Graphics", "create", 161);
    private static final StackTraceElement REPAINT = frame("java.desktop", "javax.swing.RepaintManager", "paint", 1279);
    private static final StackTraceElement OURS = frame(null, "dev.marregui.comando.ui.widgets.transcript.render.Items", "fillRound", 77);

    private static StackTraceElement frame(String module, String type, String method, int line) {
        String file = type.substring(type.lastIndexOf('.') + 1).replaceAll("\\$.*", "") + ".java";
        return new StackTraceElement(null, module, null, type, method, file, line);
    }

    /**
     * Holds the EDT until {@code seen}, at most 10 s: a fixed sleep assumes the watchdog's thread runs on time, which a
     * loaded CI runner does not promise.
     */
    private static void holdEdtUntil(BooleanSupplier seen) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!seen.getAsBoolean() && System.nanoTime() < end) {
            sleep(10);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
