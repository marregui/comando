package dev.marregui.comando.ui.shell;

import dev.marregui.comando.core.state.LowerTab;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import java.awt.Component;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Where the keyboard goes back when what it was in closes (record 112): the opener, else the opener's, and so on. */
class FocusOriginsTest {

    private final FocusOrigins origins = new FocusOrigins();
    private final Component a = new JLabel("a");
    private final Component b = new JLabel("b");
    private final Component tree = new JLabel("tree");
    private final Set<Component> gone = new HashSet<>();

    private FocusOrigins.Origin render(Set<String> open, Component owner, String key, String front) {
        return origins.onRender(open, owner, key, LowerTab.SESSION, front,
                o -> !gone.contains(o.owner()) && (o.ownerKey() == null || open.contains(o.ownerKey())));
    }

    @Test
    void closingWhatWasOpenedGoesBackToItsOpenerAndOpeningsChain() {
        assertThat(render(Set.of("panel:a"), a, "panel:a", null)).as("the first render remembers what is open").isNull();
        render(Set.of("panel:a", "panel:b"), a, "panel:a", "panel:a");
        assertThat(origins.of("panel:b")).isEqualTo(new FocusOrigins.Origin(a, "panel:a", LowerTab.SESSION));
        render(Set.of("panel:a", "panel:b", "panel:c"), b, "panel:b", "panel:b");

        FocusOrigins.Origin back = render(Set.of("panel:a", "panel:b"), null, "panel:c", "panel:c");
        assertThat(back.owner()).as("C closed: back to B").isSameAs(b);
        back = render(Set.of("panel:a"), b, "panel:b", "panel:b");
        assertThat(back.owner()).as("B closed: back to A").isSameAs(a);
    }

    @Test
    void anOpenerThatClosedMeanwhileHandsOnToItsOwnOpener() {
        render(Set.of("panel:a"), a, "panel:a", null);
        render(Set.of("panel:a", "panel:b"), a, "panel:a", "panel:a");
        render(Set.of("panel:a", "panel:b", "panel:c"), b, "panel:b", "panel:b");
        gone.add(b);
        render(Set.of("panel:a", "panel:c"), null, null, "panel:c"); // B closed while C was in front: nothing goes back
        FocusOrigins.Origin back = render(Set.of("panel:a"), null, "panel:c", "panel:c");
        assertThat(back.owner()).as("C's opener B is gone: B's opener A").isSameAs(a);
    }

    @Test
    void anOpenerOutsideThePanelsGetsItBackAndClosingWhatTheKeyboardWasNotInGivesNothing() {
        render(Set.of("panel:a"), tree, null, "panel:a");
        render(Set.of("panel:a", "panel:d"), tree, null, "panel:a");
        assertThat(render(Set.of("panel:a", "panel:d", "view:1"), tree, null, "panel:d")).isNull();
        assertThat(render(Set.of("panel:a", "panel:d"), tree, null, "panel:d")).as("a view the keyboard was not in closed").isNull();
        FocusOrigins.Origin back = render(Set.of("panel:a"), a, "panel:a", "panel:d");
        assertThat(back.owner()).as("the panel in front closed: back to the Files tab").isSameAs(tree);
        assertThat(back.ownerKey()).isNull();
    }

    @Test
    void anOpenerWithNoneLeftGivesNothing() {
        render(Set.of("panel:a"), a, "panel:a", null);
        render(Set.of("panel:a", "panel:b"), a, "panel:a", "panel:a");
        gone.add(a);
        assertThat(render(Set.of("panel:x"), null, "panel:b", "panel:b")).isNull();
    }
}
