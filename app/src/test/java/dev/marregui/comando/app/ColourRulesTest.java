package dev.marregui.comando.app;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.Color;

import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Every colour of the window comes from the theme (record 100): no class outside {@code ui.kit.theme} makes a
 * {@link Color} or names one of Java's, so a theme changes the whole window and nothing keeps a colour of its own.
 */
class ColourRulesTest {

    private static JavaClasses comando;

    @BeforeAll
    static void load() {
        comando = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("dev.marregui.comando");
    }

    @Test
    void onlyTheThemeMakesOrNamesAColour() {
        noClasses().that().resideOutsideOfPackage("dev.marregui.comando.ui.kit.theme..")
                .should().callConstructorWhere(target(owner(assignableTo(Color.class))))
                .orShould().accessFieldWhere(target(owner(assignableTo(Color.class))))
                .check(comando);
    }
}
