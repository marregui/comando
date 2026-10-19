package dev.marregui.comando.claude;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

class KitTest {

    private static final Map<String, String> FILES = new LinkedHashMap<>();

    static {
        FILES.put(".claude-plugin/plugin.json", "{\"name\":\"comando\",\"version\":\"1.2.3\"}");
        FILES.put("bin/gradle-serial", "#!/bin/sh\n");
        FILES.put("hooks/mermaid-check.py", "print()\n");
        FILES.put("hooks/method.md", "# method\n");
        FILES.put("skeleton/KB/00_START_HERE.md", "> **Last Updated:** <date>\n");
        FILES.put("skeleton/SCRATCHPAD/README.md", "# scratchpad\n");
    }

    /** A bundle from a map, so a test can change what the build carries. */
    private static Kit.Bundle bundle(Map<String, String> files) {
        return new Kit.Bundle() {
            @Override
            public List<String> files() {
                return List.copyOf(files.keySet());
            }

            @Override
            public byte[] read(String file) throws IOException {
                String text = files.get(file);
                if (text == null) {
                    throw new IOException("no " + file);
                }
                return text.getBytes(StandardCharsets.UTF_8);
            }
        };
    }

    /** Answers {@code claude} runs by their first arguments, and records every run. */
    private static final class ScriptedCli implements Kit.Cli {
        final List<String> runs = new ArrayList<>();
        final Map<String, Output> answers = new LinkedHashMap<>();

        ScriptedCli answer(String args, int exit, String text) {
            answers.put(args, new Output(exit, text));
            return this;
        }

        @Override
        public Output run(List<String> args) {
            String line = String.join(" ", args);
            runs.add(line);
            return answers.entrySet().stream().filter(e -> line.startsWith(e.getKey())).map(Map.Entry::getValue).findFirst()
                    .orElseThrow(() -> new AssertionError("unexpected run: claude " + line));
        }

        /** The runs that change Claude Code: anything but a list. */
        List<String> changes() {
            return runs.stream().filter(r -> !r.endsWith("list --json")).toList();
        }
    }

    private static String setMemory(Path claude) {
        return "Set autoMemoryDirectory to " + claude.resolve("MEMORY") + " in " + claude.resolve("settings.json")
                + ": every project then shares one memory, whose MEMORY.md index each session loads (up to 200 lines or 25 KB);"
                + " memories kept per project so far stay where they are";
    }

    private static ScriptedCli notInstalled() {
        return new ScriptedCli()
                .answer("plugin list", 0, "[{\"id\":\"other@x\",\"version\":\"1\",\"scope\":\"user\"}]")
                .answer("plugin marketplace list", 0, "[]")
                .answer("plugin marketplace add", 0, "{\"outcome\":\"ok\",\"message\":\"added\"}")
                .answer("plugin install", 0, "{\"outcome\":\"ok\",\"message\":\"installed\"}")
                .answer("plugin uninstall", 0, "{\"outcome\":\"ok\"}")
                .answer("plugin marketplace remove", 0, "{\"outcome\":\"ok\"}");
    }

    private static ScriptedCli installed(boolean enabled) {
        return new ScriptedCli().answer("plugin list", 0, "[{\"id\":\"comando@comando\",\"version\":\"1.0.0\",\"folderVersion\":\"1.2.3\","
                + "\"scope\":\"user\",\"enabled\":" + enabled + ",\"readFromFolder\":\"/k/kit\"}]")
                .answer("plugin enable", 0, "{\"outcome\":\"ok\"}");
    }

    @Test
    void refreshWritesTheBuildsFilesMarksScriptsExecutableAndDropsWhatTheBuildNoLongerCarries(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("kit");
        Kit kit = new Kit(bundle(FILES), new ScriptedCli(), dir, tmp.resolve("claude"));

        assertThat(kit.refresh()).isTrue();
        assertThat(Files.readString(dir.resolve("hooks/method.md"))).isEqualTo("# method\n");
        assertThat(Files.isExecutable(dir.resolve("bin/gradle-serial"))).isTrue();
        assertThat(Files.isExecutable(dir.resolve("hooks/mermaid-check.py"))).isTrue();
        assertThat(Files.isExecutable(dir.resolve("hooks/method.md"))).as("only scripts").isFalse();
        assertThat(kit.refresh()).as("already equal").isFalse();

        Files.writeString(dir.resolve("hooks/method.md"), "edited by hand");
        Files.writeString(dir.resolve("hooks/old.py"), "from an earlier build");
        assertThat(kit.refresh()).isTrue();
        assertThat(Files.readString(dir.resolve("hooks/method.md"))).isEqualTo("# method\n");
        assertThat(dir.resolve("hooks/old.py")).doesNotExist();
        try (Stream<Path> left = Files.list(dir.resolve("hooks"))) {
            assertThat(left.map(p -> p.getFileName().toString())).as("no temporary file left").containsExactlyInAnyOrder("mermaid-check.py", "method.md");
        }
    }

