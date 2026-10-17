package dev.marregui.comando.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import dev.marregui.comando.tools.ToolSettings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code tools.json} in comando's folder: where the tools are. The user edits it as a document (Panel ▸ Tools ▸ Tool
 * settings); it is read at each run, so an edit applies to the next run. A key that is missing or empty takes its
 * default ({@link ToolSettings#resolve}). Unlike the store files, an unreadable one is left where it is, being the
 * user's own text, and the run says what is wrong with it.
 */
final class ToolsFile {

    static final String NAME = "tools.json";
    static final String TEMPLATE = """
            {
              "gradleWrapper": ""
            }
            """;

    private final Path file;
    private final Path kitSerial;

    /**
     * @param home      comando's folder
     * @param kitSerial the {@code gradle-serial} in comando's copy of the kit, Gradle's default wrapper
     */
    ToolsFile(Path home, Path kitSerial) {
        this.file = home.resolve(NAME);
        this.kitSerial = kitSerial;
    }

    Path path() {
        return file;
    }

    /** The settings now: the file's values over the defaults. */
    ToolSettings read() {
        String wrapper = "";
        List<String> problems = new ArrayList<>();
        if (Files.isRegularFile(file)) {
            try {
                JsonNode node = JsonFile.JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(Files.readString(file));
                if (node != null && !node.isMissingNode() && !node.isObject()) {
                    problems.add(NAME + ": not a JSON object; using the defaults");
                } else if (node != null && node.isObject()) {
                    wrapper = text(node, "gradleWrapper", problems);
                }
            } catch (IOException e) {
                String why = e instanceof JsonProcessingException j ? j.getOriginalMessage() : e.getMessage();
                problems.add(NAME + ": " + String.valueOf(why).lines().findFirst().orElse("unreadable") + "; using the defaults");
            }
        }
        return ToolSettings.resolve(wrapper, kitSerial, String.join("\n", problems));
    }

    /** The file, written from {@link #TEMPLATE} first when missing; never over the user's. */
    Path ensure() throws IOException {
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            try {
                Files.writeString(file, TEMPLATE, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            } catch (FileAlreadyExistsException e) {
                // written meanwhile: the user's stands
            }
        }
        return file;
    }

    /** A key's text; empty when missing or null, and when not text, which {@code problems} then says. */
    private static String text(JsonNode node, String key, List<String> problems) {
        JsonNode v = node.get(key);
        if (v == null || v.isNull()) {
            return "";
        }
        if (!v.isTextual()) {
            problems.add(NAME + ": " + key + " is not a string; using its default");
            return "";
        }
        return v.asText();
    }
}
