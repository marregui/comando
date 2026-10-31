package dev.marregui.comando.core.loop;

import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.reduce.Reducer;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.testkit.FakeClock;
import dev.marregui.comando.testkit.States;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/// [StateLoop] applies intents in order, hands out their effects, tells listeners, and reports a slow or hung intent.
///
/// - effects precede listeners for each changed state
/// - queued work completes before close returns
/// - handler and observer failures do not halt later intents
/// - an `Error` stops the loop loudly, and later intents are refused with one error line
/// - stalls identify their slowest part
class StateLoopTest {

    /// Two SQL intents are reduced in queue order, with each transition's effects delivered before its state listener.
    @Test
    void appliesIntentsInOrderPublishesStateAndHandsEffectsOutBeforeListeners() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(2);
        try (StateLoop loop = new StateLoop(States.twoPanels(), new Reducer(new FakeClock()), e -> order.add("effect " + e.getClass().getSimpleName()))) {
            loop.addListener((prev, next) -> {
                order.add("state " + next.nextId());
                done.countDown();
            });
            loop.start();
            loop.dispatch(new Intent.RunText(States.P1, "SELECT 1", Target.AUTO));
            loop.dispatch(new Intent.RunText(States.P2, "SELECT 2", Target.AUTO));
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

            AppState s = loop.state();
            assertThat(s.runs()).hasSize(2);
            assertThat(order).containsExactly(
                    "effect OpenDb", "effect ExecuteSql", "state 2",
                    "effect ExecuteSql", "state 3");
        }
    }

    /// An exception from `OpenDb` is contained, allowing the following SQL intent to reach its listener.
    @Test
    void aFailingEffectHandlerDoesNotStopTheLoop() throws Exception {
        CountDownLatch done = new CountDownLatch(2);
        StateLoop.EffectHandler failing = e -> {
            if (e instanceof Effect.OpenDb) {
                throw new IllegalStateException("adapter down");
            }
        };
        try (StateLoop loop = new StateLoop(States.twoPanels(), new Reducer(new FakeClock()), failing)) {
            loop.addListener((prev, next) -> done.countDown());
            loop.start();
            loop.dispatch(new Intent.RunText(States.P1, "SELECT 1", Target.AUTO));
            loop.dispatch(new Intent.RunText(States.P2, "SELECT 2", Target.AUTO));
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    /// An observer that throws on the first intent is logged and skipped: both intents still reach the listener.
    @Test
    void aFailingObserverDoesNotStopTheLoop() throws Exception {
        final CountDownLatch done = new CountDownLatch(2);
        try (StateLoop loop = new StateLoop(States.twoPanels(), new Reducer(new FakeClock()), e -> {
        })) {
            loop.addObserver(intent -> {
                if (intent instanceof Intent.FocusPanel f && f.panel().equals(States.P2)) {
                    throw new IllegalStateException("replay log full");
                }
            });
            loop.addListener((prev, next) -> done.countDown());
            loop.start();
            loop.dispatch(new Intent.FocusPanel(States.P2));
            loop.dispatch(new Intent.FocusPanel(States.P1));
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(loop.failure()).isNull();
            assertThat(loop.state().focused()).isEqualTo(States.P1);
        }
    }

    /// A `StackOverflowError` from an observer stops the loop: the error is kept and logged, the state stays as it was,
    /// and the two intents dispatched after it are refused with one error line between them, not queued in silence.
    @Test
    void anErrorStopsTheLoopAndLaterIntentsAreRefusedWithOneErrorLine() throws Exception {
        final List<LogRecord> errors = new CopyOnWriteArrayList<>();
        final CountDownLatch stopped = new CountDownLatch(1);
        final Handler capture = new Handler() {
            @Override
            public void publish(LogRecord entry) {
                if (entry.getLevel().intValue() >= Level.SEVERE.intValue()) {
                    errors.add(entry);
                    if (entry.getThrown() instanceof StackOverflowError) {
                        stopped.countDown();
                    }
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        final Logger log = Logger.getLogger(StateLoop.class.getName());
        log.addHandler(capture);
        try (StateLoop loop = new StateLoop(States.twoPanels(), new Reducer(new FakeClock()), e -> {
        })) {
            final AppState before = loop.state();
            loop.addObserver(intent -> {
                throw new StackOverflowError("simulated");
            });
            loop.start();
            loop.dispatch(new Intent.FocusPanel(States.P2));
            assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(loop.failure()).isInstanceOf(StackOverflowError.class);

            loop.dispatch(new Intent.FocusPanel(States.P2));
            loop.dispatch(new Intent.FocusPanel(States.P1));
            assertThat(loop.state()).isSameAs(before);
            assertThat(errors).extracting(LogRecord::getMessage)
                    .filteredOn(m -> m.startsWith("the reducer has stopped; intents are dropped"))
                    .hasSize(1);
        } finally {
            log.removeHandler(capture);
        }
    }

    /// Closing after one thousand queued focus changes drains them through the final focus before the loop stops.
    @Test
    void closeAppliesTheIntentsQueuedBeforeIt() {
        StateLoop loop = new StateLoop(States.twoPanels(), new Reducer(new FakeClock()), e -> {
        }).start();
        for (int i = 0; i < 1_000; i++) {
            loop.dispatch(new Intent.FocusPanel(i % 2 == 0 ? States.P1 : States.P2));
        }
        loop.close();
        assertThat(loop.state().focused()).isEqualTo(States.P2);
    }

    /// Repeated closing is harmless, and an intent dispatched afterward leaves the published state object unchanged.
    @Test
    void closeIsIdempotentAndDropsLaterIntents() {
        StateLoop loop = new StateLoop(States.twoPanels(), new Reducer(new FakeClock()), e -> {
        }).start();
        loop.close();
        loop.close();
        AppState before = loop.state();
        loop.dispatch(new Intent.RunText(States.P1, "SELECT 1", Target.AUTO));
        assertThat(loop.state()).isSameAs(before);
    }

    /// An `ExecuteSql` handler lasting 120 ms exceeds the 60 ms stall limit and reports that effect once after it ends.
    @Test
    void anIntentPastTheStallLimitIsToldOnceItEndsWithItsSlowestPart() {
        List<StateLoop.Stall> stalls = new CopyOnWriteArrayList<>();
        StateLoop.EffectHandler slow = e -> {
            if (e instanceof Effect.ExecuteSql) {
                hold(120);
            }
        };
        try (StateLoop loop = new StateLoop(States.twoPanels(), new Reducer(new FakeClock()), slow, 60, 10_000, stalls::add)) {
            loop.applyNow(new Intent.FocusPanel(States.P2));
            assertThat(stalls).isEmpty();

            loop.applyNow(new Intent.RunText(States.P1, "SELECT 1", Target.AUTO));
            assertThat(stalls).hasSize(1);
            StateLoop.Stall stall = stalls.getFirst();
            assertThat(stall.ended()).isTrue();
            assertThat(stall.part()).isEqualTo("effect ExecuteSql");
            assertThat(stall.millis()).isGreaterThanOrEqualTo(120);
            assertThat(stall.stack()).isEmpty();
        }
    }

    /// An `ExecuteSql` handler held for 700 ms reports one 300 ms hang with its stack, then one ended stall sharing the
    /// start time.
    @Test
    void anIntentHeldPastTheHangLimitIsToldOnceWhileHeldWithTheLoopsStackThenWhenItEnds() throws Exception {
        List<StateLoop.Stall> stalls = new CopyOnWriteArrayList<>();
        CountDownLatch ended = new CountDownLatch(1);
        // held 400 ms past the hang limit, so the watchdog looks at the same intent four more times
        StateLoop.EffectHandler blocking = e -> {
            if (e instanceof Effect.ExecuteSql) {
                hold(700);
            }
        };
        try (StateLoop loop = new StateLoop(States.twoPanels(), new Reducer(new FakeClock()), blocking, 50, 300, s -> {
            stalls.add(s);
            if (s.ended()) {
                ended.countDown();
            }
        }).start()) {
            loop.dispatch(new Intent.RunText(States.P1, "SELECT 1", Target.AUTO));
            assertThat(ended.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(stalls).hasSize(2);
            StateLoop.Stall told = stalls.getFirst();
            assertThat(told.ended()).isFalse();
            assertThat(told.part()).isEqualTo("effect ExecuteSql");
            assertThat(told.millis()).isGreaterThanOrEqualTo(300);
            assertThat(told.stack()).contains("StateLoopTest.hold");
            StateLoop.Stall end = stalls.get(1);
            assertThat(end.ended()).isTrue();
            assertThat(end.part()).isEqualTo("effect ExecuteSql");
            assertThat(end.millis()).isGreaterThanOrEqualTo(700);
            assertThat(end.since()).isEqualTo(told.since());
        }
    }

    /// A 700 ms hung SQL effect creates a log-linked notice whose timestamp is captured near the 300 ms hang threshold.
    @Test
    void aHangsNoticeNamesTheLogEntryWithItsStack() throws Exception {
        CountDownLatch noticed = new CountDownLatch(1);
        StateLoop.EffectHandler blocking = e -> {
            if (e instanceof Effect.ExecuteSql) {
                hold(700);
            }
        };
        long before = System.currentTimeMillis();
        try (StateLoop loop = new StateLoop(States.twoPanels(), new Reducer(new FakeClock()), blocking, 50, 300, null).start()) {
            loop.addListener((previous, next) -> {
                if (next.notice().kind().equals("log")) {
                    noticed.countDown();
                }
            });
            loop.dispatch(new Intent.RunText(States.P1, "SELECT 1", Target.AUTO));
            assertThat(noticed.await(5, TimeUnit.SECONDS)).isTrue();

            // the stack is logged at the hang limit, about 300 ms in; the end's line about 700 ms in
            long at = Long.parseLong(loop.state().notice().key());
            assertThat(at - before).isBetween(250L, 650L);
            assertThat(loop.state().notice().text()).startsWith("comando stopped updating for ");
        }
    }

    /** Work that takes {@code millis}: the loop's thread is held, as a port that blocks would hold it. */
    private static void hold(long millis) {
        long until = System.nanoTime() + millis * 1_000_000;
        while (System.nanoTime() < until) {
            Thread.onSpinWait();
        }
    }
}
