package dev.marregui.comando.cdb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/// The cdb module is reached through its facade only, by comando's cdb adapter only.
/// - No class outside the module but the `Cdb*` classes of `dev.marregui.comando.db` depends on cdb.
/// - Those classes use only the types of the package `dev.marregui.comando.cdb` itself, none of its subpackages.
///
/// The classes are every module's compiled main classes in the checkout, read from their class folders: the other
/// modules are not on this module's test classpath.
class CdbArchitectureTest {

    private static final String ADAPTER = "dev.marregui.comando.db.CdbAdapter";
    private static final DescribedPredicate<JavaClass> ADAPTER_CLASS = DescribedPredicate.describe(
            "are the Cdb* classes of dev.marregui.comando.db",
            c -> c.getName().startsWith(CdbArchitectureTest.ADAPTER_CLASSES));
    private static final String ADAPTER_CLASSES = "dev.marregui.comando.db.Cdb";
    private static final DescribedPredicate<JavaClass> BELOW_CDB = DescribedPredicate.describe(
            "reside in a subpackage of dev.marregui.comando.cdb",
            c -> c.getPackageName().startsWith(CdbArchitectureTest.CDB + '.'));
    private static final String CDB = "dev.marregui.comando.cdb";
    private static final DescribedPredicate<JavaClass> IN_CDB = DescribedPredicate.describe(
            "reside in dev.marregui.comando.cdb or below", c -> c.getPackageName().equals(CDB)
                    || c.getPackageName().startsWith(CDB + '.'));
    // Gradle runs a module's tests in the module's folder
    private static final Path ROOT = Path.of("").toAbsolutePath().getParent();
    // after ROOT, which the import reads
    private static final JavaClasses MAIN = importMain();

    /// The import holds the adapter and the facade, so a module left unbuilt cannot pass the rules by holding nothing.
    @Test
    void theClassesHoldTheAdapterAndTheFacade() {
        assertThat(MAIN.contain(ADAPTER)).as("db's classes are built: run :db:compileJava first").isTrue();
        assertThat(MAIN.contain(Cdb.class)).isTrue();
    }

    /// Only the adapter's classes reach into cdb from outside it.
    @Test
    void onlyTheAdaptersClassesDependOnCdb() {
        noClasses().that(DescribedPredicate.not(IN_CDB)).and(DescribedPredicate.not(ADAPTER_CLASS))
                .should().dependOnClassesThat(IN_CDB)
                .check(MAIN);
    }

    /// The adapter's classes use the facade's package and nothing below it.
    @Test
    void theAdaptersClassesUseOnlyTheFacadesPackage() {
        noClasses().that(ADAPTER_CLASS)
                .should().dependOnClassesThat(BELOW_CDB)
                .check(MAIN);
    }

    private static JavaClasses importMain() {
        final List<Location> folders;
        try (Stream<Path> modules = Files.list(ROOT)) {
            folders = modules.map(m -> m.resolve("build/classes/java/main"))
                    .filter(Files::isDirectory)
                    .map(f -> Location.of(f.toUri()))
                    .toList();
        } catch (final IOException e) {
            throw new IllegalStateException("cannot list the modules [root=" + ROOT + ']', e);
        }
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importLocations(folders);
    }
}
