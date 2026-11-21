package dev.marregui.comando.core.model;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Several instances on one machine tell themselves apart by name (record 122).
class InstanceNameTest {

    /// An instance's name is the data root's folder name after `.comando` and its separator, the folder name itself
    /// when it has no such prefix, and empty for the main instance; a name given explicitly wins, trimmed, and a bare
    /// `.comando_` has nothing after its separator and keeps `comando_`.
    @Test
    void theNameComesFromTheDataRootUnlessGiven() {
        assertThat(InstanceName.of(Path.of("/Users/m/.comando"), null)).as("the main instance").isEmpty();
        assertThat(InstanceName.of(Path.of("/Users/m/.comando_b"), null)).isEqualTo("b");
        assertThat(InstanceName.of(Path.of("/Users/m/.comando-elf"), "")).isEqualTo("elf");
        assertThat(InstanceName.of(Path.of("/tmp/fleet-b/home"), null)).isEqualTo("home");
        assertThat(InstanceName.of(Path.of("/Users/m/.comando_b"), " tink ")).as("given").isEqualTo("tink");
        assertThat(InstanceName.of(Path.of("/Users/m/.comando_"), null)).as("nothing after the separator").isEqualTo("comando_");
    }

    /// The main instance shows as `comando` and keeps the plain preferences node, another shows as `comando · name`
    /// with a node of its own, a name that already says comando is shown as given, and characters unfit for a node
    /// become `_`.
    @Test
    void theMainInstanceKeepsItsDisplayAndPreferences() {
        assertThat(InstanceName.display("")).isEqualTo("comando");
        assertThat(InstanceName.display("b")).isEqualTo("comando · b");
        assertThat(InstanceName.display("Comando Expert")).as("a name that says comando").isEqualTo("Comando Expert");
        assertThat(InstanceName.prefsNode("")).isEqualTo("dev/marregui/comando");
        assertThat(InstanceName.prefsNode("b")).isEqualTo("dev/marregui/comando-b");
        assertThat(InstanceName.prefsNode("a/b c")).isEqualTo("dev/marregui/comando-a_b_c");
    }

    /// Only the main instance, which has no name, passes for `main` and the overseer: a name equal to `main`,
    /// `overseer` or `comando` in any case, or whose id is `main`, is refused, and so is one holding the `·` between a
    /// header's parts; a name that only contains one of them, as `mainframe` does, is a name like any other.
    @Test
    void aNameThatPassesForTheMainInstanceIsRefused() {
        for (final String name : List.of("main", "MAIN", " Main ", "main!", "-Main-", "!!!", "overseer", "Overseer",
                "comando", "COMANDO")) {
            assertThat(InstanceName.refusal(name)).as(name).hasValueSatisfying(why -> assertThat(why)
                    .startsWith("refused: only the main instance").contains("[name=" + name + ","));
        }
        assertThat(InstanceName.refusal("a · b")).hasValueSatisfying(why -> assertThat(why)
                .startsWith("refused: the name holds the separator"));
        for (final String name : List.of("", "b", "tink", "Comando Expert", "mainframe", "overseers", "comando_")) {
            assertThat(InstanceName.refusal(name)).as(name).isEmpty();
        }
        assertThat(InstanceName.refusal(null)).as("the main instance").isEmpty();
    }

    /// An instance's id is its name in lowercase letters, digits and dashes, and `main` for no name or one with none
    /// of them.
    @Test
    void theIdIsTheNameInLowercaseWithDashes() {
        assertThat(InstanceName.slug("Comando Expert")).isEqualTo("comando-expert");
        assertThat(InstanceName.slug(" b ")).isEqualTo("b");
        assertThat(InstanceName.slug("")).isEqualTo("main");
        assertThat(InstanceName.slug(null)).isEqualTo("main");
        assertThat(InstanceName.slug("!!!")).isEqualTo("main");
    }
}
