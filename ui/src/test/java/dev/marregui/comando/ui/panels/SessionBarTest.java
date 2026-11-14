package dev.marregui.comando.ui.panels;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.Reflection;
import dev.marregui.comando.core.state.SessionInfo;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.state.SessionSubject;
import dev.marregui.comando.ui.kit.async.OnEdt;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.theme.Gauge;
import dev.marregui.comando.ui.kit.theme.NamedTheme;
import dev.marregui.comando.ui.kit.theme.Theme;

import static org.assertj.core.api.Assertions.assertThat;

/// [SessionBar] shows the focused panel's session: its chip, plug, model, effort, context, Reflect, Compact and Clear.
/// - it follows the focus, and a panel without a session shows only a chip to bind one
/// - what the session does changes no control's place
/// - the model and effort are menus that send the change
/// - the context turns purple once a checkpoint is due, and only if the session has one
/// - Reflect, Compact and Clear sit beside the context, each sends its command alone, each on its own predicate
/// - Reflect's icon turns from grey to purple as the context fills the threshold, and blinks near it until sent
/// - the chip's menu follows the focused panel's kind, and every item of the bar's menus has an icon
/// - a session named like HTML is shown as text
/// - the chip names the branch of what the session works on with the repository's short name, else its folder's branch
/// - under the console style the context is a gauge ticked at the checkpoint threshold, purple once it is due
class SessionBarTest {

    private static final PanelId A = new PanelId("a");

    /// For a panel bound to an open session the bar shows its name with the session icon, the plug, the model, the
    /// effort and the context share with no cost; focusing a panel without a session leaves only "no session ▾" and
    /// hides the rest.
    @Test
    void theBarShowsTheFocusedPanelsSessionAndFollowsTheFocus() throws Exception {
        final SessionState work = SessionState.closed(SessionSpec.fresh("work", "/w").withModel("opus").withEffort("high"))
                .withStatus(Status.OPEN, "").withUsage(1.0, 412_000, 1_000_000, 1L);
        final PanelState own = new PanelState(A, "work", "", "work");
        final PanelState notes = new PanelState(new PanelId("n"), "notes", "", "");
        final AppState state = AppState.initial(List.of(own, notes), List.of(), List.of(work));
        final SessionBar[] bar = new SessionBar[1];
        SwingUtilities.invokeAndWait(() -> {
            bar[0] = new SessionBar(i -> { });
            bar[0].render(state.withFocused(A));
        });
        assertThat(button(bar[0], "Session").getText()).as("no dot: the icon shows the state").isEqualTo("work");
        assertThat(button(bar[0], "Session").getIcon()).isSameAs(Icons.get("claude"));
        assertThat(button(bar[0], "Session").getToolTipText()).startsWith("Running");
        assertThat(button(bar[0], "Start or stop the session").isVisible()).isTrue();
        assertThat(button(bar[0], "Compact the conversation").isVisible()).isTrue();
        assertThat(button(bar[0], "Clear the conversation").isVisible()).isTrue();
        assertThat(button(bar[0], "Reflect").isVisible()).as("the default checkpoint command").isTrue();
        assertThat(button(bar[0], "Model").getText()).isEqualTo("opus ▾");
        assertThat(button(bar[0], "Effort").getText()).isEqualTo("high ▾");
        assertThat(label(bar[0], "Context").getText()).isEqualTo("ctx " + Labels.tokens(412_000) + " (41%)").doesNotContain("$");

        SwingUtilities.invokeAndWait(() -> bar[0].render(state.withFocused(notes.id())));
        assertThat(button(bar[0], "Session").getText()).as("a panel without a session binds one").isEqualTo("no session ▾");
        for (final String name : List.of("Start or stop the session", "Start a new conversation", "Reflect",
                "Compact the conversation", "Clear the conversation", "Model", "Effort")) {
            assertThat(button(bar[0], name).isVisible()).as(name).isFalse();
        }
        assertThat(label(bar[0], "Context").isVisible()).isFalse();
        assertThat(label(bar[0], "Activity").isVisible()).isFalse();
    }

