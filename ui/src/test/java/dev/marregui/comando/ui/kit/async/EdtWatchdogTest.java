package dev.marregui.comando.ui.kit.async;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class EdtWatchdogTest {

    @Test
    void aBlockedEdtIsReportedOnceWithItsStack() throws Exception {
        List<String> stacks = new CopyOnWriteArrayList<>();
        EdtWatchdog w = new EdtWatchdog((millis, stack) -> stacks.add(millis + " " + stack));
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
        EdtWatchdog w = new EdtWatchdog((millis, stack) -> { }, ended::add);
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
            EdtWatchdog w = new EdtWatchdog((millis, stack) -> stacks.add(millis + " " + stack));
            try {
                Thread.sleep(400);
            } finally {
                w.close();
            }
        }
        assertThat(stacks).isEmpty();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
