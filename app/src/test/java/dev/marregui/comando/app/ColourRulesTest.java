package dev.marregui.comando.app;

import java.awt.Color;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
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

    private static JavaClasses comando;

    @BeforeAll
    static void load() {
        comando = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("dev.marregui.comando");
    }

    /// Production classes outside the theme package neither construct `Color` nor access its fields.
    /// The imported production packages exclude test-only palette fixtures.
    @Test
    void onlyTheThemeMakesOrNamesAColour() {
        noClasses().that().resideOutsideOfPackage("dev.marregui.comando.ui.kit.theme..")
                .should().callConstructorWhere(target(owner(assignableTo(Color.class))))
                .orShould().accessFieldWhere(target(owner(assignableTo(Color.class))))
                .check(comando);
    }
}
