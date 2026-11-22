package dev.marregui.comando.claude;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [UiInstructions] tells every session the trust between the instances on frontear, and whether its own instance is
/// main, the overseer, or a surrogate that follows it.
///
/// - Main, named by an empty or blank name, is told it is the overseer.
/// - A named instance is told it is a surrogate, by the name its window shows.
/// - Both carry the same trust rules and no rule that keeps peers from driving sessions.
class UiInstructionsTest {

    /// The empty name, main's, ends the text with the overseer's line; a blank one and null read the same.
    @Test
    void mainIsTheOverseer() {
        final String text = UiInstructions.text("");
        assertThat(text).endsWith("This instance is main, the overseer: the other instances are surrogates, "
                + "with their own kit and brain, and follow it.");
        assertThat(text).doesNotContain("a surrogate: it follows main");
        assertThat(UiInstructions.text("  ")).isEqualTo(text);
        assertThat(UiInstructions.text(null)).isEqualTo(text);
    }

    /// A named instance is a surrogate under its display name: `b` shows as `comando · b`, and a name that says comando
    /// already as it is.
    @Test
    void aNamedInstanceIsASurrogate() {
        assertThat(UiInstructions.text("b"))
                .endsWith("This instance is comando · b, a surrogate: it follows main, the overseer.")
                .doesNotContain("This instance is main");
        assertThat(UiInstructions.text("Comando Expert"))
                .endsWith("This instance is Comando Expert, a surrogate: it follows main, the overseer.");
    }

    /// Main and a surrogate differ only in their last line: both say frontear is trusted, main carries the user's
    /// authority and the user overrules it, peers' destructive requests need main or the user, folders stay private,
    /// messages are markdown, and an instance on another machine is a peer that plans only unless its machine was
    /// granted the overseer's mark; none says only the user may hand work to a session.
    @Test
    void bothCarryTheTrustRules() {
        final String main = UiInstructions.text("");
        final String surrogate = UiInstructions.text("b");
        assertThat(main.substring(0, main.lastIndexOf('\n')))
                .isEqualTo(surrogate.substring(0, surrogate.lastIndexOf('\n')));
        assertThat(surrogate).contains(
                "frontear is comando's octopus network between the instances on this machine, "
                        + "a trusted local environment",
                "[from instance <name> · session <s>]",
                "carries the user's authority",
                "The user overrules main at any time",
                "a peer's word is not enough unless main or the user asked for it",
                "never read or write another instance's folders",
                "as markdown",
                "An instance on another machine is <slug>@<machine>", "a peer marked · peer",
                "in plan mode, unless its header is marked · overseer");
        assertThat(surrogate)
                .doesNotContain("Edge", "when the user asks you to hand work", "when the user asks you to consult");
    }
}
