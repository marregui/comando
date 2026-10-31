package dev.marregui.comando.core.model;

import java.nio.file.Path;

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
}
