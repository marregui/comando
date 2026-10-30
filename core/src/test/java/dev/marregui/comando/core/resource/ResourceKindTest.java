package dev.marregui.comando.core.resource;

import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.KitRoot;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.StartMode;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [ResourceKind] implementations convert editable attributes to connection, folder, and session specifications with
/// validated defaults.
///
/// - connection and session forms round-trip their values
/// - folder paths and listing names are constrained to safe forms
/// - session conversation settings govern start and clone modes
/// - removal prompts count affected state
class ResourceKindTest {

    /// A connection with TLS verification round-trips through attributes without changing any field.
    @Test
    void connectionRoundTrip() {
        ConnectionSpec spec = new ConnectionSpec("lab", "10.0.0.5", 8812, "qdb", "admin", "s3cret", true, "verify-full");
        assertThat(ConnectionKind.INSTANCE.fromAttributes(ConnectionKind.INSTANCE.toAttributes(spec))).isEqualTo(spec);
    }

    /// Omitted or null TLS mode defaults to `prefer`, while an unsupported mode is rejected by the specification.
    @Test
    void tlsModeDefaultsToPreferAndIsChecked() {
        assertThat(new ConnectionSpec("a", "h", 1, "d", "u", "p", false).sslMode()).isEqualTo("prefer");
        assertThat(new ConnectionSpec("a", "h", 1, "d", "u", "p", false, null).sslMode()).isEqualTo("prefer");
        assertThatThrownBy(() -> new ConnectionSpec("a", "h", 1, "d", "u", "p", false, "maybe")).hasMessageContaining("sslMode is one of");
    }

    /// A deletable folder preserves its listing file and group, while listing files with separators or traversal are
    /// rejected.
    @Test
    void aFolderWhoseEntriesGoToTheTrashKeepsItsListingFileAndTheListingIsAFileName() {
        FolderRoot pad = new FolderRoot("Scratchpad", "/s", "", List.of(), "scratchpad").withTrash("ALPHA.md");
        assertThat(FolderKind.INSTANCE.fromAttributes(FolderKind.INSTANCE.toAttributes(pad))).isEqualTo(pad);
        assertThat(pad.withName("Pad").deletable()).isTrue();
        assertThat(pad.withName("Pad").listedIn()).isEqualTo("ALPHA.md");
        FolderRoot grouped = new FolderRoot("KB", "/b/kb", "", List.of(), "knowledge", false, "", "Brain");
        assertThat(FolderKind.INSTANCE.fromAttributes(FolderKind.INSTANCE.toAttributes(grouped))).as("its group kept").isEqualTo(grouped);
        assertThat(grouped.withName("Base").group()).isEqualTo("Brain");
        assertThat(grouped.withTrash("INDEX.md").group()).isEqualTo("Brain");
        assertThat(new FolderRoot("n", "/p", "", List.of(), "folder", false, "", null).group()).as("none read: empty").isEmpty();
        assertThat(FolderKind.INSTANCE.fromAttributes(Map.of("name", "n", "path", "/p")).deletable()).as("by default a folder keeps its files").isFalse();
        for (String bad : List.of("day/ALPHA.md", "..", "a\\b")) {
            assertThatThrownBy(() -> FolderKind.INSTANCE.fromAttributes(Map.of("name", "n", "path", "/p", "listedIn", bad)))
                    .as(bad).hasMessageStartingWith("the listing file must be a file name");
        }
    }