    @Test
    void refreshKeepsTheKitWhenTheDataRootIsWrittenWithDotSegments(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("./data/x/..").resolve("kit");
        Kit kit = new Kit(bundle(FILES), new ScriptedCli(), dir, tmp.resolve("claude"));

        kit.refresh();

        assertThat(tmp.resolve("data/kit/hooks/method.md")).as("the file refresh just wrote").exists();
        assertThat(kit.gradleSerial()).isEqualTo(tmp.resolve("data/kit/bin/gradle-serial")).exists();
        assertThat(kit.refresh()).as("nothing deleted and written again").isFalse();
    }

    @Test
    void refreshRefusesAnIndexEntryThatLeavesTheKitsFolder(@TempDir Path tmp) {
        Map<String, String> files = new LinkedHashMap<>(FILES);
        files.put("../outside.txt", "x");
        Kit kit = new Kit(bundle(files), new ScriptedCli(), tmp.resolve("kit"), tmp.resolve("claude"));

        assertThatThrownBy(kit::refresh).isInstanceOf(IOException.class).hasMessageContaining("outside");
        assertThat(tmp.resolve("outside.txt")).doesNotExist();
    }

    @Test
    void statusListsEveryChangeAnInstallWouldMake(@TempDir Path tmp) throws Exception {
        Path claude = tmp.resolve("claude");
        Files.createDirectories(claude.resolve("KB"));
        Files.writeString(claude.resolve("KB/00_START_HERE.md"), "mine");
        ScriptedCli cli = notInstalled();
        Kit kit = new Kit(bundle(FILES), cli, tmp.resolve("kit"), claude);

        Kit.Status status = kit.status();

        assertThat(status.version()).isEqualTo("1.2.3");
        assertThat(status.installed()).isFalse();
        assertThat(status.alreadySetUp()).isFalse();
        assertThat(status.changes()).containsExactly(
                setMemory(claude),
                "Register " + tmp.resolve("kit") + " with Claude Code as the plugin marketplace 'comando'",
                "Install the plugin comando@comando 1.2.3 for your user (Claude Code adds it to its settings)",
                "Create " + claude.resolve("SCRATCHPAD/README.md"));
        assertThat(cli.changes()).as("a status changes nothing").isEmpty();
    }

    @Test
    void aSetupOfTheUsersOwnKeepsTheKitOutAndIsNeverDuplicated(@TempDir Path tmp) throws Exception {
        for (String own : List.of("skills/reflect", "skills/deep-review", "commands/reflect.md", "commands/deep-review.md")) {
            Path claude = tmp.resolve(own.replace('/', '-'));
            Path p = claude.resolve(own);
            if (own.endsWith(".md")) {
                Files.createDirectories(p.getParent());
                Files.writeString(p, "my own");
            } else {
                Files.createDirectories(p);
            }
            ScriptedCli cli = notInstalled();
            Kit kit = new Kit(bundle(FILES), cli, tmp.resolve("kit"), claude);

            Kit.Status status = kit.status();
            assertThat(status.existing()).as(own).containsExactly(p.toString());
            assertThat(status.alreadySetUp()).as(own).isTrue();
            assertThat(status.changes()).as(own + ": nothing to install").isEmpty();
            assertThatThrownBy(kit::install).as(own).isInstanceOf(IOException.class)
                    .hasMessage("a setup of your own was found (" + p + "); the kit would duplicate it, so nothing was installed");
            assertThat(cli.changes()).as(own + ": no second /reflect or /deep-review").isEmpty();
            assertThat(claude.resolve("KB")).as(own + ": no skeleton either").doesNotExist();
        }
    }

