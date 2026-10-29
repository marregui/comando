package dev.marregui.comando.ui;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/// The package rules of the UI (plan §4.6, architecture §8), checked on its main classes: each layer uses only those
/// above it (kit, widget, widgets, panels, dialogs, shell), so widgets do not duplicate each other.
/// - No widget imports another widget.
/// - Widgets use only the kit, the contract and core.
/// - The kit depends on no layer below it, the contract depends on no widget, panel, dialog or shell.
/// - Panels depend on no dialog or shell, dialogs on no shell.
/// - The UI calls no adapter module and no JDBC.
class UiArchitectureTest {

    private static final String PANEL_TEXTS = "dev.marregui.comando.ui.PanelTexts";
    private static JavaClasses ui;

    @BeforeAll
    static void load() {
        ui = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("dev.marregui.comando.ui");
    }

    /// The slices under `ui.widgets` do not depend on each other.
    @Test
    void noWidgetImportsAnotherWidget() {
        slices().matching("dev.marregui.comando.ui.widgets.(*)..").should().notDependOnEachOther().check(ui);
    }

    /// No class in `ui.widgets` depends on `ui.panels`, `ui.shell`, `ui.dialogs` or `PanelTexts`.
    @Test
    void widgetsUseOnlyTheKitTheContractAndCore() {
        noClasses().that().resideInAPackage("..ui.widgets..")
                .should().dependOnClassesThat().resideInAnyPackage("..ui.panels..", "..ui.shell..", "..ui.dialogs..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName(PANEL_TEXTS)
                .check(ui);
    }

    /// No class in `ui.kit` depends on `ui.widget`, `ui.widgets`, `ui.panels`, `ui.dialogs`, `ui.shell` or `PanelTexts`.
    @Test
    void theKitDependsOnNoWidget() {
        noClasses().that().resideInAPackage("..ui.kit..")
                .should().dependOnClassesThat().resideInAnyPackage("..ui.widgets..", "..ui.widget..", "..ui.panels..", "..ui.dialogs..", "..ui.shell..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName(PANEL_TEXTS)
                .check(ui);
    }

    /// No class in `ui.widget`, the contract, depends on `ui.widgets`, `ui.panels`, `ui.dialogs`, `ui.shell` or `PanelTexts`.
    @Test
    void theContractDependsOnNoWidget() {
        noClasses().that().resideInAPackage("..ui.widget..")
                .should().dependOnClassesThat().resideInAnyPackage("..ui.widgets..", "..ui.panels..", "..ui.dialogs..", "..ui.shell..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName(PANEL_TEXTS)
                .check(ui);
    }

    /// No class in `ui.panels` depends on `ui.dialogs` or `ui.shell`, and none in `ui.dialogs` on `ui.shell`.
    @Test
    void panelsAndDialogsUseNothingBelowThem() {
        noClasses().that().resideInAPackage("..ui.panels..")
                .should().dependOnClassesThat().resideInAnyPackage("..ui.dialogs..", "..ui.shell..")
                .check(ui);
        noClasses().that().resideInAPackage("..ui.dialogs..")
                .should().dependOnClassesThat().resideInAPackage("..ui.shell..")
                .check(ui);
    }

    /// No UI class depends on the `db`, `claude`, `mcp` or `tools` modules or on `java.sql`.
    @Test
    void theUiCallsNoAdapter() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage("dev.marregui.comando.db..", "dev.marregui.comando.claude..",
                "dev.marregui.comando.mcp..", "dev.marregui.comando.tools..", "java.sql..").check(ui);
    }
}
