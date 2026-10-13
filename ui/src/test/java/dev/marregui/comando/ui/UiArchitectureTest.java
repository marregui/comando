package dev.marregui.comando.ui;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/** The package rules of the UI (plan §4.6): they keep widgets from duplicating each other. */
class UiArchitectureTest {

    private static JavaClasses ui;

    @BeforeAll
    static void load() {
        ui = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("dev.marregui.comando.ui");
    }

    @Test
    void noWidgetImportsAnotherWidget() {
        slices().matching("dev.marregui.comando.ui.widgets.(*)..").should().notDependOnEachOther().check(ui);
    }

    @Test
    void widgetsUseOnlyTheKitTheContractAndCore() {
        noClasses().that().resideInAPackage("..ui.widgets..")
                .should().dependOnClassesThat().resideInAnyPackage("..ui.panels..", "..ui.shell..", "..ui.dialogs..")
                .check(ui);
    }

    @Test
    void theKitDependsOnNoWidget() {
        noClasses().that().resideInAPackage("..ui.kit..")
                .should().dependOnClassesThat().resideInAnyPackage("..ui.widgets..", "..ui.widget..", "..ui.panels..", "..ui.shell..")
                .check(ui);
    }

    @Test
    void theUiCallsNoAdapter() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage("dev.marregui.comando.db..", "dev.marregui.comando.claude..",
                "dev.marregui.comando.mcp..", "dev.marregui.comando.tools..", "java.sql..").check(ui);
    }
}
