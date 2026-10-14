package dev.marregui.comando.ui.kit.async;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class EdtWatchdogTest {

    @Test
    void aBlockedEdtIsReportedOnceWithItsStack() throws Exception {
        List<String> stacks = new CopyOnWriteArrayList<>();
        EdtWatchdog w = new EdtWatchdog(stall -> stacks.add(stall.millis() + " " + stall.stack()));
        try {
            Thread.sleep(300); // the first probes find a free EDT
            assertThat(stacks).isEmpty();
            SwingUtilities.invokeAndWait(() -> sleep(700));
            Thread.sleep(300);
        } finally {
            w.close();
        }
        assertThat(stacks).hasSize(1);
        assertThat(stacks.getFirst()).contains("EdtWatchdogTest").contains("sleep");
    }

    /** The stall notice needs the whole length: the threshold report comes at about 200 ms, the end one after it. */
    @Test
    void theEndOfAReportedStallCarriesItsWholeLength() throws Exception {
        List<Long> ended = new CopyOnWriteArrayList<>();
        EdtWatchdog w = new EdtWatchdog(stall -> { }, (stall, millis) -> ended.add(millis));
        try {
            Thread.sleep(300);
            SwingUtilities.invokeAndWait(() -> sleep(1_300));
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
    void aStallInTheJdksDrawingIsReportedOnlyWhenItEndsMarkedAsDrawing() throws Exception {
        List<EdtWatchdog.Stall> reported = new CopyOnWriteArrayList<>();
        List<EdtWatchdog.Stall> ended = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        EdtWatchdog w = new EdtWatchdog(reported::add, (stall, millis) -> {
            ended.add(stall);
            done.countDown();
        }, frames -> true, 2_000);
        try {
            assertThat(w.awaitFirstAnswer(5, TimeUnit.SECONDS)).isTrue();
            SwingUtilities.invokeAndWait(() -> sleep(600));
            assertThat(done.await(5, TimeUnit.SECONDS)).as("the stall's end is reported").isTrue();
        } finally {
            w.close();
        }
        assertThat(reported).as("no warning with a stack at the threshold").isEmpty();
        assertThat(ended).singleElement().satisfies(s -> assertThat(s.drawing()).isTrue());
    }

    @Test
    void aDrawingStallPastItsLimitIsReportedLikeAHang() throws Exception {
        List<EdtWatchdog.Stall> reported = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        EdtWatchdog w = new EdtWatchdog(reported::add, (stall, millis) -> done.countDown(), frames -> true, 500);
        try {
            assertThat(w.awaitFirstAnswer(5, TimeUnit.SECONDS)).isTrue();
            SwingUtilities.invokeAndWait(() -> sleep(900));
            assertThat(done.await(5, TimeUnit.SECONDS)).as("the stall's end is reported").isTrue();
        } finally {
            w.close();
        }
        assertThat(reported).singleElement().satisfies(s -> {
            assertThat(s.drawing()).isFalse();
            assertThat(s.millis()).isGreaterThanOrEqualTo(500L);
            assertThat(s.stack()).contains("EdtWatchdogTest");
        });
    }

    @Test
    void onlyAStackInJava2dWithNoComandoFrameIsDrawing() {
        StackTraceElement lock = new StackTraceElement("sun.awt.SunToolkit", "awtLock", "SunToolkit.java", 233);
        StackTraceElement queue = new StackTraceElement("sun.java2d.pipe.RenderQueue", "lock", "RenderQueue.java", 112);
        StackTraceElement repaint = new StackTraceElement("javax.swing.RepaintManager", "paint", "RepaintManager.java", 1279);
        StackTraceElement ours = new StackTraceElement("dev.marregui.comando.ui.widgets.transcript.render.TranscriptCanvas", "paint",
                "TranscriptCanvas.java", 470);
        assertThat(EdtWatchdog.drawingOnly(new StackTraceElement[]{lock, queue, repaint})).isTrue();
        assertThat(EdtWatchdog.drawingOnly(new StackTraceElement[]{lock, queue, ours, repaint})).as("comando painting").isFalse();
        assertThat(EdtWatchdog.drawingOnly(new StackTraceElement[]{lock, repaint})).as("no Java2D frame").isFalse();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
