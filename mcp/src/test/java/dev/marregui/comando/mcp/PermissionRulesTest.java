package dev.marregui.comando.mcp;

import dev.marregui.comando.core.model.PermissionRules;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.testkit.FakeClock;
import dev.marregui.comando.testkit.States;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// [PermissionRules] derives the permission rule an approval scope saves, in the form the CLI saves it, and
/// [McpTools#permissionResult] sends it to its destination.
/// - A Bash command keeps two words for a command with subcommands, three for a subcommand that runs a program, else one.
/// - A nested, unclear or program-running command gets its exact text, never a prefix.
/// - A compound command gets one rule per subcommand, deduplicated; the wrappers the CLI strips come off first.
/// - An edit or read allows its file's directory by absolute or relative path; a tool without a path allows the tool.
/// - Only Bash and WebFetch rules may be saved; Always on anything else stays in the session.
/// - A plan approved with edits switches the session to accept edits.
class PermissionRulesTest {

    private final ObjectMapper json = new ObjectMapper();

    private static String rule(String tool, Map<String, String> input) {
        return PermissionRules.rules(tool, k -> input.getOrDefault(k, "")).text();
    }

    private static String bash(String command) {
        return rule("Bash", Map.of("command", command));
    }

    /// `git commit`, `./gradlew test`, `npm install` and `python -m pytest` keep their command and subcommand as the prefix.
    @Test
    void aCommandWithSubcommandsKeepsTwoWords() {
        assertThat(bash("git commit -m x")).isEqualTo("Bash(git commit:*)");
        assertThat(bash("./gradlew test --tests Foo")).isEqualTo("Bash(./gradlew test:*)");
        assertThat(bash("npm install")).isEqualTo("Bash(npm install:*)");
        assertThat(bash("python -m pytest -q")).isEqualTo("Bash(python -m pytest:*)");
    }

    /// `ls -la`, a padded `cat a.txt` and a bare `make` keep their first word only.
    @Test
    void anyOtherCommandKeepsItsFirstWord() {
        assertThat(bash("ls -la")).isEqualTo("Bash(ls:*)");
        assertThat(bash("  cat  a.txt ")).isEqualTo("Bash(cat:*)");
        assertThat(bash("make")).isEqualTo("Bash(make:*)");
    }

    /// Backticks, `$(…)`, process substitution, heredocs, an assignment prefix, `git -C` and a quoted program get their exact
    /// text; a newline or `&` splits into rules as the CLI splits them; an absolute path to git or npm keeps its subcommand
    /// prefix; a here-string is one word of data.
    @Test
    void aNestedOrUnclearCommandGetsTheExactCommand() {
        assertThat(bash("echo `id`")).isEqualTo("Bash(echo `id`)");
        assertThat(bash("echo $(id)")).isEqualTo("Bash(echo $(id))");
        assertThat(bash("diff <(ls a) <(ls b)")).isEqualTo("Bash(diff <(ls a) <(ls b))");
        assertThat(bash("ls\nrm -rf /")).as("split as the CLI splits it; rm keeps its exact text").isEqualTo("Bash(ls:*), Bash(rm -rf /)");
        assertThat(bash("sleep 5 & ls")).isEqualTo("Bash(sleep:*), Bash(ls:*)");
        // a command typed with its path is classified by its base name and keeps the path
        assertThat(bash("/usr/bin/git commit -m x")).isEqualTo("Bash(/usr/bin/git commit:*)");
        assertThat(bash("/opt/homebrew/bin/npm run test")).isEqualTo("Bash(/opt/homebrew/bin/npm run test:*)");
        assertThat(bash("/opt/homebrew/bin/npm exec")).as("a runner with nothing named").isEqualTo("Bash(/opt/homebrew/bin/npm exec)");
        // a here-document's lines are data: no rule for a line that never runs
        assertThat(bash("cat > deploy.sh <<'EOF'\n#!/bin/bash\ngit push origin main\nnpm publish\nEOF"))
                .isEqualTo("Bash(cat > deploy.sh <<'EOF'\n#!/bin/bash\ngit push origin main\nnpm publish\nEOF)");
        assertThat(bash("python3 - <<EOF\nimport os\nEOF")).isEqualTo("Bash(python3 - <<EOF\nimport os\nEOF)");
        assertThat(bash("grep x <<< 'a; rm b' && ls")).as("a here-string is one word of data").isEqualTo("Bash(grep:*), Bash(ls:*)");
        assertThat(bash("FOO=1 make test")).isEqualTo("Bash(FOO=1 make test)");
        assertThat(bash("git -C repo status")).isEqualTo("Bash(git -C repo status)");
        assertThat(bash("\"my tool\" run")).isEqualTo("Bash(\"my tool\" run)");
    }

