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

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
