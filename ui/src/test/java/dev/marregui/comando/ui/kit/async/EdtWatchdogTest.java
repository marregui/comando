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

/// [EdtWatchdog] probes the event dispatch thread and reports a stall with its kind, length, place and stack.
/// - A hang past its limit is reported once, with its stack, and its end with the whole length.
/// - A stall below its limit is told only when it ends, with its kind and where it was.
/// - A wait below the 200 ms threshold is not reported.
/// - A stall before the first probe answers is startup.
/// - A stall in the JDK's drawing has a longer limit of its own and is marked as drawing.
/// - [EdtWatchdog#drawingOnly] and [EdtWatchdog#where] classify and place a stack.
class EdtWatchdogTest {

    /// A hold past the 400 ms hang limit is reported exactly once, marked reported, with a length of at least the
    /// limit, kind [EdtWatchdog.Kind#COMANDO] and the holding method in its stack; its end is reported too.
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

    /// A stall below its limit leaves no stack: one line when it ends, saying what held the EDT and where. The hold
    /// lasts until the probe has waited 600 ms, three times the threshold, so late watchdog checks still see it.
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

    /// The stall notice needs the whole length: the threshold report comes at about 200 ms, the end one after it with
    /// at least the 1 s the EDT was held.
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

    /// An EDT busy for 130 ms when the first probe is posted answers between the 100 ms and 200 ms checks, so nothing
    /// is reported; five rounds guard against a lucky schedule.
    @Test
    void aWaitBelowTheThresholdIsNotReported() throws Exception {
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

    /// An EDT held by the window's build before the first probe answers ends as kind [EdtWatchdog.Kind#STARTUP] with no
    /// place.
    @Test
    void aStallBeforeTheFirstProbeIsStartup() throws Exception {
        List<EdtWatchdog.Stall> ended = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> { }); // the EDT exists before the probes start
        AtomicReference<EdtWatchdog> watching = new AtomicReference<>();
        // the window's build, before the first probe, held until the probe has waited well past the threshold
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

    /// Drawing has a limit of its own, past the hang limit: Java2D waiting on macOS's GPU flush is not comando's. A
    /// drawing stall past the 400 ms hang limit but below the 2 s drawing limit is only told at its end, marked as
    /// drawing.
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

    /// A drawing stall past its own 500 ms limit is reported with its stack, marked as drawing and reported.
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

    /// A stack waiting on Java2D's render queue or a flush is drawing even when comando's paint called it, Java2D alone
    /// is drawing, while Java2D working for comando, the AWT lock without Java2D, or Swing alone is not.
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

    /// [EdtWatchdog#where] gives `in=` the first frame outside `java.base` and `comando=` the first comando frame with
    /// its line, or `-` when there is none.
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