    /// `&&`, `||`, `;`, pipes, newlines and `&` give one rule per subcommand in order, a dangerous `rm` keeping its exact
    /// text, redirections dropped, a repeated prefix once, and separators inside quotes or escaped left alone.
    @Test
    void aCompoundCommandGetsOneRulePerSubcommandAsTheCliSavesThem() {
        // changed: a compound command used to get its exact text, which Claude Code never matches
        assertThat(bash("git status && npm test")).isEqualTo("Bash(git status:*), Bash(npm test:*)");
        assertThat(bash("ls || true")).isEqualTo("Bash(ls:*), Bash(true:*)");
        assertThat(bash("cd a; ls")).isEqualTo("Bash(cd:*), Bash(ls:*)");
        assertThat(bash("ps aux | grep java")).isEqualTo("Bash(ps:*), Bash(grep:*)");
        assertThat(bash("rm -rf a && ls")).isEqualTo("Bash(rm -rf a), Bash(ls:*)");
        assertThat(bash("./gradlew test 2>&1 | tail -5")).isEqualTo("Bash(./gradlew test:*), Bash(tail:*)");
        assertThat(bash("ls && ls -la")).isEqualTo("Bash(ls:*)");
        assertThat(bash("grep -n 'a|b;c' f.txt")).isEqualTo("Bash(grep:*)");
        assertThat(bash("echo a\\;b && ls")).isEqualTo("Bash(echo:*), Bash(ls:*)");
        // newlines and a background job separate commands too, as Claude Code splits them
        assertThat(bash("git add -A\ngit commit -m x")).isEqualTo("Bash(git add:*), Bash(git commit:*)");
        assertThat(bash("npm run dev & npm test")).isEqualTo("Bash(npm run dev:*), Bash(npm test:*)");
        assertThat(bash("npm run dev &")).isEqualTo("Bash(npm run dev:*)");
    }

    /// `timeout` (with signal and duration), `time`, `nice`, `nohup`, `stdbuf`, `command`, `builtin` and `noglob` are removed
    /// before the prefix is taken; a wrapped `sudo` still gets its exact text, and `timeout` without a duration is no wrapper.
    @Test
    void theWrappersTheCliStripsComeOffBeforeTheRule() {
        assertThat(bash("timeout 30 npm test")).isEqualTo("Bash(npm test:*)");
        assertThat(bash("timeout -s KILL 5m ./gradlew build")).isEqualTo("Bash(./gradlew build:*)");
        assertThat(bash("time make")).isEqualTo("Bash(make:*)");
        assertThat(bash("nice -n 10 cargo build")).isEqualTo("Bash(cargo build:*)");
        assertThat(bash("nohup ls")).isEqualTo("Bash(ls:*)");
        assertThat(bash("stdbuf -oL -e 0 tail -f log")).isEqualTo("Bash(tail:*)");
        assertThat(bash("command ls")).isEqualTo("Bash(ls:*)");
        assertThat(bash("builtin cd x")).isEqualTo("Bash(cd:*)");
        assertThat(bash("noglob git status")).isEqualTo("Bash(git status:*)");
        assertThat(bash("timeout 10 sudo reboot")).isEqualTo("Bash(sudo reboot)");
        assertThat(bash("timeout npm test")).isEqualTo("Bash(timeout npm test)");
    }