    /// Folder attributes round-trip exclusions and home paths, reject invalid names and paths, and retain only
    /// supported icons and in-folder indexes.
    @Test
    void folderRoundTripAndItsFormIsChecked() {
        FolderRoot spec = new FolderRoot("Plugin", "/Users/x/.claude/plugins/p", "README.md", List.of("tools", "bin"));
        assertThat(FolderKind.INSTANCE.fromAttributes(FolderKind.INSTANCE.toAttributes(spec))).isEqualTo(spec);
        assertThat(FolderKind.INSTANCE.toAttributes(spec)).containsEntry("excludes", "tools, bin");
        assertThat(FolderKind.INSTANCE.fromAttributes(Map.of("name", "n", "path", "/p", "excludes", " a ,, b ,")).excludes())
                .as("blank names are dropped").containsExactly("a", "b");
        String home = System.getProperty("user.home");
        assertThat(FolderKind.INSTANCE.fromAttributes(Map.of("name", "n", "path", "~/notes")).path()).isEqualTo(home + "/notes");
        assertThat(FolderKind.INSTANCE.fromAttributes(Map.of("name", "n", "path", "~")).path()).isEqualTo(home);
        assertThatThrownBy(() -> FolderKind.INSTANCE.fromAttributes(Map.of("name", " ", "path", "/p"))).hasMessage("the folder needs a name");
        assertThatThrownBy(() -> FolderKind.INSTANCE.fromAttributes(Map.of("name", "n", "path", ""))).hasMessage("choose the folder to show");
        assertThatThrownBy(() -> FolderKind.INSTANCE.fromAttributes(Map.of("name", "n", "path", "notes")))
                .hasMessage("the folder must be a full path, such as ~/notes: notes");
        for (String outside : List.of("/etc/passwd", "../x.md", "a/../../x.md")) {
            assertThatThrownBy(() -> FolderKind.INSTANCE.fromAttributes(Map.of("name", "n", "path", "/p", "index", outside)))
                    .as(outside).hasMessageStartingWith("the index file must be inside the folder");
        }
        assertThat(FolderKind.INSTANCE.removalQuestion(null, "KB")).isEqualTo("Remove folder KB from the Files tab? Its files stay on disk.");
        FolderRoot skills = new FolderRoot("Skills", "/s", "", List.of(), "skills");
        assertThat(FolderKind.INSTANCE.fromAttributes(FolderKind.INSTANCE.toAttributes(skills))).isEqualTo(skills);
        assertThat(FolderKind.INSTANCE.fromAttributes(Map.of("name", "n", "path", "/p")).icon()).as("none set").isEqualTo(FolderRoot.FOLDER);
        assertThatThrownBy(() -> FolderKind.INSTANCE.fromAttributes(Map.of("name", "n", "path", "/p", "icon", "rocket")))
                .hasMessage("the icon must be one of folder, scratchpad, memory, knowledge, skills, plugin: rocket");
    }

    /// Folder exclusions always include build output and compare configured names ignoring case, while defaults retain
    /// the root and index file.
    @Test
    void aFolderLeavesOutBuildOutputAndItsOwnNamesIgnoringCase() {
        FolderRoot f = new FolderRoot("p", "/p", null, List.of("Tools"));
        assertThat(f.index()).isEmpty();
        assertThat(f.indexFile()).isEmpty();
        assertThat(f.excludes("tools")).isTrue();
        assertThat(f.excludes("node_modules")).isTrue();
        assertThat(f.excludes("Build")).isTrue();
        assertThat(f.excludes("skills")).isFalse();
        assertThat(new FolderRoot("p", "/p", "docs/ALPHA.md", null).indexFile()).contains(Path.of("/p/docs/ALPHA.md"));
        assertThat(FolderRoot.defaults(Path.of("/home/u/.claude"))).singleElement().satisfies(d -> {
            assertThat(d.path()).isEqualTo("/home/u/.claude");
            assertThat(d.index()).isEqualTo("CLAUDE.md");
            assertThat(d.excludes("projects")).as("Claude Code's transcripts are not documents").isTrue();
        });
    }

    /// Session budgets reject non-finite or negative values and accept a non-negative decimal amount.
    @Test
    void aSessionBudgetIsAFiniteAmount() {
        for (String budget : List.of("NaN", "Infinity", "1e400", "-1")) {
            assertThatThrownBy(() -> SessionKind.INSTANCE.fromAttributes(Map.of("name", "s", "workDir", "/w", "budgetUsd", budget)))
                    .as(budget).hasMessageStartingWith("budgetUsd must be a finite amount, 0 or more");
        }
        assertThat(SessionKind.INSTANCE.fromAttributes(Map.of("name", "s", "workDir", "/w", "budgetUsd", "0.5")).budgetUsd()).isEqualTo(0.5);
    }

    /// Session attributes retain all explicit fields, preserve an empty checkpoint, and give the default checkpoint to
    /// a stored session that has none.
    @Test
    void sessionRoundTrip() {
        SessionSpec spec = new SessionSpec("edge", "/Users/x/edge", "opus", "high", "acceptEdits",
                List.of("/a", "/b"), 15.0, "id-1", StartMode.RESUME, "/reflect", "");
        assertThat(SessionKind.INSTANCE.fromAttributes(SessionKind.INSTANCE.toAttributes(spec))).isEqualTo(spec);
        SessionSpec silent = new SessionSpec("edge", "/Users/x/edge", "", "", "", List.of(), 0.0, "", StartMode.NEW, "", "");
        assertThat(SessionKind.INSTANCE.fromAttributes(SessionKind.INSTANCE.toAttributes(silent)).checkpoint())
                .as("an emptied checkpoint stays empty: the button is hidden").isEmpty();
        assertThat(new SessionSpec("edge", "/Users/x/edge", "", "", "", List.of(), 0.0, "", StartMode.NEW, null, null).checkpoint())
                .as("a spec read from a file written before the field existed gets the default").isEqualTo("/reflect");
    }