    @Test
    void installRegistersTheCopyInstallsThePluginAndWritesOnlyTheMissingSkeletonFiles(@TempDir Path tmp) throws Exception {
        Path claude = tmp.resolve("claude");
        Path dir = tmp.resolve("kit");
        Files.createDirectories(claude.resolve("KB"));
        Files.writeString(claude.resolve("KB/00_START_HERE.md"), "mine");
        ScriptedCli cli = notInstalled();
        Kit kit = new Kit(bundle(FILES), cli, dir, claude);

        List<String> done = kit.install();

        assertThat(cli.runs).containsExactly("plugin list --json", "plugin marketplace list --json",
                "plugin marketplace add " + dir + " --json", "plugin install comando@comando -y --json");
        assertThat(Files.isExecutable(dir.resolve("bin/gradle-serial"))).as("the copy is written before it is registered").isTrue();
        assertThat(Files.readString(claude.resolve("KB/00_START_HERE.md"))).as("an existing file is never replaced").isEqualTo("mine");
        assertThat(Files.readString(claude.resolve("SCRATCHPAD/README.md"))).isEqualTo("# scratchpad\n");
        assertThat(done).containsExactly("Set autoMemoryDirectory to " + claude.resolve("MEMORY"),
                "Registered the marketplace 'comando' at " + dir, "Installed comando@comando 1.2.3",
                "Created " + claude.resolve("SCRATCHPAD/README.md"));
        assertThat(Files.readString(claude.resolve("settings.json"))).isEqualTo("{\n  \"autoMemoryDirectory\": \"" + claude.resolve("MEMORY") + "\"\n}\n");
    }

    @Test
    void installDatesTheSkeletonFiles(@TempDir Path tmp) throws Exception {
        Path claude = tmp.resolve("claude");
        new Kit(bundle(FILES), notInstalled(), tmp.resolve("kit"), claude).install();

        assertThat(Files.readString(claude.resolve("KB/00_START_HERE.md"))).isEqualTo("> **Last Updated:** " + LocalDate.now() + "\n");
    }

    @Test
    void aMarketplaceOfThatNameReadingAnotherFolderIsPointedAtComandosCopy(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("kit"));
        ScriptedCli elsewhere = notInstalled()
                .answer("plugin marketplace list", 0, "[{\"name\":\"comando\",\"source\":\"directory\",\"path\":\"/clone/kit\"}]")
                .answer("plugin marketplace remove", 0, "{\"outcome\":\"ok\"}");
        Kit kit = new Kit(bundle(FILES), elsewhere, dir, tmp.resolve("c1"));

        assertThat(kit.status().changes().get(1))
                .isEqualTo("Point Claude Code's plugin marketplace 'comando' at " + dir
                        + " (it now reads /clone/kit; Claude Code uninstalls what was installed from it, in any project)");
        kit.install();
        assertThat(elsewhere.changes()).containsExactly("plugin marketplace remove comando --json",
                "plugin marketplace add " + dir + " --json", "plugin install comando@comando -y --json");

        ScriptedCli github = notInstalled().answer("plugin marketplace list", 0, "[{\"name\":\"comando\",\"source\":\"github\"}]")
                .answer("plugin marketplace remove", 0, "{\"outcome\":\"ok\"}");
        assertThat(new Kit(bundle(FILES), github, dir, tmp.resolve("c2")).status().changes().get(1))
                .startsWith("Point Claude Code's plugin marketplace 'comando' at " + dir + " (it now reads github;");

