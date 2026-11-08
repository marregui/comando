package dev.marregui.comando.core.reduce;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.LowerTab;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.ViewState;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.testkit.FakeClock;
import dev.marregui.comando.testkit.States;

import static dev.marregui.comando.testkit.States.P1;
import static dev.marregui.comando.testkit.States.P2;
import static org.assertj.core.api.Assertions.assertThat;

/// [Reducer] keeps the workspace's right-hand tabs and its preview tab (record 141).
///
/// - a panel moves to the end of the side tabs or of the main tabs, focused, and the main tabs keep one panel
/// - a panel closed on the side reopens there
/// - the preview tab is replaced by the next preview, never kept closed, reused for its own file and pinned to stay
/// - a view moves between the lower panel and the side, and a session's update still reaches it there
class ReducerPartitionTest {

    private static final PanelId P3 = new PanelId("p3");
    private static final PanelId HIT = new PanelId("hit-1");
    private static final PanelId NEXT_HIT = new PanelId("hit-2");
    private static final String NO_LAST_MOVE = "The last panel of the main tabs cannot move to the right side";

    private final FakeClock clock = new FakeClock();
    private final Reducer reducer = new Reducer(clock);
    private AppState state = States.twoPanels();
    private List<Effect> effects = List.of();

    private void apply(final Intent intent) {
        final Transition t = reducer.apply(state, intent);
        state = t.state();
        effects = t.effects();
    }

    private List<PanelId> mainTabs() {
        return state.panels().stream().filter(p -> !p.side()).map(PanelState::id).toList();
    }

    private List<PanelId> sideTabs() {
        return state.panels().stream().filter(PanelState::side).map(PanelState::id).toList();
    }

    private long onlyView() {
        return state.views().getFirst().id();
    }

    // ---- placing panels

    /// A panel sent to the side goes to the end of the side tabs, focused and saved with its side; sent back it goes
    /// to the end of the main tabs.
    @Test
    void aPanelMovesToTheEndOfTheOtherTabsFocusedAndSaved() {
        apply(new Intent.AddPanel(P3, "three"));
        apply(new Intent.PlacePanel(P1, true));
        apply(new Intent.FocusPanel(P2));
        apply(new Intent.PlacePanel(P3, true));

        assertThat(mainTabs()).containsExactly(P2);
        assertThat(sideTabs()).containsExactly(P1, P3);
        assertThat(state.focused()).isEqualTo(P3);
        assertThat(effects).singleElement().isInstanceOfSatisfying(Effect.SavePanels.class, e -> assertThat(e.panels())
                .filteredOn(PanelState::side).extracting(PanelState::id).containsExactly(P1, P3));

        apply(new Intent.PlacePanel(P1, false));
        assertThat(mainTabs()).containsExactly(P2, P1);
        assertThat(sideTabs()).containsExactly(P3);
        assertThat(state.focused()).isEqualTo(P1);
    }

    /// The last panel of the main tabs does not move to the side: the notice says why, nothing is saved, and a tool is
    /// given the same reason before it sends.
    @Test
    void theLastPanelOfTheMainTabsStaysThere() {
        apply(new Intent.PlacePanel(P1, true));
        final Intent.PlacePanel last = new Intent.PlacePanel(P2, true);
        apply(last);

        assertThat(mainTabs()).containsExactly(P2);
        assertThat(sideTabs()).containsExactly(P1);
        assertThat(state.notice().text()).isEqualTo(NO_LAST_MOVE);
        assertThat(effects).isEmpty();
        assertThat(Reducer.refusal(state, last)).contains(NO_LAST_MOVE);
        assertThat(Reducer.refusal(state, new Intent.PlacePanel(P1, false))).as("back to the main tabs").isEmpty();
    }

    /// A panel already where it is sent is only focused; a closed panel is not placed.
    @Test
    void aPanelAlreadyThereIsFocusedAndAClosedOneIsNotPlaced() {
        apply(new Intent.PlacePanel(P2, false));
        assertThat(state.focused()).isEqualTo(P2);
        assertThat(effects).isEmpty();

        apply(new Intent.ClosePanel(P2));
        final AppState before = state;
        apply(new Intent.PlacePanel(P2, true));
        assertThat(state).isSameAs(before);
    }

    /// The last panel of the main tabs is not closed while the side holds others, and removed it leaves an empty panel
    /// in the main tabs.
    @Test
    void theMainTabsKeepOnePanelWhenTheirLastIsClosedOrRemoved() {
        apply(new Intent.PlacePanel(P1, true));
        apply(new Intent.ClosePanel(P2));
        assertThat(state.panel(P2)).isPresent();
        assertThat(state.notice().text()).isEqualTo("The last panel of the main tabs cannot be closed");

        apply(new Intent.RemovePanel(P2, ""));
        assertThat(state.panel(P2)).isEmpty();
        assertThat(mainTabs()).singleElement().isNotEqualTo(P1);
        assertThat(sideTabs()).containsExactly(P1);
    }