    /// `sudo`, `find -delete/-exec`, `docker compose exec`, `source`, `.`, interpreters with a script or `-e`, `watch`,
    /// `env`, `npx`, `rm`, `docker exec/run` and `kubectl exec` get their exact text, while a harmless `find` or
    /// `docker compose up` keeps a prefix.
    @Test
    void aCommandThatRunsOtherCommandsNeverGetsAPrefix() {
        assertThat(bash("sudo systemctl restart nginx")).isEqualTo("Bash(sudo systemctl restart nginx)");
        assertThat(bash("find . -name '*.tmp' -delete")).isEqualTo("Bash(find . -name '*.tmp' -delete)");
        assertThat(bash("find src -type f -exec rm {} +")).isEqualTo("Bash(find src -type f -exec rm {} +)");
        assertThat(bash("find src -name '*.java'")).isEqualTo("Bash(find:*)");
        assertThat(bash("docker compose exec db psql")).isEqualTo("Bash(docker compose exec db psql)");
        assertThat(bash("docker compose up -d")).isEqualTo("Bash(docker compose:*)");
        // commands that run code or other commands get their exact text
        assertThat(bash("source .venv/bin/activate && pytest -q")).isEqualTo("Bash(source .venv/bin/activate), Bash(pytest:*)");
        assertThat(bash(". ./env.sh")).isEqualTo("Bash(. ./env.sh)");
        assertThat(bash("java -jar tool.jar")).isEqualTo("Bash(java -jar tool.jar)");
        assertThat(bash("awk '{print $1}' f")).isEqualTo("Bash(awk '{print $1}' f)");
        assertThat(bash("osascript -e 'beep'")).isEqualTo("Bash(osascript -e 'beep')");
        // the query form of command is not a wrapper; exec wrappers never get a prefix
        assertThat(bash("command -v jq")).isEqualTo("Bash(command -v jq)");
        assertThat(bash("watch -n1 ls")).isEqualTo("Bash(watch -n1 ls)");
        assertThat(bash("bash scripts/build.sh")).isEqualTo("Bash(bash scripts/build.sh)");
        assertThat(bash("python manage.py migrate")).isEqualTo("Bash(python manage.py migrate)");
        assertThat(bash("python3.12 x.py")).isEqualTo("Bash(python3.12 x.py)");
        assertThat(bash("env FOO=1 make")).isEqualTo("Bash(env FOO=1 make)");
        assertThat(bash("npx prettier --write .")).isEqualTo("Bash(npx prettier --write .)");
        assertThat(bash("rm -rf build")).isEqualTo("Bash(rm -rf build)");
        assertThat(bash("/bin/rm -rf build")).isEqualTo("Bash(/bin/rm -rf build)");
        for (String c : List.of("su", "doas", "xargs", "exec", "eval", "sh", "zsh", "fish", "ssh", "node", "ruby", "perl", "deno", "bun",
                "pnpx", "bunx", "uvx")) {
            assertThat(bash(c + " x")).as(c).isEqualTo("Bash(" + c + " x)");
        }
        assertThat(bash("docker exec web rm -rf /data")).isEqualTo("Bash(docker exec web rm -rf /data)");
        assertThat(bash("docker run alpine sh")).isEqualTo("Bash(docker run alpine sh)");
        assertThat(bash("kubectl exec pod -- ls")).isEqualTo("Bash(kubectl exec pod -- ls)");
    }

