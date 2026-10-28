package dev.marregui.comando.ui.shell;

import dev.marregui.comando.core.state.LowerTab;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import java.awt.Component;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// [FocusOrigins] keeps the return path (record 112): closing the place the user is at returns to where they were just
/// before they last arrived at it; closing a tab they are not at moves nothing.
/// - Openings chain and unwind in order.
/// - The way back is where the user came from last, not what opened the place.
/// - Closing a tab the user is not at moves nothing.
/// - An arrival told at the request wins over the render.
/// - A way back that closed meanwhile hands on to its own; a place reached because another closed keeps its own.
class FocusOriginsTest {

    private final FocusOrigins origins = new FocusOrigins();
    private final Map<String, Component> text = Map.of("panel:a", new JLabel("a"), "panel:b", new JLabel("b"),
            "panel:c", new JLabel("c"), "panel:main", new JLabel("main"), "panel:f", new JLabel("f"));
    private final Component tree = new JLabel("tree");
    private final Set<Component> gone = new HashSet<>();
    private Set<String> open = Set.of();
    private String at;

    /** A render: {@code open} shows, the user is at {@code now}, the keyboard having been in {@code at} before. */
    private FocusOrigins.Origin render(Set<String> open, String now) {
        this.open = open;
        FocusOrigins.Origin before = at == null ? null : new FocusOrigins.Origin(text.get(at), at, LowerTab.SESSION, at);
        at = now;
        Set<String> shown = open;
        return origins.onRender(open, before, now,
                o -> !gone.contains(o.owner()) && (o.ownerKey() == null || shown.contains(o.ownerKey())));
    }

    /// The first render returns no origin; after A, B, C open in turn, closing C returns to B and closing B to A.
    @Test
    void openingsChainAndUnwindInOrder() {
        assertThat(render(Set.of("panel:a"), "panel:a")).as("the first render remembers what is open").isNull();
        render(Set.of("panel:a", "panel:b"), "panel:b");
        render(Set.of("panel:a", "panel:b", "panel:c"), "panel:c");
        assertThat(render(Set.of("panel:a", "panel:b"), "panel:b").owner()).as("C closed: back to B").isSameAs(text.get("panel:b"));
        assertThat(render(Set.of("panel:a"), "panel:a").owner()).as("B closed: back to A").isSameAs(text.get("panel:a"));
    }

    /// F opened from review, then the user visited main and came back to F; closing F returns to main.
    @Test
    void theWayBackIsWhereTheUserCameFromLastNotWhatOpenedIt() {
        render(Set.of("panel:review", "panel:main"), "panel:review");
        render(Set.of("panel:review", "panel:main", "panel:f"), "panel:f"); // opened from review
        render(Set.of("panel:review", "panel:main", "panel:f"), "panel:main"); // a click on main
        render(Set.of("panel:review", "panel:main", "panel:f"), "panel:f"); // and back to F
        FocusOrigins.Origin back = render(Set.of("panel:review", "panel:main"), "panel:review");
        assertThat(back.owner()).as("F closed: back to main, where the user came from").isSameAs(text.get("panel:main"));
    }

    /// Closing F while the user is at main returns no origin.
    @Test
    void closingATabTheUserIsNotAtMovesNothing() {
        render(Set.of("panel:review", "panel:main"), "panel:review");
        render(Set.of("panel:review", "panel:main", "panel:f"), "panel:f");
        render(Set.of("panel:review", "panel:main", "panel:f"), "panel:main");
        assertThat(render(Set.of("panel:review", "panel:main"), "panel:main")).as("F closed in the background").isNull();
    }

    /// An origin given by [FocusOrigins#opened] (the Files tree) replaces the panel in front at the render, and closing
    /// returns to it.
    @Test
    void anArrivalToldAtTheRequestWinsOverTheRender() {
        render(Set.of("panel:a", "panel:b"), "panel:a");
        origins.opened("panel:f", new FocusOrigins.Origin(tree, null, LowerTab.SESSION, "panel:b"));
        render(Set.of("panel:a", "panel:b", "panel:f"), "panel:f");
        assertThat(origins.of("panel:f").owner()).as("opened from the Files tab, not the panel in front").isSameAs(tree);
        assertThat(render(Set.of("panel:a", "panel:b"), "panel:a").owner()).isSameAs(tree);
    }

    /// With C from B from A and B closed in the background, closing C returns to A.
    @Test
    void aWayBackThatClosedMeanwhileHandsOnToItsOwn() {
        render(Set.of("panel:a"), "panel:a");
        render(Set.of("panel:a", "panel:b"), "panel:b");
        render(Set.of("panel:a", "panel:b", "panel:c"), "panel:c");
        gone.add(text.get("panel:b"));
        render(Set.of("panel:a", "panel:c"), "panel:c"); // B closed in the background
        assertThat(render(Set.of("panel:a"), "panel:a").owner()).as("C came from B, B from A").isSameAs(text.get("panel:a"));
    }

    /// Returning to B because C closed keeps B's own origin, A, instead of recording C.
    @Test
    void reachingAPlaceBecauseAnotherClosedKeepsItsOwnWayBack() {
        render(Set.of("panel:a"), "panel:a");
        render(Set.of("panel:a", "panel:b"), "panel:b");
        render(Set.of("panel:a", "panel:b", "panel:c"), "panel:c");
        render(Set.of("panel:a", "panel:b"), "panel:b"); // C closed: back to B
        assertThat(origins.of("panel:b").owner()).as("B still came from A").isSameAs(text.get("panel:a"));
    }
}