    /// Invalid numeric ports and a resume request without a stored conversation id report the corresponding form
    /// errors.
    @Test
    void invalidFormsExplainThemselves() {
        Map<String, String> attrs = ConnectionKind.INSTANCE.toAttributes(ConnectionSpec.questDbDefaults("x"));
        attrs.put("port", "eighty");
        assertThatThrownBy(() -> ConnectionKind.INSTANCE.fromAttributes(attrs)).hasMessageContaining("port is not a number");

        Map<String, String> s = SessionKind.INSTANCE.toAttributes(SessionSpec.fresh("s", "/tmp"));
        s.put("startMode", "RESUME");
        assertThatThrownBy(() -> SessionKind.INSTANCE.fromAttributes(s))
                .hasMessage("Conversation RESUME needs a stored conversation; choose NEW for an empty one");
    }

    /// Start modes accept case-insensitive known choices and reject names outside NEW, RESUME, and FORK.
    @Test
    void aStartModeOutsideTheChoicesNamesTheChoices() {
        Map<String, String> s = SessionKind.INSTANCE.toAttributes(SessionSpec.imported("s", "/tmp", "id-1"));
        s.put("startMode", "new conversation");
        assertThatThrownBy(() -> SessionKind.INSTANCE.fromAttributes(s)).hasMessage("Conversation must be NEW, RESUME or FORK");
        s.put("startMode", "resume");
        assertThat(SessionKind.INSTANCE.fromAttributes(s).startMode()).isEqualTo(StartMode.RESUME);
    }

    /// Only the model choice permits typed values; every other choice remains constrained while text attributes stay
    /// editable.
    @Test
    void onlyTheModelAcceptsTypedValues() {
        assertThat(SessionKind.INSTANCE.attributes()).filteredOn(a -> a.kind() == Attribute.Kind.CHOICE)
                .extracting(Attribute::key, Attribute::editable)
                .containsExactly(Tuple.tuple("startMode", false), Tuple.tuple("model", true), Tuple.tuple("effort", false),
                        Tuple.tuple("permissionMode", false));
        assertThat(ConnectionKind.INSTANCE.attributes()).filteredOn(a -> a.kind() == Attribute.Kind.CHOICE)
                .allMatch(a -> !a.editable());
        assertThat(Attribute.of("x", "X", Attribute.Kind.TEXT, false, "").editable()).isTrue();
    }

    /// The conversation comes first; More holds the folder, the extra folders and the checkpoint prompt, and opens
    /// only when one of them differs from a new session's: a folder other than the kit's, extra folders, another prompt.
    @Test
    void moreHoldsTheFoldersAndThePromptAndOpensWhenOneDiffers() {
        assertThat(SessionKind.INSTANCE.attributes()).filteredOn(Attribute::advanced).extracting(Attribute::key)
                .containsExactly("workDir", "addDirs", "checkpoint");
        String kit = KitRoot.current().dir().toString();
        assertThat(SessionKind.INSTANCE.showAdvanced(SessionKind.INSTANCE.toAttributes(SessionSpec.fresh("s", kit)))).isFalse();
        assertThat(SessionKind.INSTANCE.showAdvanced(SessionKind.INSTANCE.toAttributes(SessionSpec.imported("s", kit, "id-1")))).isFalse();
        assertThat(SessionKind.INSTANCE.showAdvanced(SessionKind.INSTANCE.toAttributes(SessionSpec.fresh("s", "/elsewhere")))).isTrue();
        assertThat(SessionKind.INSTANCE.showAdvanced(SessionKind.INSTANCE.toAttributes(SessionSpec.fresh("s", kit).withCheckpoint("/compact")))).isTrue();
        assertThat(ConnectionKind.INSTANCE.attributes()).noneMatch(Attribute::advanced);
    }