    /// A panel closed on the side keeps its side, so reopening it puts it back at the end of the side tabs; the focus
    /// goes to its neighbour in the same tabs.
    @Test
    void aPanelClosedOnTheSideReopensThere() {
        apply(new Intent.AddPanel(P3, "three"));
        apply(new Intent.PlacePanel(P2, true));
        apply(new Intent.PlacePanel(P3, true));
        apply(new Intent.ClosePanel(P3));
        assertThat(state.focused()).as("the neighbour on the side").isEqualTo(P2);
        assertThat(state.closedPanel(P3)).hasValueSatisfying(p -> assertThat(p.side()).isTrue());

        apply(new Intent.FocusPanel(P1));
        apply(new Intent.ReopenPanel(P3));
        assertThat(mainTabs()).containsExactly(P1);
        assertThat(sideTabs()).containsExactly(P2, P3);
        assertThat(state.focused()).isEqualTo(P3);
    }

    // ---- the preview tab

    /// A preview replaces the preview before it in its place, and the one replaced is let go as a closed document is:
    /// forgotten, and not among the closed panels ⌘⇧T reopens.
    @Test
    void aPreviewReplacesThePreviewBeforeItAndIsNeverKeptClosed() {
        apply(new Intent.AddPanel(HIT, "README.md", true, true));
        apply(new Intent.AddPanel(P3, "three"));
        apply(new Intent.AddPanel(NEXT_HIT, "README.md", true, true));

        assertThat(state.panels()).extracting(PanelState::id).containsExactly(P1, P2, NEXT_HIT, P3);
        assertThat(state.panel(NEXT_HIT)).hasValueSatisfying(p -> {
            assertThat(p.preview()).isTrue();
            assertThat(p.title()).as("the title the replaced preview held is free").isEqualTo("README.md");
        });
        assertThat(state.focused()).isEqualTo(NEXT_HIT);
        assertThat(state.closed()).isEmpty();
        assertThat(effects).first().isEqualTo(new Effect.ForgetPanel(HIT));
        assertThat(effects).last().isInstanceOf(Effect.SavePanels.class);

        apply(new Intent.ClosePanel(NEXT_HIT));
        assertThat(state.closed()).as("a preview closed is not kept").isEmpty();
    }

    /// A preview on the side, focused, is replaced on the side by the next preview, which takes the focus.
    @Test
    void aPreviewOnTheSideIsReplacedThere() {
        apply(new Intent.AddPanel(HIT, "a.md", true, true));
        apply(new Intent.PlacePanel(HIT, true));
        apply(new Intent.AddPanel(NEXT_HIT, "b.md", true, true));

        assertThat(sideTabs()).containsExactly(NEXT_HIT);
        assertThat(mainTabs()).containsExactly(P1, P2);
        assertThat(state.focused()).isEqualTo(NEXT_HIT);
    }

    /// A pinned preview stays when the next file is previewed, which opens a preview of its own at the end.
    @Test
    void aPinnedPreviewStaysAndTheNextPreviewOpensBesideIt() {
        apply(new Intent.AddPanel(HIT, "a.md", true, true));
        apply(new Intent.PinPanel(HIT));
        assertThat(state.panel(HIT)).hasValueSatisfying(p -> assertThat(p.preview()).isFalse());
        assertThat(effects).singleElement().isInstanceOf(Effect.SavePanels.class);

        apply(new Intent.AddPanel(NEXT_HIT, "b.md", true, true));
        assertThat(state.panels()).extracting(PanelState::id).containsExactly(P1, P2, HIT, NEXT_HIT);
        assertThat(effects).noneMatch(Effect.ForgetPanel.class::isInstance);

        apply(new Intent.PinPanel(P1));
        assertThat(effects).as("a panel that is no preview: nothing to pin").isEmpty();
    }

    /// The preview's file opened again reuses the preview: previewed, it is focused and stays a preview; opened to
    /// stay, it is pinned.
    @Test
    void thePreviewsFileOpenedAgainReusesIt() {
        apply(new Intent.AddPanel(HIT, "a.md", true, true));
        apply(new Intent.FocusPanel(P1));
        apply(new Intent.AddPanel(HIT, "a.md", true, true));
        assertThat(state.focused()).isEqualTo(HIT);
        assertThat(state.panels()).hasSize(3);
        assertThat(state.panel(HIT)).hasValueSatisfying(p -> assertThat(p.preview()).isTrue());

        apply(new Intent.FocusPanel(P1));
        apply(new Intent.AddPanel(HIT, "a.md", true));
        assertThat(state.focused()).isEqualTo(HIT);
        assertThat(state.panel(HIT)).hasValueSatisfying(p -> assertThat(p.preview()).isFalse());
        assertThat(effects).singleElement().isInstanceOf(Effect.SavePanels.class);
    }

    // ---- placing views

