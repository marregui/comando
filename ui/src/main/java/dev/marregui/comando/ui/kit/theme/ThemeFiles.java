package dev.marregui.comando.ui.kit.theme;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The user's themes (record 126): every {@code *.json} in a folder, the data root's {@code themes}, read once at start
 * in the order of their names. A file that does not parse, repeats an id, or reads below a floor of
 * {@link ThemeRules} is not offered, and its problem is reported for a notice.
 */
public final class ThemeFiles {

    /** The folder's themes, in the order of their files' names, and one line per file left out. */
    public record Read(List<NamedTheme> themes, List<String> problems) {
    }

    private ThemeFiles() {
    }

    /** The themes in {@code folder}; none and no problem when it is null or does not exist. */
    public static Read read(Path folder) {
        List<NamedTheme> themes = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        if (folder == null || !Files.isDirectory(folder)) {
            return new Read(themes, problems);
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(folder)) {
            files = s.filter(f -> f.getFileName().toString().endsWith(".json") && Files.isRegularFile(f)).sorted().toList();
        } catch (IOException e) {
            problems.add(folder + ": cannot be listed: " + e.getMessage());
            return new Read(themes, problems);
        }
        Set<String> ids = new HashSet<>(NamedTheme.builtIn().stream().map(t -> t.id).toList());
        for (Path f : files) {
            String name = f.getFileName().toString();
            try {
                ThemeSpec spec = ThemeSpec.parse(Files.readString(f, StandardCharsets.UTF_8));
                if (!ids.add(spec.id())) {
                    problems.add(name + ": the id " + spec.id() + " is another theme's");
                    continue;
                }
                NamedTheme t = NamedTheme.fromFile(spec, f);
                List<String> low = ThemeRules.problems(t.ground, t.tint);
                if (!low.isEmpty()) {
                    problems.add(name + ": " + low.getFirst() + (low.size() > 1 ? " (and " + (low.size() - 1) + " more)" : ""));
                    continue;
                }
                themes.add(t);
            } catch (IllegalArgumentException e) {
                problems.add(name + ": " + e.getMessage());
            } catch (IOException e) {
                problems.add(name + ": cannot be read: " + e.getMessage());
            }
        }
        return new Read(themes, problems);
    }
}