    /// A session going from idle to three turns busy keeps every always-shown control at the same x, shows the activity
    /// with the queue count, and greys out the new-conversation button while it runs.
    @Test
    void whatTheSessionDoesChangesNoControlsPlace() throws Exception {
        final SessionState idle = SessionState.closed(SessionSpec.imported("work", "/w", "c1").withCheckpoint("/reflect"))
                .withStatus(Status.OPEN, "");
        final SessionState busy = idle.withTurns(3, System.currentTimeMillis() - 65_000);
        final PanelState own = new PanelState(A, "work", "", "work");
        final SessionBar[] bar = new SessionBar[1];
        final List<Integer> before = new ArrayList<>();
        final List<Integer> after = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            bar[0] = new SessionBar(i -> { });
            bar[0].render(AppState.initial(List.of(own), List.of(), List.of(idle)));
            before.addAll(places(bar[0]));
            bar[0].render(AppState.initial(List.of(own), List.of(), List.of(busy)));
            after.addAll(places(bar[0]));
        });
        assertThat(label(bar[0], "Activity").getText()).startsWith("working · ").endsWith(" · 2 queued");
        assertThat(after).as("the chip, plug, new conversation, model, effort, Reflect, Compact and Clear stay put")
                .isEqualTo(before);
        assertThat(button(bar[0], "Start a new conversation").isVisible()).as("it has a conversation").isTrue();
        assertThat(button(bar[0], "Start a new conversation").isEnabled()).as("greyed out while it runs").isFalse();
    }

    /// For a stopped session the model button names its configured model, as the session's row does, not a model the
    /// CLI reported earlier; its menu checks it, and picking items sends `SetSessionModel` and `SetSessionEffort`. An
    /// unset effort reads "default".
    @Test
    void theModelAndTheEffortAreMenusThatChangeThem() throws Exception {
        final List<Intent> intents = new CopyOnWriteArrayList<>();
        final SessionState x = SessionState.closed(SessionSpec.fresh("edge", "/w/edge").withModel("sonnet"))
                .withInfo(new SessionInfo("", "claude-opus-4-5", "", "", "", List.of()), 1L);
        final PanelState own = new PanelState(A, "edge", "", "edge");
        final SessionBar[] bar = new SessionBar[1];
        final List<String> checked = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            bar[0] = new SessionBar(intents::add);
            bar[0].render(AppState.initial(List.of(own), List.of(), List.of(x)));
            for (final Component c : bar[0].modelMenu().getComponents()) {
                if (c instanceof JCheckBoxMenuItem m && m.isSelected()) {
                    checked.add(m.getText());
                }
            }
            click(bar[0].modelMenu(), "opus");
            click(bar[0].effortMenu(), "max");
        });
        assertThat(button(bar[0], "Model").getText()).as("named as the session's row names it").isEqualTo(SessionRow.modelName(x) + " ▾").isEqualTo("sonnet ▾");
        assertThat(button(bar[0], "Model").getToolTipText()).endsWith("click to change");
        assertThat(checked).containsExactly("sonnet");
        assertThat(intents).containsExactly(new Intent.SetSessionModel("edge", "opus"), new Intent.SetSessionEffort("edge", "max"));
        assertThat(button(bar[0], "Effort").getText()).isEqualTo("default ▾");
    }

    /// The context label is muted until the reflection is due, then [Theme#THINKING] with a tooltip saying the
    /// checkpoint is due; a session without a checkpoint command stays muted even when due.
    @Test
    void theContextTurnsPurpleOnceTheCheckpointIsDue() throws Exception {
        final SessionState x = SessionState.closed(SessionSpec.fresh("work", "/w").withCheckpoint("/reflect"))
                .withStatus(Status.OPEN, "").withUsage(1.0, 700_000, 1_000_000, 1L);
        final PanelState own = new PanelState(A, "work", "", "work");
        final SessionBar[] bar = new SessionBar[1];
        SwingUtilities.invokeAndWait(() -> {
            bar[0] = new SessionBar(i -> { });
            bar[0].render(AppState.initial(List.of(own), List.of(), List.of(x.withReflection(new Reflection(false, true)))));
        });
        assertThat(label(bar[0], "Context").getForeground()).isEqualTo(Theme.MUTED);
        SwingUtilities.invokeAndWait(() -> bar[0].render(AppState.initial(List.of(own), List.of(), List.of(x.withReflection(new Reflection(true, false))))));
        assertThat(label(bar[0], "Context").getForeground()).as("as the session's row in the sidebar").isEqualTo(Theme.THINKING);
        assertThat(label(bar[0], "Context").getToolTipText()).endsWith("Reflect beside it is due");
        SwingUtilities.invokeAndWait(() -> bar[0].render(AppState.initial(List.of(own), List.of(),
                List.of(x.withSpec(x.spec().withCheckpoint("")).withReflection(new Reflection(true, false))))));
        assertThat(label(bar[0], "Context").getForeground()).as("no checkpoint, nothing due").isEqualTo(Theme.MUTED);
    }

    /// Reflect, Compact and Clear share the context's group, in that order after it, and the bar has no rewrite button;
    /// the context reads "ctx –" before a turn reports it; Reflect sends the checkpoint command alone, its tooltip
    /// naming no compaction, Compact sends `/compact` and Clear `/clear`, each as a `SessionCommand`.
    @Test
    void reflectCompactAndClearSitBesideTheContextAndSendTheirCommandsAlone() throws Exception {
        final List<Intent> intents = new CopyOnWriteArrayList<>();
        final SessionState running = SessionState.closed(SessionSpec.fresh("work", "/w").withCheckpoint("/reflect")).withStatus(Status.OPEN, "");
        final PanelState panel = new PanelState(A, "one", "", "work");
        final SessionBar[] bar = new SessionBar[1];
        SwingUtilities.invokeAndWait(() -> {
            bar[0] = new SessionBar(intents::add);
            bar[0].render(AppState.initial(List.of(panel), List.of(), List.of(running)));
        });
        assertThat(button(bar[0], "Rewrite as a document")).as("in the session's menu in the sidebar").isNull();
        final Container group = label(bar[0], "Context").getParent();
        final List<Component> order = List.of(group.getComponents());
        for (final String name : List.of("Reflect", "Compact the conversation", "Clear the conversation")) {
            assertThat(button(bar[0], name).getParent()).as("%s in the context's group", name).isSameAs(group);
            assertThat(button(bar[0], name).getIcon()).as(name).isNotNull();
        }
        assertThat(order.indexOf(button(bar[0], "Reflect"))).as("Reflect after the context")
                .isGreaterThan(order.indexOf(label(bar[0], "Context")))
                .isLessThan(order.indexOf(button(bar[0], "Compact the conversation")));
        assertThat(order.indexOf(button(bar[0], "Clear the conversation"))).as("Clear last")
                .isGreaterThan(order.indexOf(button(bar[0], "Compact the conversation")));
        assertThat(label(bar[0], "Context").getText()).as("before a turn reports it").isEqualTo("ctx –");
        assertThat(label(bar[0], "Context").getToolTipText()).endsWith("Reflect beside it falls due at 40%");
        assertThat(button(bar[0], "Reflect").getToolTipText()).isEqualTo("Reflect: sends /reflect")
                .doesNotContain("compact");
        assertThat(button(bar[0], "Compact the conversation").getToolTipText()).isEqualTo(SessionRow.COMPACT_TIP);
        assertThat(button(bar[0], "Clear the conversation").getToolTipText())
                .startsWith("Clear the conversation: sends /clear");
        SwingUtilities.invokeAndWait(() -> {
            button(bar[0], "Reflect").doClick();
            button(bar[0], "Compact the conversation").doClick();
            button(bar[0], "Clear the conversation").doClick();
        });
        assertThat(intents).containsExactly(new Intent.SessionCommand("work", "/reflect"),
                new Intent.SessionCommand("work", SessionSpec.COMPACT_COMMAND),
                new Intent.SessionCommand("work", "/clear"));
        assertThat(SessionSpec.CLEAR_COMMAND).isEqualTo("/clear");
    }

    /// Each button follows its predicate: a stopped session takes Reflect but not Compact or Clear, whose tooltip says
    /// to start it; a stopping one takes none; one running its checkpoint takes none; a due session's Reflect tooltip
    /// and its menu icon say so. The bar's enablement is the statics' that the menus and the palette share.
    @Test
    void eachButtonIsEnabledByItsOwnPredicate() throws Exception {
        final SessionState open = SessionState.closed(SessionSpec.imported("work", "/w", "c1")
                .withCheckpoint("/reflect")).withStatus(Status.OPEN, "");
        final SessionState stopped = open.withStatus(Status.CLOSED, "");
        final SessionState stopping = open.withStatus(Status.CLOSING, "");
        final SessionState locked = open.withTurns(1, 1L).withCheckpointTurns(1);
        final PanelState own = new PanelState(A, "work", "", "work");
        final SessionBar[] bar = new SessionBar[1];
        final List<List<Boolean>> enabled = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            bar[0] = new SessionBar(i -> { });
            for (final SessionState x : List.of(open, stopped, stopping, locked)) {
                bar[0].render(AppState.initial(List.of(own), List.of(), List.of(x)));
                enabled.add(List.of(button(bar[0], "Reflect").isEnabled(),
                        button(bar[0], "Compact the conversation").isEnabled(),
                        button(bar[0], "Clear the conversation").isEnabled()));
            }
        });
        assertThat(enabled).containsExactly(List.of(true, true, true), List.of(true, false, false),
                List.of(false, false, false), List.of(false, false, false));
        for (final SessionState x : List.of(open, stopped, stopping, locked)) {
            assertThat(List.of(SessionBar.canReflect(x), SessionBar.canCompact(x), SessionBar.canClear(x)))
                    .as("the statics the bar reads")
                    .isEqualTo(enabled.get(List.of(open, stopped, stopping, locked).indexOf(x)));
        }
        assertThat(SessionBar.clearTip(stopped)).endsWith("start the session first, or start a new conversation");
        assertThat(SessionBar.clearTip(open)).doesNotContain("first");
        final SessionState due = open.withReflection(new Reflection(true, false));
        assertThat(SessionBar.reflectTip(due)).startsWith("Reflect, due: ").endsWith("sends /reflect");
        assertThat(SessionBar.reflectIconName(due)).isEqualTo("checkpoint-due");
        assertThat(SessionBar.reflectIconName(open)).isEqualTo("checkpoint");
        assertThat(SessionBar.showsReflect(open.withSpec(open.spec().withCheckpoint("")))).isFalse();
        assertThat(SessionBar.reflectIntent(open)).isEqualTo(new Intent.SessionCommand("work", "/reflect"));
        assertThat(SessionBar.compactIntent(open)).isEqualTo(new Intent.SessionCommand("work", "/compact"));
        assertThat(SessionBar.clearIntent(open)).isEqualTo(new Intent.SessionCommand("work", "/clear"));
    }

    /// Reflect's share of the way to purple is the context over the threshold's tokens: none for an unknown context or
    /// window, a half at 200k of a million at 40%, all of it at 400k and past it; painted, the bookmark is the SVG's
    /// grey at none and [Theme#THINKING] at all of it.
    @Test
    @ExtendWith(OnEdt.class)
    void reflectsIconTurnsFromGreyToPurpleAsTheContextFillsTheThreshold() {
        final SessionState x = SessionState.closed(SessionSpec.fresh("work", "/w").withCheckpoint("/reflect"))
                .withStatus(Status.OPEN, "");
        assertThat(SessionBar.reflectShare(x, 40)).as("unknown context").isZero();
        assertThat(SessionBar.reflectShare(x.withUsage(1.0, 200_000, 0, 1L), 40)).as("unknown window").isZero();
        assertThat(SessionBar.reflectShare(x.withUsage(1.0, 200_000, 1_000_000, 1L), 40)).isEqualTo(0.5);
        assertThat(SessionBar.reflectShare(x.withUsage(1.0, 400_000, 1_000_000, 1L), 40)).isEqualTo(1.0);
        assertThat(SessionBar.reflectShare(x.withUsage(1.0, 900_000, 1_000_000, 1L), 40)).as("clamped").isEqualTo(1.0);
        final PanelState own = new PanelState(A, "work", "", "work");
        ConsoleChecks.inTheme(NamedTheme.GRAPHITE_GOLD, () -> {
            final SessionBar bar = new SessionBar(i -> { });
            bar.render(AppState.initial(List.of(own), List.of(), List.of(x)));
            assertThat(centre(button(bar, "Reflect").getIcon())).as("dull before a turn reports the context")
                    .isEqualTo(0x6E6E6E);
            bar.render(AppState.initial(List.of(own), List.of(), List.of(x.withUsage(1.0, 200_000, 1_000_000, 1L))));
            assertThat(centre(button(bar, "Reflect").getIcon())).as("half way")
                    .isEqualTo(mix(0x6E6E6E, Theme.THINKING.getRGB() & 0xFFFFFF));
            bar.render(AppState.initial(List.of(own), List.of(), List.of(x.withUsage(1.0, 400_000, 1_000_000, 1L))));
            assertThat(centre(button(bar, "Reflect").getIcon())).as("at the threshold")
                    .isEqualTo(Theme.THINKING.getRGB() & 0xFFFFFF);
        });
    }

    /// Reflect blinks, its bookmark grey on every other tick, from 38% of the window at a 40% threshold until the
    /// checkpoint command is sent: not at 37%, at 38% armed, at 50% due; a session checkpointed (neither due nor armed)
    /// does not, nor one with no checkpoint command; once it stops blinking the icon is drawn lit again.
    @Test
    @ExtendWith(OnEdt.class)
    void reflectBlinksFromTwoPointsBelowTheThresholdUntilTheCommandIsSent() {
        final SessionState x = SessionState.closed(SessionSpec.fresh("work", "/w").withCheckpoint("/reflect"))
                .withStatus(Status.OPEN, "").withReflection(Reflection.FRESH);
        final SessionState near = x.withUsage(1.0, 380_000, 1_000_000, 1L);
        assertThat(SessionBar.blinks(x.withUsage(1.0, 379_999, 1_000_000, 1L), 40)).as("below 38%").isFalse();
        assertThat(SessionBar.blinks(near, 40)).as("at 38%, armed").isTrue();
        assertThat(SessionBar.blinks(x.withUsage(1.0, 500_000, 1_000_000, 1L)
                .withReflection(new Reflection(true, false)), 40)).as("past it, due").isTrue();
        assertThat(SessionBar.blinks(near.withReflection(Reflection.checkpointed()), 40)).as("sent").isFalse();
        assertThat(SessionBar.blinks(near.withSpec(near.spec().withCheckpoint("")), 40)).as("no command").isFalse();
        assertThat(SessionBar.blinks(x, 40)).as("unknown context").isFalse();
        final PanelState own = new PanelState(A, "work", "", "work");
        ConsoleChecks.inTheme(NamedTheme.GRAPHITE_GOLD, () -> {
            final SessionBar bar = new SessionBar(i -> { });
            bar.render(AppState.initial(List.of(own), List.of(), List.of(near)));
            final Icon icon = button(bar, "Reflect").getIcon();
            final int lit = centre(icon);
            bar.tick();
            assertThat(centre(icon)).as("dim on one tick").isEqualTo(0x6E6E6E);
            bar.tick();
            assertThat(centre(icon)).as("lit on the next").isEqualTo(lit).isNotEqualTo(0x6E6E6E);
            bar.tick();
            bar.render(AppState.initial(List.of(own), List.of(),
                    List.of(near.withReflection(Reflection.checkpointed()))));
            assertThat(centre(icon)).as("sent: lit, not left dim").isEqualTo(lit);
            bar.tick();
            assertThat(centre(icon)).as("and no longer blinks").isEqualTo(lit);
        });
    }

    /// Every item of the bar's own menus has an icon: the chip's menu for each panel kind, and the model and effort
    /// menus.
    @Test
    void everyItemOfTheBarsMenusHasAnIcon() throws Exception {
        final SessionState work = SessionState.closed(SessionSpec.fresh("work", "/w"));
        final PanelState own = new PanelState(A, "work", "", "work");
        final PanelState notes = new PanelState(new PanelId("n"), "notes", "", "");
        final PanelState doc = new PanelState(new PanelId("d"), "PLAN.md", "", "", true);
        final PanelState attached = new PanelState(new PanelId("e"), "NOTES.md", "", "work", true);
        final List<String> bare = new ArrayList<>();
        final int[] items = new int[1];
        SwingUtilities.invokeAndWait(() -> {
            final SessionBar bar = new SessionBar(i -> { });
            final List<JPopupMenu> menus = new ArrayList<>();
            for (final AppState state : List.of(
                    AppState.initial(List.of(own, notes, doc, attached), List.of(), List.of(work)),
                    AppState.initial(List.of(doc), List.of(), List.of()))) {
                for (final PanelState p : state.panels()) {
                    bar.render(state.withFocused(p.id()));
                    menus.add(bar.sessionMenu());
                }
            }
            bar.render(AppState.initial(List.of(own), List.of(), List.of(work)));
            menus.add(bar.modelMenu());
            menus.add(bar.effortMenu());
            for (final JPopupMenu m : menus) {
                for (final Component c : m.getComponents()) {
                    if (c instanceof JMenuItem item) {
                        items[0]++;
                        if (item.getIcon() == null) {
                            bare.add(item.getText());
                        }
                    }
                }
            }
        });
        assertThat(items[0]).as("the menus have items").isGreaterThan(8);
        assertThat(bare).as("items without an icon").isEmpty();
    }

    /// The chip's menu explains a session's own panel, offers a new or existing session to a panel without one, attach
    /// to a document and detach to an attached document; "New session" passes the panel's title, and "Open" sends
    /// `OpenSession`.
    @Test
    void theSessionChipsMenuFollowsTheFocusedPanelsKind() throws Exception {
        final List<Intent> intents = new CopyOnWriteArrayList<>();
        final List<String> created = new CopyOnWriteArrayList<>();
        final SessionState work = SessionState.closed(SessionSpec.fresh("work", "/w"));
        final PanelState own = new PanelState(A, "work", "", "work");
        final PanelState notes = new PanelState(new PanelId("n"), "notes", "", "");
        final PanelState doc = new PanelState(new PanelId("d"), "PLAN.md", "", "", true);
        final PanelState attached = new PanelState(new PanelId("e"), "NOTES.md", "", "work", true);
        final AppState state = AppState.initial(List.of(own, notes, doc, attached), List.of(), List.of(work));
        final List<List<String>> menus = new ArrayList<>();
        final SessionBar[] bar = new SessionBar[1];
        SwingUtilities.invokeAndWait(() -> {
            bar[0] = new SessionBar(intents::add);
            bar[0].onNewSession(created::add);
            for (final PanelState p : List.of(own, notes, doc, attached)) {
                bar[0].render(state.withFocused(p.id()));
                menus.add(texts(bar[0].sessionMenu()));
            }
        });
        assertThat(menus.get(0)).as("its own panel").containsExactly("work's own panel: removing either removes both");
        assertThat(menus.get(1)).as("a panel without a session").containsExactly("New session notes…", "Open work  /w");
        assertThat(menus.get(2)).as("a document").containsExactly("Attach to work  /w");
        assertThat(menus.get(3)).as("a document attached").containsExactly("Detach from work");

        SwingUtilities.invokeAndWait(() -> {
            bar[0].render(state.withFocused(notes.id()));
            click(bar[0].sessionMenu(), "New session notes…");
        });
        assertThat(created).containsExactly("notes");
        SwingUtilities.invokeAndWait(() -> click(bar[0].sessionMenu(), "Open work  /w"));
        assertThat(intents).singleElement().isInstanceOfSatisfying(Intent.OpenSession.class,
                o -> assertThat(o.session()).as("its own panel comes forward; this one is not taken").isEqualTo("work"));
    }

    /// A session named `<html><b>bold</b>` shows its name literally, not rendered as HTML.
    @Test
    void aSessionNamedLikeHtmlIsShownAsText() throws Exception {
        final SessionState imported = SessionState.closed(SessionSpec.fresh("<html><b>bold</b>", "/w"));
        final PanelState panel = new PanelState(A, "one", "", imported.name());
        final SessionBar[] bar = new SessionBar[1];
        SwingUtilities.invokeAndWait(() -> {
            bar[0] = new SessionBar(i -> { });
            bar[0].render(AppState.initial(List.of(panel), List.of(), List.of(imported)));
        });
        assertThat(button(bar[0], "Session").getText()).contains("bold</b>").doesNotStartWith("<html>");
    }

    /// A session writing to `/src/edge` on `eng-1` shows that branch and `edge`, not the branch of its folder.
    @Test
    void theChipNamesTheBranchOfTheSubjectAndItsRepository() {
        final SessionState session = SessionState.closed(SessionSpec.fresh("work", "/w")).withBranch("master")
                .withSubject(new SessionSubject("/src/edge", "eng-1", "ENG-1", List.of()));
        assertThat(SessionBar.chipText(session)).isEqualTo("work  ⎇ eng-1 · edge");
    }

    /// With no branch to read, the repository's name stands alone, with no branch mark.
    @Test
    void aSubjectWithoutABranchShowsItsRepository() {
        final SessionState session = SessionState.closed(SessionSpec.fresh("work", "/w"))
                .withSubject(new SessionSubject("/src/edge", "", "", List.of()));
        assertThat(SessionBar.chipText(session)).isEqualTo("work · edge");
    }

    /// With no writes the branch of the working folder shows as before, and with none the name alone.
    @Test
    void withoutASubjectTheChipShowsTheFoldersBranch() {
        final SessionState session = SessionState.closed(SessionSpec.fresh("work", "/w"));
        assertThat(SessionBar.chipText(session)).isEqualTo("work");
        assertThat(SessionBar.chipText(session.withBranch("main"))).isEqualTo("work  ⎇ main");
    }

    /// Under the console style a session at half its context, with a checkpoint at the 40% threshold, shows one gauge
    /// captioned "CTX" at 50 with its tick at 40 and the label's tooltip, purple once due and in the tint before; the
    /// label hides; without a checkpoint command the gauge has no tick.
    @Test
    @ExtendWith(OnEdt.class)
    void underTheConsoleStyleTheContextIsAGaugeTickedAtTheCheckpoint() {
        final SessionState x = SessionState.closed(SessionSpec.fresh("work", "/w").withCheckpoint("/reflect"))
                .withStatus(Status.OPEN, "").withUsage(1.0, 500_000, 1_000_000, 1L);
        final PanelState own = new PanelState(A, "work", "", "work");
        ConsoleChecks.inTheme(NamedTheme.CONSOLE_AMBER, () -> {
            final SessionBar bar = new SessionBar(i -> { });
            bar.render(AppState.initial(List.of(own), List.of(), List.of(x.withReflection(new Reflection(true, false)))));
            final List<Gauge> gauges = showing(bar.component());
            assertThat(gauges).hasSize(1);
            final Gauge gauge = gauges.getFirst();
            assertThat(gauge.caption()).isEqualTo("CTX");
            assertThat(gauge.percent()).isEqualTo(50);
            assertThat(gauge.tick()).hasValue(Reflection.DEFAULT_PERCENT).hasValue(40);
            assertThat(gauge.fill()).isSameAs(Theme.THINKING);
            assertThat(gauge.getToolTipText()).isEqualTo(label(bar, "Context").getToolTipText())
                    .endsWith("Reflect beside it is due");
            assertThat(label(bar, "Context").isVisible()).as("the gauge stands for it").isFalse();

            bar.render(AppState.initial(List.of(own), List.of(), List.of(x.withReflection(new Reflection(false, true)))));
            assertThat(gauge.fill()).as("not due yet").isSameAs(Theme.tint().body);
            bar.render(AppState.initial(List.of(own), List.of(),
                    List.of(x.withSpec(x.spec().withCheckpoint("")).withReflection(new Reflection(true, false)))));
            assertThat(gauge.tick()).as("no checkpoint, no tick").isEqualTo(OptionalInt.empty());
            assertThat(gauge.fill()).as("no checkpoint, nothing due").isSameAs(Theme.tint().body);
        });
    }

    /// Under the console style a session whose context share is not known, before a turn reports it or without its
    /// window, keeps the label and shows no gauge.
    @Test
    @ExtendWith(OnEdt.class)
    void underTheConsoleStyleAnUnknownContextStaysALabel() {
        final SessionState x = SessionState.closed(SessionSpec.fresh("work", "/w").withCheckpoint("/reflect"))
                .withStatus(Status.OPEN, "");
        final PanelState own = new PanelState(A, "work", "", "work");
        ConsoleChecks.inTheme(NamedTheme.CONSOLE_AMBER, () -> {
            final SessionBar bar = new SessionBar(i -> { });
            for (final SessionState s : List.of(x, x.withUsage(1.0, 500_000, 0, 1L))) {
                bar.render(AppState.initial(List.of(own), List.of(), List.of(s)));
                assertThat(showing(bar.component())).isEmpty();
                assertThat(label(bar, "Context").isVisible()).isTrue();
            }
        });
    }

    /// Under a plain theme the context stays the label it was, with its text and colour, and no gauge shows.
    @Test
    @ExtendWith(OnEdt.class)
    void underAPlainThemeTheContextStaysALabel() {
        final SessionState x = SessionState.closed(SessionSpec.fresh("work", "/w").withCheckpoint("/reflect"))
                .withStatus(Status.OPEN, "").withUsage(1.0, 500_000, 1_000_000, 1L).withReflection(new Reflection(true, false));
        final PanelState own = new PanelState(A, "work", "", "work");
        ConsoleChecks.inTheme(NamedTheme.GRAPHITE_GOLD, () -> {
            final SessionBar bar = new SessionBar(i -> { });
            bar.render(AppState.initial(List.of(own), List.of(), List.of(x)));
            assertThat(showing(bar.component())).isEmpty();
            assertThat(label(bar, "Context").isVisible()).isTrue();
            assertThat(label(bar, "Context").getText()).isEqualTo("ctx " + Labels.tokens(500_000) + " (50%)");
            assertThat(label(bar, "Context").getForeground()).isEqualTo(Theme.THINKING);
        });
    }


    /// The gauges under `c` that show: each, and every container up to `c`, visible.
    private static List<Gauge> showing(final Component c) {
        final List<Gauge> out = new ArrayList<>();
        if (!c.isVisible()) {
            return out;
        }
        if (c instanceof Gauge g) {
            out.add(g);
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                out.addAll(showing(child));
            }
        }
        return out;
    }

    /** The x of each control a session always shows, laid out at a fixed width. */
    private static List<Integer> places(final SessionBar bar) {
        final JComponent root = bar.component();
        root.setSize(900, 32);
        layOut(root);
        final List<Integer> xs = new ArrayList<>();
        for (final String name : List.of("Session", "Start or stop the session", "Start a new conversation",
                "Model", "Effort", "Reflect", "Compact the conversation", "Clear the conversation")) {
            final AbstractButton b = button(bar, name);
            xs.add(SwingUtilities.convertPoint(b.getParent(), b.getLocation(), root).x);
        }
        return xs;
    }

    /// The RGB `icon` paints at its centre, where the bookmark covers every pixel.
    private static int centre(final Icon icon) {
        final BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(),
                BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = image.createGraphics();
        try {
            icon.paintIcon(null, g, 0, 0);
        } finally {
            g.dispose();
        }
        return image.getRGB(icon.getIconWidth() / 2, icon.getIconHeight() / 2 - 2) & 0xFFFFFF;
    }

    /// `from` half way to `to`, channel by channel, rounded as the icon rounds.
    private static int mix(final int from, final int to) {
        int out = 0;
        for (final int shift : new int[]{16, 8, 0}) {
            final int a = from >> shift & 0xFF;
            final int b = to >> shift & 0xFF;
            out |= (int) Math.round(a + (b - a) * 0.5) << shift;
        }
        return out;
    }

    private static void layOut(final Component c) {
        if (c instanceof Container k) {
            k.doLayout();
            for (final Component child : k.getComponents()) {
                layOut(child);
            }
        }
    }

    private static List<String> texts(final JPopupMenu menu) {
        final List<String> out = new ArrayList<>();
        for (final Component c : menu.getComponents()) {
            if (c instanceof JMenuItem m) {
                out.add(m.getText());
            }
        }
        return out;
    }

    private static void click(final JPopupMenu menu, final String text) {
        for (final Component c : menu.getComponents()) {
            if (c instanceof JMenuItem m && text.equals(m.getText())) {
                m.doClick();
                return;
            }
        }
        throw new AssertionError("no item " + text);
    }

    private static AbstractButton button(final SessionBar bar, final String name) {
        final List<AbstractButton> out = new ArrayList<>();
        collect(bar.component(), AbstractButton.class, name, out);
        return out.isEmpty() ? null : out.getFirst();
    }

    private static JLabel label(final SessionBar bar, final String name) {
        final List<JLabel> out = new ArrayList<>();
        collect(bar.component(), JLabel.class, name, out);
        assertThat(out).as("a label %s", name).isNotEmpty();
        return out.getFirst();
    }

    private static <T extends Component> void collect(final Component c, final Class<T> type, final String name, final List<T> out) {
        if (type.isInstance(c) && name.equals(c.getAccessibleContext().getAccessibleName())) {
            out.add(type.cast(c));
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                collect(child, type, name, out);
            }
        }
    }
}
