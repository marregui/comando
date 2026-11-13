package dev.marregui.comando.app;

import java.awt.Color;

import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/// Colours are made and named only in `ui.kit.theme`.
///
/// - rejects direct `Color` construction outside the theme
/// - rejects references to predefined `Color` fields outside the theme
class ColourRulesTest {

    /// Production classes outside the theme package neither construct `Color` nor access its fields.
    /// The imported production packages exclude test-only palette fixtures.
    @Test
    void onlyTheThemeMakesOrNamesAColour() {
        noClasses().that().resideOutsideOfPackage("dev.marregui.comando.ui.kit.theme..")
                .should().callConstructorWhere(target(owner(assignableTo(Color.class))))
                .orShould().accessFieldWhere(target(owner(assignableTo(Color.class))))
                .check(ComandoClasses.MAIN);
    }
}
