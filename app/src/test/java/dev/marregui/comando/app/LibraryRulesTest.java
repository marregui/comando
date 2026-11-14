package dev.marregui.comando.app;

import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/// The libraries comando replaced with its own code stay out (record 157).
///
/// - JSON is `core.json`, not Jackson
/// - MQTT is `app.mqtt`, not the HiveMQ MQTT client or Netty
/// - diffs, SQL completion and SVG icons are comando's, not java-diff-utils, AutoComplete or FlatLaf Extras
class LibraryRulesTest {

    /// No production class of any module depends on a class of a replaced library.
    @Test
    void noMainClassUsesAReplacedLibrary() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage(
                        "tools.jackson..",
                        "com.fasterxml.jackson..",
                        "com.hivemq.client..",
                        "io.netty..",
                        "io.reactivex..",
                        "com.github.difflib..",
                        "org.fife.ui.autocomplete..",
                        "com.formdev.flatlaf.extras..")
                .check(ComandoClasses.MAIN);
    }
}
