package dev.marregui.comando.core.diag;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.reduce.Reducer;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.testkit.FakeClock;
import dev.marregui.comando.testkit.States;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IntentEventTest {

    @Test
    void eachAppliedIntentIsRecorded(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("r.jfr");
        try (Recording r = new Recording(); StateLoop loop = new StateLoop(States.twoPanels(), new Reducer(new FakeClock()), e -> { })) {
            r.enable("comando.Intent");
            r.start();
            loop.applyNow(new Intent.SelectLowerTab(LowerTab.APPROVALS));
            loop.applyNow(new Intent.SelectLowerTab(LowerTab.APPROVALS));
            r.stop();
            r.dump(file);
        }
        List<RecordedEvent> events = RecordingFile.readAllEvents(file).stream()
                .filter(e -> e.getEventType().getName().equals("comando.Intent")).toList();
        assertThat(events).hasSize(2);
        assertThat(events).extracting(e -> e.getString("type")).containsOnly("SelectLowerTab");
        assertThat(events).extracting(e -> e.getBoolean("changed")).containsExactly(true, false);
    }
}
