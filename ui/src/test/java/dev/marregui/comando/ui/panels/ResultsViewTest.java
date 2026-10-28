package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.result.ResultRegistry;
import dev.marregui.comando.core.state.SqlRun;
import dev.marregui.comando.ui.kit.format.KeyText;
import dev.marregui.comando.ui.kit.overlay.Overlay;
import dev.marregui.comando.ui.widget.capability.Capabilities;
import dev.marregui.comando.ui.widget.capability.Copyable;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.InvocationTargetException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/// [ResultsView] shows a panel's run: the running overlay, the error, the grid, and what the tab copies.
/// - the tick paints no run the panel does not have
/// - a failed run shows the database's error as given
/// - the tab copies the error of a failed run, else the grid's text
/// - with no run or no rows yet there is nothing to copy
class ResultsViewTest {

    private static final String EMPTY = KeyText.of("Run SQL from the editor: select it, or put the caret in its block, and press ⌘⏎.");

    /// After a running statement is replaced by no run, the tick keeps the empty-state text instead of the running text.
    @Test
    void theTickDoesNotPaintARunThePanelDoesNotHave() {
        onEdt(() -> {
            ResultsView rv = new ResultsView(new ResultRegistry());
            SqlRun running = SqlRun.queued(7, PanelId.random(), "tutorial", "select 1").started(System.currentTimeMillis() - 42_000);
            rv.render(Optional.of(running));
            assertThat(overlay(rv).messageText()).startsWith("Running on tutorial");
            rv.render(Optional.empty());
            assertThat(overlay(rv).messageText()).isEqualTo(EMPTY);
            rv.tick();
            assertThat(overlay(rv).messageText()).isEqualTo(EMPTY);
        });
    }

    /// The overlay of a failed run shows the error text exactly as the database gave it, position line included.
    @Test
    void aFailedRunShowsTheErrorWithoutAClaimAboutTheEditor() {
        onEdt(() -> {
            ResultsView rv = new ResultsView(new ResultRegistry());
            String error = "ERROR: table does not exist [table=nope]\n  Position: 16";
            SqlRun failed = SqlRun.queued(8, PanelId.random(), "tutorial", "insert into t1 values (99); select * From nope")
                    .started(System.currentTimeMillis()).failed(error, 5);
            rv.render(Optional.of(failed));
            assertThat(overlay(rv).errorText()).isEqualTo(error);
        });
    }

    /// The tab's [Copyable] copies the error of a failed run and the grid's text for a completed one; the grid keeps its own
    /// copy owner.
    @Test
    void theResultsTabCopiesTheErrorOfAFailedRun() {
        onEdt(() -> {
            ResultsView rv = new ResultsView(new ResultRegistry());
            Copyable copy = Capabilities.at(rv.component(), Copyable.class);
            assertThat(copy).isNotNull();
            String error = "ERROR: table does not exist [table=nope]";
            SqlRun run = SqlRun.queued(9, PanelId.random(), "tutorial", "select * From nope").started(System.currentTimeMillis());
            rv.render(Optional.of(run.failed(error, 5)));
            assertThat(copy.copyText()).isEqualTo(error);
            rv.render(Optional.of(run.completed(0, 0, false, 1, 1, 2)));
            assertThat(copy.copyText()).isEqualTo(rv.grid().copyText());
            assertThat(Capabilities.at(rv.grid().component(), Copyable.class)).as("the grid keeps its own owner").isSameAs(rv.grid());
        });
    }

    /// With no run, or a run still without rows, the tab cannot copy; a failed run can.
    @Test
    void anEmptyResultsTabHasNothingToCopy() {
        onEdt(() -> {
            ResultsView rv = new ResultsView(new ResultRegistry());
            Copyable copy = Capabilities.at(rv.component(), Copyable.class);
            rv.render(Optional.empty());
            assertThat(copy.canCopy()).isFalse();
            SqlRun run = SqlRun.queued(3, PanelId.random(), "tutorial", "select 1").started(System.currentTimeMillis());
            rv.render(Optional.of(run));
            assertThat(copy.canCopy()).as("running, no rows yet").isFalse();
            rv.render(Optional.of(run.failed("ERROR: boom", 5)));
            assertThat(copy.canCopy()).isTrue();
        });
    }

    private static Overlay overlay(ResultsView rv) {
        return find(rv.component(), Overlay.class);
    }

    private static <T> T find(Component root, Class<T> type) {
        if (type.isInstance(root)) {
            return type.cast(root);
        }
        if (root instanceof Container k) {
            for (Component x : k.getComponents()) {
                T t = find(x, type);
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }

    private static void onEdt(Runnable r) {
        try {
            SwingUtilities.invokeAndWait(r);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException x) {
                throw x;
            }
            if (e.getCause() instanceof Error x) {
                throw x;
            }
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
