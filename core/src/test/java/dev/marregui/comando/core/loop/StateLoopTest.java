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

import static org.assertj.core.api.Assertions.assertThat;

class StateLoopTest {

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
}