    /// Choosing NEW for a stored conversation warns that it will not continue, while fresh sessions receive no advice.
    @Test
    void choosingNewForAStoredConversationSaysItWillNotBeContinued() {
        Map<String, String> s = SessionKind.INSTANCE.toAttributes(SessionSpec.imported("s", "/tmp", "c0ffee"));
        assertThat(SessionKind.INSTANCE.advice(s)).isEmpty();
        s.put("startMode", "NEW");
        assertThat(SessionKind.INSTANCE.advice(s)).isEqualTo("The stored conversation c0ffee will not be continued");
        assertThat(SessionKind.INSTANCE.advice(SessionKind.INSTANCE.toAttributes(SessionSpec.fresh("n", "/tmp")))).isEmpty();
    }

    /// A session removal question counts its open state, two pending approvals, home panel, attached document, and
    /// stored conversation.
    @Test
    void removingASessionNamesWhatItStopsDeniesRemovesAndDetaches() {
        SessionSpec work = SessionSpec.imported("work", "/tmp", "id-1");
        AppState s = AppState.initial(
                        List.of(new PanelState(new PanelId("p1"), "work", "", "work"), new PanelState(new PanelId("p2"), "PLAN.md", "", "work", true)),
                        List.of(), List.of(SessionState.closed(work).withStatus(Status.OPEN, "")))
                .withApprovals(List.of(new Approval(1, "work", ApprovalKind.TOOL, "Bash", "{}", "ls", 0L),
                        new Approval(2, "work", ApprovalKind.TOOL, "Bash", "{}", "pwd", 0L)));

        assertThat(SessionKind.INSTANCE.removalQuestion(s, "work")).isEqualTo("Remove session work? This stops it, denies 2 waiting approvals,"
                + " removes its panel work with its text and detaches 1 document. The conversation stays in ~/.claude/projects and can be"
                + " imported again.");

        AppState idle = AppState.initial(List.of(new PanelState(new PanelId("p1"), "one", "", "")), List.of(),
                List.of(SessionState.closed(SessionSpec.fresh("new", "/tmp"))));
        assertThat(SessionKind.INSTANCE.removalQuestion(idle, "new")).isEqualTo("Remove session new?");
    }

    /// A connection removal question lists closing, unbinding, and password deletion, and both resource kinds map names
    /// to their notice subjects.
    @Test
    void removingAConnectionNamesWhatItClosesUnbindsAndDeletes() {
        ConnectionSpec lab = new ConnectionSpec("lab", "h", 8812, "qdb", "admin", "s3cret", false, "prefer");
        AppState s = AppState.initial(List.of(new PanelState(new PanelId("p1"), "one", "lab", "")),
                List.of(ConnectionState.closed(lab).with(Status.OPEN, "")), List.of());
        assertThat(ConnectionKind.INSTANCE.removalQuestion(s, "lab"))
                .isEqualTo("Remove connection lab? This closes it, unbinds 1 panel and deletes its saved password.");
        assertThat(ConnectionKind.INSTANCE.noticeSubject("lab")).isEqualTo(Notice.connection("lab"));
        assertThat(SessionKind.INSTANCE.noticeSubject("w")).isEqualTo(Notice.session("w"));
    }

    /// Removal prompts count bindings on closed panels as well as open panels and name the closed home panel.
    @Test
    void removalCountsAClosedPanelItUnbinds() {
        ConnectionSpec lab = new ConnectionSpec("lab", "h", 8812, "qdb", "admin", "", false, "prefer");
        AppState s = AppState.initial(List.of(new PanelState(new PanelId("p1"), "one", "", "")), List.of(ConnectionState.closed(lab)),
                        List.of(SessionState.closed(SessionSpec.fresh("work", "/tmp"))))
                .withClosed(List.of(new PanelState(new PanelId("p2"), "two", "lab", "work")));
        assertThat(SessionKind.INSTANCE.removalQuestion(s, "work")).as("its own panel, closed").isEqualTo("Remove session work? This removes its panel two with its text.");
        assertThat(ConnectionKind.INSTANCE.removalQuestion(s, "lab")).isEqualTo("Remove connection lab? This unbinds 1 panel.");
    }

    /// Cloning a session with a conversation switches it to FORK, while a fresh session clone remains NEW.
    @Test
    void cloningASessionWithAConversationForksIt() {
        SessionSpec spec = SessionSpec.imported("edge", "/x", "id-1");
        assertThat(SessionKind.INSTANCE.cloneOf(spec, "edge 2").startMode()).isEqualTo(StartMode.FORK);
        assertThat(SessionKind.INSTANCE.cloneOf(SessionSpec.fresh("n", "/x"), "n 2").startMode()).isEqualTo(StartMode.NEW);
        assertThat(SessionSpec.imported("t", "/x", "c0ffee").startMode()).isEqualTo(StartMode.FORK);
    }
}