    /// `npm run test:unit`, `uv run pytest`, `pnpm dlx` and `npm exec tsc` keep three words; a runner given an interpreter,
    /// `--`, or nothing to run gets its exact text.
    @Test
    void aSubcommandThatRunsAProgramKeepsThreeWords() {
        assertThat(bash("npm run test:unit")).isEqualTo("Bash(npm run test:unit:*)");
        assertThat(bash("npm run test")).isEqualTo("Bash(npm run test:*)");
        assertThat(bash("uv run pytest -q")).isEqualTo("Bash(uv run pytest:*)");
        assertThat(bash("pnpm dlx create-app")).isEqualTo("Bash(pnpm dlx create-app:*)");
        assertThat(bash("npm exec tsc")).isEqualTo("Bash(npm exec tsc:*)");
        assertThat(bash("uv run python x.py")).isEqualTo("Bash(uv run python x.py)");
        assertThat(bash("uv run -- pytest")).isEqualTo("Bash(uv run -- pytest)");
        assertThat(bash("npm run")).isEqualTo("Bash(npm run)");
    }

    /// Edit, Write, MultiEdit and NotebookEdit allow `//<dir>/**` for an absolute path, normalised, `//**` for a file at the
    /// root, `./<dir>/**` for a relative path, and the file itself when it has no folder.
    @Test
    void anEditAllowsTheFilesDirectoryByAbsolutePath() {
        assertThat(rule("Edit", Map.of("file_path", "/a/b/c.java"))).isEqualTo("Edit(//a/b/**)");
        assertThat(rule("Write", Map.of("file_path", "/a/b/../d/c.java"))).isEqualTo("Edit(//a/d/**)");
        assertThat(rule("MultiEdit", Map.of("file_path", "/c.java"))).isEqualTo("Edit(//**)");
        assertThat(rule("NotebookEdit", Map.of("notebook_path", "/n/x.ipynb"))).isEqualTo("Edit(//n/**)");
        assertThat(rule("Edit", Map.of("file_path", "src/a.java"))).isEqualTo("Edit(./src/**)");
        assertThat(rule("Edit", Map.of("file_path", "a.java"))).isEqualTo("Edit(./a.java)");
    }

    /// Read allows its file's directory, Glob and Grep the given path (absolute or relative) with `/**`, and Grep without a
    /// path allows the whole tool.
    @Test
    void aReadAllowsTheDirectoryItReadsAndAToolWithoutAPathTheTool() {
        assertThat(rule("Read", Map.of("file_path", "/etc/hosts"))).isEqualTo("Read(//etc/**)");
        assertThat(rule("Glob", Map.of("pattern", "**/*.java", "path", "/w/src"))).isEqualTo("Read(//w/src/**)");
        assertThat(rule("Grep", Map.of("pattern", "x", "path", "src"))).isEqualTo("Read(./src/**)");
        assertThat(rule("Grep", Map.of("pattern", "x"))).isEqualTo("Grep");
    }

    /// Rules for a non-blank Bash command and an `http(s)` WebFetch URL are persistable; a URL without a scheme, a `file` URL,
    /// an empty URL, a blank command, and every edit, read, MCP and Task tool are not.
    @Test
    void onlyBashAndFetchRulesMayBeSaved() {
        assertThat(PermissionRules.rules("Bash", k -> k.equals("command") ? "ls" : "").persistable()).isTrue();
        assertThat(PermissionRules.rules("WebFetch", k -> k.equals("url") ? "https://a.org" : "").persistable()).isTrue();
        // nothing to name the rule after: the whole tool, never saved
        for (String url : new String[]{"example.com/x", "file:///etc/passwd", ""}) {
            PermissionRules.Rules r = PermissionRules.rules("WebFetch", k -> k.equals("url") ? url : "");
            assertThat(r.persistable()).as(url).isFalse();
        }
        assertThat(PermissionRules.rules("Bash", k -> "").persistable()).as("a blank command").isFalse();
        for (String tool : List.of("Edit", "Write", "MultiEdit", "NotebookEdit", "Read", "Glob", "Grep", "mcp__comando__db_run_sql", "Task")) {
            assertThat(PermissionRules.rules(tool, k -> "/a/b").persistable()).as(tool).isFalse();
        }
    }