    /// A view moved to the side leaves the lower panel, whose tab goes back to the session's; moved back it becomes the
    /// lower tab again. Its home stays where it was first placed.
    @Test
    void aViewMovesBetweenTheLowerPanelAndTheSide() {
        apply(new Intent.ShowView("", "Changes", new ViewSpec.Markdown("a"), Placement.LOWER));
        final long id = onlyView();
        assertThat(state.lowerTab()).isEqualTo(LowerTab.view(id));

        apply(new Intent.PlaceView(id, Placement.SIDE));
        assertThat(state.view(id)).hasValueSatisfying(v -> {
            assertThat(v.placement()).isEqualTo(Placement.SIDE);
            assertThat(v.home()).isEqualTo(Placement.LOWER);
        });
        assertThat(state.lowerTab()).isEqualTo(LowerTab.SESSION);

        apply(new Intent.PlaceView(id, Placement.LOWER));
        assertThat(state.view(id)).map(ViewState::placement).contains(Placement.LOWER);
        assertThat(state.lowerTab()).isEqualTo(LowerTab.view(id));
    }

    /// A view that is not the lower tab moves to the side without changing the lower tab; an unknown view is not
    /// placed.
    @Test
    void aViewThatIsNotTheLowerTabMovesWithoutChangingIt() {
        apply(new Intent.ShowView("", "one", new ViewSpec.Markdown("a"), Placement.LOWER));
        final long first = onlyView();
        apply(new Intent.ShowView("", "two", new ViewSpec.Markdown("b"), Placement.LOWER));
        final LowerTab tab = state.lowerTab();

        apply(new Intent.PlaceView(first, Placement.SIDE));
        assertThat(state.lowerTab()).isEqualTo(tab);

        final AppState before = state;
        apply(new Intent.PlaceView(999L, Placement.SIDE));
        assertThat(state).isSameAs(before);
    }

    /// `Reducer.shownAt` says where a view shown again lands, the title clipped as the reducer clips it: a moved
    /// view's new spec reaches the side, a title never shown opens where it asks.
    @Test
    void shownAtSaysWhereAViewShownAgainLandsWithItsTitleClipped() {
        final String longTitle = "t".repeat(ViewRules.VIEW_TITLE_CHARS + 20);
        apply(new Intent.ShowView("work", longTitle, new ViewSpec.Markdown("one"), Placement.LOWER));
        apply(new Intent.PlaceView(onlyView(), Placement.SIDE));

        final Intent.ShowView again = new Intent.ShowView("work", longTitle, new ViewSpec.Markdown("two"), Placement.LOWER);
        assertThat(Reducer.shownAt(state, again)).isEqualTo(Placement.SIDE);
        apply(again);
        assertThat(state.views()).singleElement().extracting(ViewState::placement).isEqualTo(Placement.SIDE);

        assertThat(Reducer.shownAt(state, new Intent.ShowView("work", "other", new ViewSpec.Markdown("x"),
                Placement.LOWER))).isEqualTo(Placement.LOWER);
    }

    /// A session's update of a view the user moved reaches it where it is now: replaced in place, no second view, the
    /// lower tab untouched; the user's own command shows its moved view again without taking the lower panel.
    @Test
    void aSessionsUpdateReachesAViewTheUserMoved() {
        apply(new Intent.ShowView("work", "notes", new ViewSpec.Markdown("one"), Placement.LOWER));
        final long id = onlyView();
        apply(new Intent.PlaceView(id, Placement.SIDE));

        apply(new Intent.ShowView("work", "notes", new ViewSpec.Markdown("two"), Placement.LOWER));
        assertThat(state.views()).singleElement().satisfies(v -> {
            assertThat(v.id()).isEqualTo(id);
            assertThat(v.placement()).as("where the user put it").isEqualTo(Placement.SIDE);
            assertThat(v.spec()).isEqualTo(new ViewSpec.Markdown("two"));
            assertThat(v.version()).isEqualTo(2L);
        });
        assertThat(state.lowerTab()).isEqualTo(LowerTab.SESSION);

        apply(new Intent.ShowView("", "Find in files", new ViewSpec.Markdown("a"), Placement.LOWER));
        final long own = state.views().getLast().id();
        apply(new Intent.PlaceView(own, Placement.SIDE));
        apply(new Intent.ShowView("", "Find in files", new ViewSpec.Markdown("b"), Placement.LOWER));
        assertThat(state.views()).hasSize(2);
        assertThat(state.lowerTab()).as("on the side: not the lower tab").isEqualTo(LowerTab.SESSION);
    }

    /// A refresh of the user's own view finds it on the side too.
    @Test
    void aRefreshFindsAViewTheUserMoved() {
        apply(new Intent.ShowView("", "plot · one", new ViewSpec.Markdown("a"), Placement.LOWER));
        final long id = onlyView();
        apply(new Intent.PlaceView(id, Placement.SIDE));

        apply(new Intent.RefreshView("plot · one", new ViewSpec.Markdown("c")));
        assertThat(state.view(id)).map(ViewState::spec).contains(new ViewSpec.Markdown("c"));
    }
}