        // the same folder, as Claude Code reports it with links resolved (/var is /private/var on macOS)
        ScriptedCli same = notInstalled()
                .answer("plugin marketplace list", 0, "[{\"name\":\"comando\",\"path\":\"" + dir.toRealPath() + "\"}]");
        new Kit(bundle(FILES), same, dir, tmp.resolve("c3")).install();
        assertThat(same.changes()).containsExactly("plugin install comando@comando -y --json");
    }

    @Test
    void anInstalledKitReportsTheVersionThatLoadsAndWhereFrom(@TempDir Path tmp) throws Exception {
        ScriptedCli cli = installed(true);
        Kit kit = new Kit(bundle(FILES), cli, tmp.resolve("kit"), tmp.resolve("claude"));

        Kit.Status status = kit.status();
        assertThat(status.installedVersion()).as("folderVersion, not the version recorded at install").isEqualTo("1.2.3");
        assertThat(status.enabled()).isTrue();
        assertThat(status.loadsFrom()).isEqualTo("/k/kit");
        assertThat(status.fromCopy()).as("a folder other than comando's copy").isFalse();
        assertThat(status.loadsCopy()).isFalse();
        assertThat(status.changes()).containsExactly(setMemory(tmp.resolve("claude")), "Create " + tmp.resolve("claude/KB/00_START_HERE.md"),
                "Create " + tmp.resolve("claude/SCRATCHPAD/README.md"));
        kit.install();
        assertThat(cli.changes()).as("nothing but the skeleton").isEmpty();
    }

    @Test
    void aDisabledKitIsEnabled(@TempDir Path tmp) throws Exception {
        ScriptedCli cli = installed(false);
        Kit kit = new Kit(bundle(FILES), cli, tmp.resolve("kit"), tmp.resolve("claude"));

        assertThat(kit.status().changes()).contains("Enable the plugin comando@comando for your user");
        assertThat(kit.install()).contains("Enabled comando@comando");
        assertThat(cli.changes()).containsExactly("plugin enable comando@comando --scope user --json");
    }

    @Test
    void aDisabledKitIsNotEnabledBesideASetupOfTheUsersOwn(@TempDir Path tmp) throws Exception {
        Path claude = tmp.resolve("claude");
        Files.createDirectories(claude.resolve("skills/reflect"));
        ScriptedCli cli = installed(false);
        Kit kit = new Kit(bundle(FILES), cli, tmp.resolve("kit"), claude);

        Kit.Status status = kit.status();
        assertThat(status.alreadySetUp()).isTrue();
        assertThat(status.changes()).isEmpty();
        assertThatThrownBy(kit::install).isInstanceOf(IOException.class)
                .hasMessage("a setup of your own was found (" + claude.resolve("skills/reflect") + "); the kit would duplicate it, so nothing was enabled");
        assertThat(cli.changes()).as("no second /reflect").isEmpty();
    }

    @Test
    void anInstalledKitLoadingComandosCopyIsSaidToLoadIt(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("kit"));
        ScriptedCli cli = new ScriptedCli().answer("plugin list", 0, "[{\"id\":\"comando@comando\",\"version\":\"1.2.3\",\"scope\":\"user\","
                + "\"enabled\":true,\"readFromFolder\":\"" + dir.toRealPath() + "\"}]");

        Kit.Status status = new Kit(bundle(FILES), cli, dir, tmp.resolve("claude")).status();

        assertThat(status.fromCopy()).isTrue();
        assertThat(status.loadsCopy()).isTrue();
    }

    @Test
    void aProjectInstallIsNotTheUsersInstall(@TempDir Path tmp) throws Exception {
        ScriptedCli cli = new ScriptedCli().answer("plugin list", 0, "[{\"id\":\"comando@comando\",\"version\":\"1.0.0\",\"scope\":\"project\"}]")
                .answer("plugin marketplace list", 0, "[]");

        assertThat(new Kit(bundle(FILES), cli, tmp.resolve("kit"), tmp.resolve("claude")).status().installed()).isFalse();
    }

    @Test
    void aFailedStepSaysWhatClaudeCodeAnsweredAndWhatWasDoneBeforeIt(@TempDir Path tmp) throws Exception {
        Path claude = tmp.resolve("claude");
        // the shape of Claude Code 2.1.301: exit 1, the JSON result line, then a line for people
        ScriptedCli refused = notInstalled().answer("plugin install", 1, """
                {"command":"install","outcome":"failed","plugin":"comando@comando","message":"Plugin \\"comando\\" not found in marketplace \\"comando\\""}
                ✘ Failed to install plugin "comando@comando\"""");
        Kit kit = new Kit(bundle(FILES), refused, tmp.resolve("kit"), claude);
        assertThatThrownBy(kit::install).isInstanceOf(IOException.class).hasMessage("claude plugin install: Plugin \"comando\" not found in"
                + " marketplace \"comando\"; undone: removed the marketplace 'comando', took autoMemoryDirectory out of " + claude.resolve("settings.json"));
        assertThat(claude.resolve("SCRATCHPAD/README.md")).as("no skeleton after a failed install").doesNotExist();
        assertThat(claude.resolve("settings.json")).as("whole or none: the settings file comando created is gone").doesNotExist();

        ScriptedCli okButRefused = notInstalled().answer("plugin marketplace add", 0, "{\"outcome\":\"failed\",\"message\":\"Path does not exist\"}");
        assertThatThrownBy(() -> new Kit(bundle(FILES), okButRefused, tmp.resolve("kit"), claude).install())
                .hasMessage("claude plugin marketplace add: Path does not exist; undone: took autoMemoryDirectory out of " + claude.resolve("settings.json"));

        ScriptedCli crashed = notInstalled().answer("plugin marketplace add", 1, "\n Error: permission denied\n  at x");
        assertThatThrownBy(() -> new Kit(bundle(FILES), crashed, tmp.resolve("kit"), claude).install()).isInstanceOf(IOException.class)
                .hasMessage("claude plugin marketplace add failed (exit 1): Error: permission denied; undone: took autoMemoryDirectory out of "
                        + claude.resolve("settings.json"));

        ScriptedCli garbled = new ScriptedCli().answer("plugin list", 0, "Welcome to Claude Code!");
        assertThatThrownBy(() -> new Kit(bundle(FILES), garbled, tmp.resolve("kit"), claude).status()).isInstanceOf(IOException.class)
                .hasMessage("claude plugin list did not answer in JSON: Welcome to Claude Code!");
    }

    @Test
    void aStepThatFailsUndoesTheStepsBeforeItLastFirst(@TempDir Path tmp) throws Exception {
        Path claude = Files.createDirectories(tmp.resolve("claude"));
        Files.writeString(claude.resolve("settings.json"), CLAUDE_SETTINGS);
        Files.writeString(claude.resolve("SCRATCHPAD"), "a file where the folder goes");
        ScriptedCli cli = notInstalled();

        assertThatThrownBy(() -> new Kit(bundle(FILES), cli, tmp.resolve("kit"), claude).install())
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith("cannot create " + claude.resolve("SCRATCHPAD/README.md") + ": ")
                .hasMessageEndingWith("; undone: deleted " + claude.resolve("KB/00_START_HERE.md") + ", uninstalled comando@comando,"
                        + " removed the marketplace 'comando', took autoMemoryDirectory out of " + claude.resolve("settings.json"));
        assertThat(cli.changes()).containsExactly("plugin marketplace add " + tmp.resolve("kit") + " --json",
                "plugin install comando@comando -y --json", "plugin uninstall comando@comando -y --json",
                "plugin marketplace remove comando --json");
        assertThat(Files.readString(claude.resolve("settings.json"))).as("as Claude Code wrote it").isEqualTo(CLAUDE_SETTINGS);
        assertThat(claude.resolve("KB/00_START_HERE.md")).doesNotExist();
    }

    @Test
    void whatCannotBeUndoneIsSaid(@TempDir Path tmp) throws Exception {
        Path claude = Files.createDirectories(tmp.resolve("claude"));
        Files.writeString(claude.resolve("SCRATCHPAD"), "a file where the folder goes");
        ScriptedCli cli = notInstalled().answer("plugin uninstall", 1, "{\"outcome\":\"failed\",\"message\":\"busy\"}");

        assertThatThrownBy(() -> new Kit(bundle(FILES), cli, tmp.resolve("kit"), claude).install())
                .hasMessageContaining("; NOT undone: Installed comando@comando 1.2.3 (claude plugin uninstall: busy)");
    }

    @Test
    void anUndoKeepsWhatAnotherWriterPutInTheSettingsMeanwhile(@TempDir Path tmp) throws Exception {
        Path claude = Files.createDirectories(tmp.resolve("claude"));
        Path settings = Files.writeString(claude.resolve("settings.json"), "{\n  \"a\": 1\n}\n");
        ScriptedCli base = notInstalled();
        Kit.Cli cli = args -> {
            if (String.join(" ", args).startsWith("plugin install")) {
                // Claude Code, or the user, writes the file while the install runs
                String now = Files.readString(settings);
                Files.writeString(settings, now.replace("\"a\": 1", "\"a\": 1,\n  \"b\": 2"));
                return new Kit.Cli.Output(1, "{\"outcome\":\"failed\",\"message\":\"no\"}");
            }
            return base.run(args);
        };

        assertThatThrownBy(() -> new Kit(bundle(FILES), cli, tmp.resolve("kit"), claude).install()).hasMessageStartingWith("claude plugin install: no");
        assertThat(Files.readString(settings)).isEqualTo("{\n  \"a\": 1,\n  \"b\": 2\n}\n");
    }

    @Test
    void anUndoNeverTakesAValueTheUserSetMeanwhileAndKeepsTheFilesPermissions(@TempDir Path tmp) throws Exception {
        Path claude = Files.createDirectories(tmp.resolve("claude"));
        Path settings = Files.writeString(claude.resolve("settings.json"), "{\n  \"a\": 1\n}\n");
        Files.setPosixFilePermissions(settings, PosixFilePermissions.fromString("rw-r-----"));
        ScriptedCli base = notInstalled();
        for (String during : List.of("\"b\": 2", "\"autoMemoryDirectory\": \"~/mine\"")) {
            Kit.Cli cli = args -> {
                if (String.join(" ", args).startsWith("plugin install")) {
                    String now = Files.readString(settings);
                    Files.writeString(settings, during.startsWith("\"b\"") ? now.replace("\"a\": 1,", "\"a\": 1,\n  " + during + ",")
                            : now.replaceAll("\"autoMemoryDirectory\": \"[^\"]*\"", during));
                    return new Kit.Cli.Output(1, "{\"outcome\":\"failed\",\"message\":\"no\"}");
                }
                return base.run(args);
            };
            Throwable failure = catchThrowable(() -> new Kit(bundle(FILES), cli, tmp.resolve("kit"), claude).install());

            if (during.startsWith("\"b\"")) {
                assertThat(Files.readString(settings)).as("only comando's key goes").isEqualTo("{\n  \"a\": 1,\n  \"b\": 2\n}\n");
                assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(settings))).isEqualTo("rw-r-----");
                Files.writeString(settings, "{\n  \"a\": 1\n}\n");
            } else {
                assertThat(Files.readString(settings)).as("the user's value stands").contains(during);
                assertThat(failure).hasMessageContaining("; NOT undone: Set autoMemoryDirectory to " + claude.resolve("MEMORY")
                        + " (its value was changed since; kept)");
            }
        }
    }

    @Test
    void aSkeletonFileWrittenToSinceIsKeptAndSaidNotUndone(@TempDir Path tmp) throws Exception {
        Path claude = Files.createDirectories(tmp.resolve("claude"));
        Map<String, String> files = new LinkedHashMap<>(FILES);
        files.put("skeleton/ZZ/README.md", "z");
        Kit.Bundle bundle = new Kit.Bundle() {
            @Override
            public List<String> files() {
                return List.copyOf(files.keySet());
            }

            @Override
            public byte[] read(String file) throws IOException {
                if (file.equals("skeleton/ZZ/README.md") && Files.exists(claude.resolve("KB/00_START_HERE.md"))) {
                    // the user edits the first skeleton file before the last one fails
                    Files.writeString(claude.resolve("KB/00_START_HERE.md"), "mine now");
                    throw new IOException("unreadable");
                }
                return bundle(files).read(file);
            }
        };

        assertThatThrownBy(() -> new Kit(bundle, notInstalled(), tmp.resolve("kit"), claude).install())
                .hasMessageContaining("; NOT undone: Created " + claude.resolve("KB/00_START_HERE.md") + " (written to since; kept)");
        assertThat(Files.readString(claude.resolve("KB/00_START_HERE.md"))).isEqualTo("mine now");
    }

    /** A settings file as Claude Code writes it: JSON.stringify with two spaces, and a newline. */
    private static final String CLAUDE_SETTINGS = """
            {
              "permissions": {
                "allow": [
                  "Bash(git status:*)",
                  "Read(~/ñandú/**)"
                ],
                "deny": []
              },
              "env": {},
              "cleanupPeriodDays": 30,
              "effortLevel": 1.5,
              "enabledPlugins": {
                "other@x": true
              }
            }
            """;

    @Test
    void theMemorySettingIsAddedToTheUsersSettingsKeepingEverythingElseAsClaudeCodeWroteIt(@TempDir Path tmp) throws Exception {
        Path claude = Files.createDirectories(tmp.resolve("claude"));
        Path settings = Files.writeString(claude.resolve("settings.json"), CLAUDE_SETTINGS);
        Files.setPosixFilePermissions(settings, PosixFilePermissions.fromString("rw-r-----"));

        new Kit(bundle(FILES), notInstalled(), tmp.resolve("kit"), claude).install();

        assertThat(Files.readString(settings)).isEqualTo(CLAUDE_SETTINGS.substring(0, CLAUDE_SETTINGS.length() - 3)
                + ",\n  \"autoMemoryDirectory\": \"" + claude.resolve("MEMORY") + "\"\n}\n");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(settings))).isEqualTo("rw-r-----");
        try (Stream<Path> left = Files.list(claude)) {
            assertThat(left.map(p -> p.getFileName().toString())).as("no temporary file left").doesNotContain(".kit");
        }
    }

    @Test
    void aMemoryFolderTheUserChoseIsKeptAndUsed(@TempDir Path tmp) throws Exception {
        Path claude = Files.createDirectories(tmp.resolve("claude"));
        Path theirs = tmp.resolve("notes/memory");
        String settings = "{\n  \"autoMemoryDirectory\": \"" + theirs + "\"\n}\n";
        Files.writeString(claude.resolve("settings.json"), settings);
        Map<String, String> files = new LinkedHashMap<>(FILES);
        files.put("skeleton/MEMORY/MEMORY.md", "*Index*\n");
        Kit kit = new Kit(bundle(files), notInstalled(), tmp.resolve("kit"), claude);

        assertThat(kit.status().changes()).noneMatch(c -> c.startsWith("Set ")).contains("Create " + theirs.resolve("MEMORY.md"));
        assertThat(kit.folders().getFirst().path()).isEqualTo(theirs.toString());
        assertThat(kit.install()).noneMatch(c -> c.startsWith("Set "));
        assertThat(Files.readString(claude.resolve("settings.json"))).as("the user's value stands").isEqualTo(settings);
        assertThat(Files.readString(theirs.resolve("MEMORY.md"))).isEqualTo("*Index*\n");
    }

    @Test
    void aLinkedSettingsFileIsWrittenWhereItLives(@TempDir Path tmp) throws Exception {
        Path claude = Files.createDirectories(tmp.resolve("claude"));
        Path real = Files.writeString(Files.createDirectories(tmp.resolve("dotfiles")).resolve("settings.json"), "{}\n");
        Files.createSymbolicLink(claude.resolve("settings.json"), real);

        new Kit(bundle(FILES), notInstalled(), tmp.resolve("kit"), claude).install();

        assertThat(Files.isSymbolicLink(claude.resolve("settings.json"))).isTrue();
        assertThat(Files.readString(real)).contains("\"autoMemoryDirectory\": ");
    }

    @Test
    void aByteOrderMarkIsKept(@TempDir Path tmp) throws Exception {
        Path claude = Files.createDirectories(tmp.resolve("claude"));
        Files.writeString(claude.resolve("settings.json"), "\uFEFF{\n  \"a\": 1\n}\n");

        new Kit(bundle(FILES), notInstalled(), tmp.resolve("kit"), claude).install();

        assertThat(Files.readString(claude.resolve("settings.json")))
                .isEqualTo("\uFEFF{\n  \"a\": 1,\n  \"autoMemoryDirectory\": \"" + claude.resolve("MEMORY") + "\"\n}\n");
    }

    @Test
    void aMemorySettingClaudeCodeWouldNotTakeIsRefused(@TempDir Path tmp) throws Exception {
        Path claude = Files.createDirectories(tmp.resolve("claude"));
        Files.writeString(claude.resolve("settings.json"), "{\"autoMemoryDirectory\": \"notes/memory\"}");
        Kit kit = new Kit(bundle(FILES), notInstalled(), tmp.resolve("kit"), claude);

        assertThatThrownBy(kit::status).hasMessage("autoMemoryDirectory \"notes/memory\" in " + claude.resolve("settings.json")
                + " is neither an absolute path nor ~/…, as Claude Code requires; correct it, then try again");
        assertThatThrownBy(kit::install).hasMessageStartingWith("autoMemoryDirectory \"notes/memory\"");
    }

    @Test
    void aSettingsLinkToNothingIsNamed(@TempDir Path tmp) throws Exception {
        Path claude = Files.createDirectories(tmp.resolve("claude"));
        Files.createSymbolicLink(claude.resolve("settings.json"), tmp.resolve("gone/settings.json"));

        assertThatThrownBy(() -> new Kit(bundle(FILES), notInstalled(), tmp.resolve("kit"), claude).install())
                .hasMessage(claude.resolve("settings.json") + " links to " + tmp.resolve("gone/settings.json") + ", which does not exist;"
                        + " nothing was written to it");
    }

    @Test
    void settingsThatAreNotAJsonObjectAreNeverWritten(@TempDir Path tmp) throws Exception {
        Path claude = Files.createDirectories(tmp.resolve("claude"));
        for (String bad : List.of("{\"a\": ", "[1]", "{\"a\": 1}\n{\"b\": 2}")) {
            Files.writeString(claude.resolve("settings.json"), bad);
            ScriptedCli cli = notInstalled();
            Kit kit = new Kit(bundle(FILES), cli, tmp.resolve("kit"), claude);

            assertThatThrownBy(kit::install).as(bad).isInstanceOf(IOException.class).hasMessageContaining(claude.resolve("settings.json")
                    + " is not").hasMessageEndingWith("nothing was written to it");
            assertThat(Files.readString(claude.resolve("settings.json"))).isEqualTo(bad);
            assertThat(cli.changes()).as(bad + ": nothing installed").isEmpty();
        }
    }

    private static Path script(Path dir, String body) throws IOException {
        Path p = Files.writeString(dir.resolve("claude"), "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------"));
        return p;
    }

    @Test
    void theProcessRunnerReturnsTheExitAndBothStreamsAndGivesNoInput(@TempDir Path tmp) throws Exception {
        Path claude = script(tmp, "echo \"args: $*\"; echo oops >&2; read answer; echo \"read: [$answer]\"; exit 3");

        Kit.Cli.Output out = Kit.Cli.process(claude.toString()).run(List.of("plugin", "list"));

        assertThat(out.exit()).isEqualTo(3);
        assertThat(out.text()).isEqualTo("args: plugin list\noops\nread: []\n");
    }

    @Test
    void aRunPastItsBoundIsKilledAndFails(@TempDir Path tmp) throws Exception {
        Path claude = script(tmp, "sleep 30");
        long t0 = System.nanoTime();

        assertThatThrownBy(() -> Kit.Cli.process(claude.toString(), Duration.ofMillis(300)).run(List.of("plugin", "install")))
                .isInstanceOf(IOException.class).hasMessage("claude plugin install did not finish within 300 ms");
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(10));
    }

    /**
     * A process the run started that keeps the output open must not hold the run. On Linux the JDK stops reading a
     * process's pipe once it exits, so the run returns its output; elsewhere the drain bound ends it with a failure.
     */
    @Test
    void aProcessLeftHoldingTheOutputDoesNotHoldTheRun(@TempDir Path tmp) throws Exception {
        Path claude = script(tmp, "sleep 30 &\necho started");
        List<Kit.Cli.Output> out = new ArrayList<>();
        long t0 = System.nanoTime();

        Throwable failure = catchThrowable(() -> out.add(Kit.Cli.process(claude.toString()).run(List.of("plugin", "list"))));

        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(10));
        if (failure == null) {
            assertThat(out.getFirst().exit()).isZero();
            assertThat(out.getFirst().text()).isEqualTo("started\n");
        } else {
            assertThat(failure).isInstanceOf(IOException.class)
                    .hasMessage("claude plugin list ended, but a process it started kept its output open");
        }
    }

    @Test
    void theBuildCarriesEveryFileOfTheRepositorysKit() throws Exception {
        Kit.Bundle bundle = Kit.Bundle.resources();
        Path repoKit = Path.of(System.getProperty("user.dir")).getParent().resolve("kit");
        List<String> onDisk;
        try (Stream<Path> walk = Files.walk(repoKit)) {
            onDisk = walk.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().equals(".DS_Store"))
                    .map(p -> repoKit.relativize(p).toString().replace('\\', '/')).sorted().toList();
        }

        assertThat(bundle.files()).containsExactlyElementsOf(onDisk);
        for (String f : onDisk) {
            assertThat(bundle.read(f)).as(f).isEqualTo(Files.readAllBytes(repoKit.resolve(f)));
        }
        Kit kit = new Kit(bundle, new ScriptedCli(), Path.of("/nowhere"), Path.of("/nowhere"));
        assertThat(kit.version()).matches("\\d+\\.\\d+\\.\\d+");
        assertThat(kit.folders()).extracting(f -> f.indexFile().orElseThrow().getFileName().toString())
                .as("each folder opens a file the skeleton writes").containsExactly("MEMORY.md", "00_START_HERE.md", "README.md");
        assertThat(bundle.files()).contains("skeleton/MEMORY/MEMORY.md", "skeleton/KB/00_START_HERE.md", "skeleton/SCRATCHPAD/README.md");
    }
}
