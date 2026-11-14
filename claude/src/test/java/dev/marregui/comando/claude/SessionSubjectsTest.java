package dev.marregui.comando.claude;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.port.McpGateway;
import dev.marregui.comando.core.state.SessionSubject;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.testkit.RecordingSink;

import static org.assertj.core.api.Assertions.assertThat;

/// [SessionSubjects] reads what a session works on from its own writes, and [ClaudeAdapter] tells the window.
///
/// - The latest write's repository wins; the others are listed, the latest first.
/// - Only `Write`, `Edit`, `MultiEdit` and `NotebookEdit` count, and only when they succeeded.
/// - A path under the working folder or Claude Code's folder is dropped, with no `.git` under it read.
/// - A pass reads only the blocks added since the last one.
/// - A branch names its ticket in upper case.
/// - The adapter reads the subject at turn end and after history load; a kit folder shows no branch of its own.
class SessionSubjectsTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @TempDir
    Path tmp;
    private final RecordingSink sink = new RecordingSink();
    private final McpGateway gateway = new McpGateway() {
        @Override
        public Ticket register(final String session) {
            return new Ticket("http://127.0.0.1:1/mcp", "token-" + session);
        }

        @Override
        public void unregister(final String session, final Ticket ticket) {
            // nothing was issued that outlives the test
        }
    };
    private final Transcript transcript = new Transcript();
    private ClaudeAdapter adapter;
    private Path work;
    private Path projects;
    private SessionSubjects subjects;

    @BeforeEach
    void setUp() throws Exception {
        work = Files.createDirectories(tmp.resolve("work"));
        projects = Files.createDirectories(tmp.resolve("claude/projects"));
        subjects = new SessionSubjects(projects);
    }

    @AfterEach
    void tearDown() {
        if (adapter != null) {
            adapter.close();
        }
    }

    /// Writes to `one`, `two`, then `one` again leave `one` as the subject with `two` listed once, and a third
    /// repository is listed before `two` once `one` is written again.
    @Test
    void theLatestWritesRepositoryWinsAndTheOthersAreListedLatestFirst() throws Exception {
        final Path one = repo("one", "main");
        final Path two = repo("two", "feature/x");
        final Path three = repo("three", "main");
        write("Write", one, two, one);
        assertThat(subjects.pass(transcript, work))
                .isEqualTo(new SessionSubject(one.toString(), "main", "", List.of(two.toString())));
        write("Edit", three, one);
        assertThat(subjects.pass(transcript, work).others()).containsExactly(three.toString(), two.toString());
    }

    /// `Write`, `Edit`, `MultiEdit` and `NotebookEdit` each name a repository; the notebook call by `notebook_path`.
    @Test
    void everyWritingToolCounts() throws Exception {
        for (final String tool : List.of("Write", "Edit", "MultiEdit")) {
            final Path repo = repo(tool, "main");
            write(tool, repo);
            assertThat(subjects.pass(transcript, work).repository()).as(tool).isEqualTo(repo.toString());
        }
        final Path book = repo("book", "main");
        final String notebook = "{\"notebook_path\":\"" + book.resolve("n.ipynb") + "\"}";
        transcript.append(call("NotebookEdit", notebook, Block.ToolStatus.DONE));
        assertThat(subjects.pass(transcript, work).repository()).isEqualTo(book.toString());
    }

    /// `Read` and a `Bash` command that names a repository's file change nothing: a shell command is not parsed.
    @Test
    void readsAndShellCommandsAreNotWrites() throws Exception {
        final Path repo = repo("repo", "main");
        write("Read", repo);
        final String file = repo.resolve("f").toString();
        final String input = "{\"command\":\"echo x > " + file + "\",\"file_path\":\"" + file + "\"}";
        transcript.append(call("Bash", input, Block.ToolStatus.DONE));
        assertThat(subjects.pass(transcript, work)).isEqualTo(SessionSubject.NONE);
    }

    /// A write that failed wrote nothing, so its repository is not the subject.
    @Test
    void aFailedWriteCountsForNothing() throws Exception {
        final Path repo = repo("repo", "main");
        transcript.append(call("Write", input(repo), Block.ToolStatus.ERROR));
        assertThat(subjects.pass(transcript, work)).isEqualTo(SessionSubject.NONE);
    }

    /// A write still running at a turn's end is read by a later pass, once its input and outcome are in.
    @Test
    void aWriteStillRunningIsReadOnceItEnds() throws Exception {
        final Path repo = repo("repo", "main");
        final int at = transcript.append(call("Write", "{}", Block.ToolStatus.RUNNING));
        assertThat(subjects.pass(transcript, work)).isEqualTo(SessionSubject.NONE);
        transcript.replace(at, call("Write", input(repo), Block.ToolStatus.DONE));
        assertThat(subjects.pass(transcript, work).repository()).isEqualTo(repo.toString());
    }

    /// A file in the working folder, itself a repository, in a repository nested in it, or in one inside Claude Code's
    /// folder, is dropped by its text.
    @Test
    void aPathUnderTheWorkingFolderOrClaudesFolderIsDropped() throws Exception {
        final Path nested = Files.createDirectories(work.resolve("nested/.git")).getParent();
        final Path brain = Files.createDirectories(projects.resolveSibling("BRAIN/.git")).getParent();
        Files.createDirectories(work.resolve(".git"));
        write("Write", work, nested, brain);
        assertThat(subjects.pass(transcript, work)).isEqualTo(SessionSubject.NONE);
    }

    /// `kit` links to Claude Code's folder, which is a repository: a write through the link is the kit's all the same.
    @Test
    void aWriteThroughALinkToClaudesFolderIsDropped() throws Exception {
        Files.createDirectories(projects.resolveSibling(".git"));
        final Path link = Files.createSymbolicLink(tmp.resolve("kit"), projects.getParent());
        write("Write", link);
        assertThat(subjects.pass(transcript, work)).isEqualTo(SessionSubject.NONE);
    }

    /// A repository of its own inside Claude's folder, written through a link, is Claude's folder all the same.
    @Test
    void aWriteThroughALinkToARepositoryInsideClaudesFolderIsDropped() throws Exception {
        final Path inside = Files.createDirectories(projects.resolveSibling("brain/.git")).getParent();
        final Path link = Files.createSymbolicLink(tmp.resolve("kit"), projects.getParent());
        write("Write", link.resolve(inside.getFileName()));
        assertThat(subjects.pass(transcript, work)).isEqualTo(SessionSubject.NONE);
    }

    /// Relative paths, a value that is not a path, an input that is not JSON and a file in no repository name nothing.
    @Test
    void aPathThatNamesNoRepositoryIsIgnored() throws Exception {
        transcript.append(call("Write", "{\"file_path\":\"src/F.java\"}", Block.ToolStatus.DONE));
        transcript.append(call("Write", "{\"file_path\":\"bad\\u0000name\"}", Block.ToolStatus.DONE));
        transcript.append(call("Write", "not json", Block.ToolStatus.DONE));
        write("Write", Files.createDirectories(tmp.resolve("plain")));
        assertThat(subjects.pass(transcript, work)).isEqualTo(SessionSubject.NONE);
    }

    /// A block changed after a pass read it is not read again: the second pass starts where the first ended, so
    /// rewriting the first write's input to another repository changes nothing.
    @Test
    void aPassReadsOnlyTheBlocksAdded() throws Exception {
        final Path one = repo("one", "main");
        final Path two = repo("two", "main");
        write("Write", one);
        subjects.pass(transcript, work);
        transcript.replace(0, call("Write", input(two), Block.ToolStatus.DONE));
        assertThat(subjects.pass(transcript, work).repository()).isEqualTo(one.toString());
    }

    /// A branch changed between passes shows at the next one, with no new block.
    @Test
    void theBranchIsReadEachPass() throws Exception {
        final Path repo = repo("repo", "main");
        write("Write", repo);
        subjects.pass(transcript, work);
        Files.writeString(repo.resolve(".git/HEAD"), "ref: refs/heads/eng-7-next\n");
        assertThat(subjects.pass(transcript, work).ticket()).isEqualTo("ENG-7");
    }

    /// After `rewind` the emptied transcript's repositories stand and new blocks read from the start; after `forget`
    /// another conversation starts from nothing.
    @Test
    void rewindKeepsTheRepositoriesAndForgetDropsThem() throws Exception {
        final Path one = repo("one", "main");
        final Path two = repo("two", "main");
        write("Write", one, two);
        subjects.pass(transcript, work);
        transcript.clear();
        subjects.rewind();
        assertThat(subjects.pass(transcript, work).repository()).isEqualTo(two.toString());
        write("Write", one);
        assertThat(subjects.pass(transcript, work).repository()).as("the first block after the clear")
                .isEqualTo(one.toString());
        transcript.clear();
        subjects.forget();
        assertThat(subjects.pass(transcript, work)).isEqualTo(SessionSubject.NONE);
    }

    /// `feature/eng-1234-x` names `ENG-1234`; a word of one letter, a word with no number and `main` name none.
    @Test
    void aBranchNamesItsTicketInUpperCase() {
        assertThat(SessionSubjects.ticket("feature/eng-1234-x")).isEqualTo("ENG-1234");
        assertThat(SessionSubjects.ticket("AB-1")).isEqualTo("AB-1");
        assertThat(SessionSubjects.ticket("v-2")).isEmpty();
        assertThat(SessionSubjects.ticket("feature-x")).isEmpty();
        assertThat(SessionSubjects.ticket("main")).isEmpty();
    }

    /// Claude Code's folder and a folder in it are the kit, a sibling is not, and a link to it is.
    @Test
    void theKitIsClaudesFolderAndWhatItHolds() throws Exception {
        final Path kit = projects.getParent();
        assertThat(subjects.inKit(kit)).isTrue();
        assertThat(subjects.inKit(kit.resolve("BRAIN"))).isTrue();
        assertThat(subjects.inKit(work)).isFalse();
        assertThat(subjects.inKit(Files.createSymbolicLink(tmp.resolve("kit"), kit))).isTrue();
    }

    /// A turn that writes to `repo` ends with [Intent.SessionSubjectRead] naming its branch and ticket; a second turn
    /// writing the same says nothing new, so one intent is all the window gets.
    @Test
    void aTurnsEndTellsTheWindowOnce() throws Exception {
        final Path repo = repo("repo", "feature/eng-1234-x");
        final ClaudeAdapter a = adapterWriting(repo.resolve("F.java"));
        a.start(SessionSpec.fresh("work", work.toString()));
        sink.await(Intent.SessionStarted.class, WAIT);
        a.send("work", "write it", "", "");
        final Intent.SessionSubjectRead read = sink.await(Intent.SessionSubjectRead.class, WAIT);
        assertThat(read.subject())
                .isEqualTo(new SessionSubject(repo.toString(), "feature/eng-1234-x", "ENG-1234", List.of()));
        sink.clear();
        a.send("work", "again", "", "");
        sink.await(Intent.SessionTurnEnded.class, WAIT);
        assertThat(sink.of(Intent.SessionSubjectRead.class)).isEmpty();
    }

    /// A stored conversation's write gives the subject once its history is loaded, with no process.
    @Test
    void aLoadedHistoryTellsTheWindow() throws Exception {
        final Path repo = repo("repo", "eng-9");
        final Path project = Files.createDirectories(projects.resolve("-w"));
        Files.writeString(project.resolve("abc.jsonl"), """
                {"type":"assistant","message":{"id":"m0","content":[{"type":"tool_use","id":"t1","name":"Write","input":%s}]}}
                {"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}}
                """.formatted(input(repo)));
        adapterAt("no-claude").loadHistory(SessionSpec.imported("old", work.toString(), "abc"));
        assertThat(sink.await(Intent.SessionSubjectRead.class, WAIT).subject().ticket()).isEqualTo("ENG-9");
    }

    /// A session started in Claude Code's folder, itself a repository, shows no branch; one started in a repository
    /// shows its branch, as before there was a subject.
    @Test
    void theKitsBranchIsNotShownARepositorysIs() throws Exception {
        final Path kit = projects.getParent();
        Files.createDirectories(kit.resolve(".git"));
        Files.writeString(kit.resolve(".git/HEAD"), "ref: refs/heads/master\n");
        final Path repo = repo("repo", "main");
        final ClaudeAdapter a = adapterWriting(repo.resolve("F.java"));
        a.start(SessionSpec.fresh("kit", kit.toString()));
        a.start(SessionSpec.fresh("real", repo.toString()));
        sink.await(Intent.SessionStarted.class, s -> "kit".equals(s.name()), WAIT);
        a.send("kit", "write it", "", "");
        // read after the working folder's branch
        sink.await(Intent.SessionSubjectRead.class, r -> "kit".equals(r.name()), WAIT);
        sink.await(Intent.SessionBranch.class, b -> "real".equals(b.name()), WAIT);
        assertThat(sink.of(Intent.SessionBranch.class)).extracting(Intent.SessionBranch::name).doesNotContain("kit");
    }

    /** A repository in the temporary folder on {@code branch}. */
    private Path repo(final String name, final String branch) throws Exception {
        final Path repo = Files.createDirectories(tmp.resolve(name).resolve(".git")).getParent();
        Files.writeString(repo.resolve(".git/HEAD"), "ref: refs/heads/" + branch + "\n");
        return repo;
    }

    /** Appends a finished {@code tool} call on a file in each of {@code folders}. */
    private void write(final String tool, final Path... folders) {
        for (final Path folder : folders) {
            transcript.append(call(tool, input(folder), Block.ToolStatus.DONE));
        }
    }

    private static String input(final Path folder) {
        return "{\"file_path\":\"" + folder.resolve("F.java") + "\"}";
    }

    private static Block.ToolCall call(final String tool, final String input, final Block.ToolStatus status) {
        return new Block.ToolCall("t", tool, input, "", status, "");
    }

    /** An adapter on a process that writes {@code file} in each turn. */
    private ClaudeAdapter adapterWriting(final Path file) throws Exception {
        final Path exe = tmp.resolve("fake-claude");
        Files.writeString(exe, """
                #!/bin/sh
                n=0
                while IFS= read -r line; do
                  case "$line" in
                    *'"control_request"'*)
                      printf '%%s\\n' '{"type":"control_response","response":{"subtype":"success","request_id":"x"}}' ;;
                    *'"type":"user"'*)
                      n=$((n+1))
                      printf '%%s\\n' '{"type":"system","subtype":"init","session_id":"fake-1","model":"fake","cwd":"/w","claude_code_version":"0.0.1","permissionMode":"default","capabilities":[]}'
                      printf '%%s\\n' '{"type":"assistant","parent_tool_use_id":null,"message":{"id":"m'$n'","content":[{"type":"tool_use","id":"t'$n'","name":"Write","input":{"file_path":"%s"}}],"usage":{"input_tokens":10}}}'
                      printf '%%s\\n' '{"type":"user","parent_tool_use_id":null,"message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t'$n'","content":"ok"}]}}'
                      printf '%%s\\n' '{"type":"result","subtype":"success","is_error":false,"result":"","total_cost_usd":0,"duration_ms":1,"num_turns":1,"usage":{"input_tokens":10}}'
                      ;;
                  esac
                done
                """.formatted(file));
        assertThat(exe.toFile().setExecutable(true)).isTrue();
        return adapterAt(exe.toString());
    }

    private ClaudeAdapter adapterAt(final String executable) {
        adapter = new ClaudeAdapter(sink, new TranscriptRegistry(), gateway,
                new ClaudeSettings(executable, tmp.resolve("run"), projects, UiInstructions.TEXT));
        return adapter;
    }
}
