package dev.marregui.comando.tools;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// [ToolSettings] finds the executable Gradle queue wrapper on a supplied path and exposes the running JDK home.
///
/// - The first executable wrapper in path order is selected.
/// - Missing, empty, and null paths select the checkout wrapper fallback.
/// - Child processes inherit the current Java home.
class ToolSettingsTest {

    /// [ToolSettings#of] skips empty and non-executable path entries, selects the first executable `gradle-serial`,
    /// and returns no wrapper when the path is missing or null.
    @Test
    void gradleRunsThroughTheWrapperOnThePathWhenItIsThereElseGradlew(@TempDir final Path dir) throws Exception {
        final Path first = Files.createDirectories(dir.resolve("first"));
        final Path second = Files.createDirectories(dir.resolve("second"));
        final Path notRunnable = Files.writeString(first.resolve("gradle-serial"), "#!/bin/sh\n");
        final Path serial = Files.writeString(second.resolve("gradle-serial"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(serial, PosixFilePermissions.fromString("rwxr-xr-x"));
        final String path = String.join(File.pathSeparator, "", dir.resolve("none").toString(), first.toString(), second.toString());

        assertThat(ToolSettings.of(path).gradleSerial()).as("the first that runs").contains(serial);
        Files.setPosixFilePermissions(notRunnable, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThat(ToolSettings.of(path).gradleSerial()).as("in PATH order").contains(notRunnable);
        assertThat(ToolSettings.of(dir.resolve("none").toString()).gradleSerial()).as("none: ./gradlew").isEmpty();
        assertThat(ToolSettings.of(null).gradleSerial()).isEmpty();
    }

    /// [ToolSettings#of] records the JVM's `java.home` for child processes even when no path entry is supplied.
    @Test
    void childProcessesGetTheJdkThatRunsComando() {
        assertThat(ToolSettings.of("").javaHome()).isEqualTo(System.getProperty("java.home"));
    }
}
