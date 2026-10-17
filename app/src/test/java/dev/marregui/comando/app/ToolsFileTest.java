package dev.marregui.comando.app;

import dev.marregui.comando.tools.ToolSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

class ToolsFileTest {

    private static Path kitSerial(Path home) throws Exception {
        Path p = Files.writeString(Files.createDirectories(home.resolve("kit/bin")).resolve("gradle-serial"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
        return p;
    }

    @Test
    void withoutTheFileGradleRunsThroughTheKitsWrapper(@TempDir Path home) throws Exception {
        Path serial = kitSerial(home);

        ToolSettings s = new ToolsFile(home, serial).read();

        assertThat(s.gradleSerial()).contains(serial);
        assertThat(s.problem()).isEmpty();
    }

    @Test
    void ensureWritesTheTemplateOnceAndItReadsAsTheDefaults(@TempDir Path home) throws Exception {
        Path serial = kitSerial(home);
        ToolsFile file = new ToolsFile(home, serial);

        Path written = file.ensure();
        assertThat(written).isEqualTo(home.resolve("tools.json"));
        assertThat(Files.readString(written)).isEqualTo(ToolsFile.TEMPLATE);
        assertThat(file.read().gradleSerial()).contains(serial);
        assertThat(file.read().problem()).isEmpty();

        Files.writeString(written, "{\"gradleWrapper\": \"none\"}");
        file.ensure();
        assertThat(Files.readString(written)).as("the user's file stands").isEqualTo("{\"gradleWrapper\": \"none\"}");
    }

    @Test
    void anEditAppliesToTheNextRead(@TempDir Path home) throws Exception {
        Path serial = kitSerial(home);
        ToolsFile file = new ToolsFile(home, serial);
        file.ensure();

        Files.writeString(file.path(), "{\"gradleWrapper\": \"none\", \"later\": 1}");

        ToolSettings s = file.read();
        assertThat(s.gradleSerial()).isEmpty();
        assertThat(s.problem()).as("an unknown key is ignored").isEmpty();
    }

    @Test
    void whatIsWrongWithTheFileIsSaidAndTheDefaultsApply(@TempDir Path home) throws Exception {
        Path serial = kitSerial(home);
        ToolsFile file = new ToolsFile(home, serial);

        Files.writeString(file.path(), "{\"gradleWrapper\": \"none\",");
        ToolSettings s = file.read();
        assertThat(s.gradleSerial()).as("defaults").contains(serial);
        assertThat(s.problem()).startsWith("tools.json: ").endsWith("; using the defaults").doesNotContain("\n");
        assertThat(file.path()).as("the user's text is not moved aside").exists();

        Files.writeString(file.path(), "{\"gradleWrapper\": \"none\"} {\"later\": \"x\"}");
        assertThat(file.read().problem()).as("a second document").startsWith("tools.json: ").endsWith("; using the defaults");
        assertThat(file.read().gradleSerial()).contains(serial);

        Files.writeString(file.path(), "[\"none\"]");
        assertThat(file.read().problem()).isEqualTo("tools.json: not a JSON object; using the defaults");

        Files.writeString(file.path(), "{\"gradleWrapper\": 5, \"later\": null}");
        assertThat(file.read().problem()).isEqualTo("tools.json: gradleWrapper is not a string; using its default");
        assertThat(file.read().gradleSerial()).contains(serial);

        Files.writeString(file.path(), "");
        assertThat(file.read().problem()).as("an empty file is every default").isEmpty();
    }
}