    /// Session scope on an Edit adds `//a/b/**` to the session; Always on an MCP tool or an edit also stays in the session;
    /// Always on a compound Bash command saves its two rules to the local settings; Allow once sends no update.
    @Test
    void theScopeButtonsSendTheDerivedRuleToItsDestination() throws Exception {
        McpTools tools = new McpTools(i -> { }, States::twoPanels, new TranscriptRegistry(), null, AuditLog.none(), new FakeClock(), Duration.ofMinutes(1));
        ObjectNode input = json.createObjectNode().put("file_path", "/a/b/c.java").put("old_string", "x").put("new_string", "y");
        JsonNode session = json.readTree(tools.permissionResult("Edit", input, new ApprovalDecision.Allow(ApprovalDecision.Scope.SESSION)));
        JsonNode update = session.path("updatedPermissions").path(0);
        assertThat(update.path("type").asString()).isEqualTo("addRules");
        assertThat(update.path("destination").asString()).isEqualTo("session");
        assertThat(update.path("rules").path(0).path("toolName").asString()).isEqualTo("Edit");
        assertThat(update.path("rules").path(0).path("ruleContent").asString()).isEqualTo("//a/b/**");

        // changed: an Always for an MCP tool or an edit used to be saved in the local settings
        JsonNode always = json.readTree(tools.permissionResult("mcp__comando__ui_plot", json.createObjectNode(),
                new ApprovalDecision.Allow(ApprovalDecision.Scope.ALWAYS)));
        JsonNode wide = always.path("updatedPermissions").path(0);
        assertThat(wide.path("destination").asString()).isEqualTo("session");
        assertThat(wide.path("rules").path(0).has("ruleContent")).isFalse();
        JsonNode edit = json.readTree(tools.permissionResult("Edit", input, new ApprovalDecision.Allow(ApprovalDecision.Scope.ALWAYS)));
        assertThat(edit.path("updatedPermissions").path(0).path("destination").asString()).isEqualTo("session");

        JsonNode compound = json.readTree(tools.permissionResult("Bash", json.createObjectNode().put("command", "git status && npm test"),
                new ApprovalDecision.Allow(ApprovalDecision.Scope.ALWAYS)));
        JsonNode saved = compound.path("updatedPermissions").path(0);
        assertThat(saved.path("destination").asString()).isEqualTo("localSettings");
        assertThat(saved.path("rules")).hasSize(2);
        assertThat(saved.path("rules").path(0).path("ruleContent").asString()).isEqualTo("git status:*");
        assertThat(saved.path("rules").path(1).path("ruleContent").asString()).isEqualTo("npm test:*");

        JsonNode once = json.readTree(tools.permissionResult("Bash", json.createObjectNode().put("command", "ls"), ApprovalDecision.allowOnce()));
        assertThat(once.has("updatedPermissions")).isFalse();
    }

    /// Approving `ExitPlanMode` for the session allows it with the plan unchanged and one update setting the session's mode to
    /// `acceptEdits`.
    @Test
    void aPlanApprovedWithEditsSwitchesTheSessionToAcceptEdits() throws Exception {
        McpTools tools = new McpTools(i -> { }, States::twoPanels, new TranscriptRegistry(), null, AuditLog.none(), new FakeClock(), Duration.ofMinutes(1));
        ObjectNode plan = json.createObjectNode().put("plan", "1. do it");
        JsonNode r = json.readTree(tools.permissionResult("ExitPlanMode", plan, new ApprovalDecision.Allow(ApprovalDecision.Scope.SESSION)));
        assertThat(r.path("behavior").asString()).isEqualTo("allow");
        assertThat(r.path("updatedInput").path("plan").asString()).isEqualTo("1. do it");
        assertThat(r.path("updatedPermissions")).hasSize(1);
        JsonNode update = r.path("updatedPermissions").path(0);
        assertThat(update.path("type").asString()).isEqualTo("setMode");
        assertThat(update.path("mode").asString()).isEqualTo("acceptEdits");
        assertThat(update.path("destination").asString()).isEqualTo("session");

        JsonNode approved = json.readTree(tools.permissionResult("ExitPlanMode", plan, ApprovalDecision.allowOnce()));
        assertThat(approved.has("updatedPermissions")).isFalse();
    }
}
