package dev.marregui.comando.app;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;

/// Every main class of comando, imported once per JVM for the rule tests of this package. The import reads only the
/// class folders and jars of the checkout's own modules on the test classpath: `importPackages` would open every
/// library jar to look for the package, and `DO_NOT_INCLUDE_JARS` would leave out the other modules, which reach this
/// module's tests as jars.
final class ComandoClasses {

    // Gradle runs a module's tests in the module's folder
    private static final Path ROOT = Path.of("").toAbsolutePath().getParent();

    /// The main classes of every module, this one's included; no test class.
    static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importLocations(Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                    .map(entry -> Path.of(entry).toAbsolutePath().normalize())
                    .filter(entry -> entry.startsWith(ROOT))
                    .map(entry -> Location.of(entry.toUri()))
                    .collect(Collectors.toSet()));

    private ComandoClasses() {
    }
}
