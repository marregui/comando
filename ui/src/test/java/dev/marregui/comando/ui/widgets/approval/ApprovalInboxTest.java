package dev.marregui.comando.ui.widgets.approval;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.PermissionRules;
import dev.marregui.comando.ui.kit.action.ActionRegistry;
import dev.marregui.comando.ui.widget.WidgetContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalInboxTest {

    private static final long T0 = 1_000_000_000L;

    private final List<Intent> sent = new ArrayList<>();
    private final AtomicLong now = new AtomicLong(T0);
    private final FakeFocus focus = new FakeFocus();
    private final ApprovalInbox inbox = new ApprovalInbox(new WidgetContext(sent::add, new ActionRegistry()), now::get, focus);

    @AfterEach
    void unmount() {
        inbox.unmount();
    }

    /** The focus owner a test sets, and every component the inbox asked to focus. */
    private static final class FakeFocus implements ApprovalInbox.Focus {
        Component owner;
        final List<Component> requested = new ArrayList<>();

        @Override
        public Component owner() {
            return owner;
        }

        @Override
        public void request(Component c) {
            requested.add(c);
        }
    }

    private static Approval bash(long id, String command) {
        return new Approval(id, "work", ApprovalKind.TOOL, "Bash", "{\"command\":\"" + command + "\"}", command, T0);
    }

    private static Approval question(long id) {
        return new Approval(id, "work", ApprovalKind.QUESTION, "AskUserQuestion", """
                {"questions":[{"question":"Which approach?","multiSelect":false,"options":[
                  {"label":"Fast","description":"<html><img src='http://x/y.png'>skips the cache"},
                  {"label":"Safe","description":"keeps the cache"}]}]}""", "Which approach?", T0);
    }

    private static Approval plan(long id) {
        return new Approval(id, "work", ApprovalKind.PLAN, "ExitPlanMode", "{\"plan\":\"1. do it\"}", "plan", T0);
    }

    private List<Component> cards() {
        return cardsOf(inbox);
    }

    private static List<Component> cardsOf(ApprovalInbox inbox) {
        Container list = (Container) ((JScrollPane) find(inbox.component(), c -> c instanceof JScrollPane)).getViewport().getView();
        List<Component> out = new ArrayList<>();
        for (Component c : list.getComponents()) {
            if (c instanceof JPanel) {
                out.add(c);
            }
        }
        return out;
    }

    private static Component find(Component root, Predicate<Component> p) {
        if (p.test(root)) {
            return root;
        }
        if (root instanceof Container k) {
            for (Component c : k.getComponents()) {
                Component f = find(c, p);
                if (f != null) {
                    return f;
                }
            }
        }
        return null;
    }

    private static Component named(Component root, String name) {
        return find(root, c -> name.equals(c.getName()));
    }

    private static boolean press(Component c, int key, int modifiers) {
        return SwingUtilities.processKeyBindings(new KeyEvent(c, KeyEvent.KEY_PRESSED, 0, modifiers, key, KeyEvent.CHAR_UNDEFINED));
    }

    private static void type(Component c, char ch) {
        SwingUtilities.processKeyBindings(new KeyEvent(c, KeyEvent.KEY_TYPED, 0, 0, KeyEvent.VK_UNDEFINED, ch));
    }

    private ApprovalDecision decision() {
        assertThat(sent).hasSize(1);
        return ((Intent.ApprovalAnswered) sent.getFirst()).decision();
    }

    // ---- keys from anywhere in the card

    /** A typed note is an objection: Enter sends it with a denial, never with a call that runs. */
    @Test
    void enterInANoteWithTextDeniesWithItAndALetterThereTypes() {
        inbox.render(List.of(bash(1, "rm -rf build")));
        JTextField note = (JTextField) named(cards().getFirst(), "note");
        assertThat(note.getClientProperty("JTextField.placeholderText")).isEqualTo("Note for Claude: ⏎ denies with it");
        for (char c : "no, keep build/".toCharArray()) {
            press(note, KeyEvent.getExtendedKeyCodeForChar(c), 0);
            type(note, c);
            note.setCaretPosition(note.getDocument().getLength()); // a field never shown keeps its caret at 0
        }
        assertThat(sent).isEmpty();
        assertThat(note.getText()).isEqualTo("no, keep build/");

        press(note, KeyEvent.VK_ENTER, 0);
        assertThat(decision()).isEqualTo(new ApprovalDecision.Deny("no, keep build/"));
    }

    @Test
    void enterInAnEmptyNoteGivesThePrimaryAnswer() {
        inbox.render(List.of(bash(1, "ls -la")));
        JTextField note = (JTextField) named(cards().getFirst(), "note");
        note.setText("   ");
        press(note, KeyEvent.VK_ENTER, 0);
        assertThat(decision()).isEqualTo(ApprovalDecision.allowOnce());
    }

    @Test
    void theMenuKeyWithEnterInTheNoteDeniesWithTheNote() {
        inbox.render(List.of(bash(1, "ls -la")));
        JTextField note = (JTextField) named(cards().getFirst(), "note");
        note.setText("not now");
        press(note, KeyEvent.VK_ENTER, InputEvent.META_DOWN_MASK);
        assertThat(decision()).isEqualTo(new ApprovalDecision.Deny("not now"));
    }

    @Test
    void escapeInTheNoteReturnsToTheCardWithoutAnswering() {
        inbox.render(List.of(bash(1, "ls -la")));
        Component card = cards().getFirst();
        press(named(card, "note"), KeyEvent.VK_ESCAPE, 0);
        assertThat(sent).isEmpty();
        assertThat(focus.requested).containsExactly(card);
    }

    @Test
    void theCardsLettersWorkFromItsViewer() {
        inbox.render(List.of(bash(1, "ls -la")));
        Component viewer = find(cards().getFirst(), c -> c instanceof JTextComponent t && !t.isEditable() && t.isFocusable());
        press(viewer, KeyEvent.VK_ENTER, 0);
        assertThat(decision()).isEqualTo(ApprovalDecision.allowOnce());
    }

    @Test
    void deniesFromTheViewerWithTheTypedNote() {
        inbox.render(List.of(bash(1, "ls -la")));
        Component card = cards().getFirst();
        ((JTextField) named(card, "note")).setText("why");
        press(find(card, c -> c instanceof JTextComponent t && !t.isEditable() && t.isFocusable()), KeyEvent.VK_D, 0);
        assertThat(decision()).isEqualTo(new ApprovalDecision.Deny("why"));
    }

    @Test
    void upAndDownMoveBetweenCards() {
        inbox.render(List.of(bash(1, "ls"), bash(2, "pwd"), bash(3, "id")));
        List<Component> cards = cards();
        press(cards.get(1), KeyEvent.VK_DOWN, 0);
        press(named(cards.get(1), "note"), KeyEvent.VK_UP, 0);
        press(cards.get(2), KeyEvent.VK_DOWN, 0);
        assertThat(focus.requested).containsExactly(cards.get(2), cards.get(0));
    }

    @Test
    void aPressOnTheCardsTextFocusesTheCard() {
        inbox.render(List.of(bash(1, "ls")));
        Component card = cards().getFirst();
        Component age = named(card, "age");
        age.dispatchEvent(new MouseEvent(age, MouseEvent.MOUSE_PRESSED, 0, 0, 1, 1, 1, false, MouseEvent.BUTTON1));
        assertThat(focus.requested).containsExactly(card);
    }

    // ---- focus after a card leaves

    @Test
    void theFocusMovesToTheCardThatTookThePlaceOfTheAnsweredOne() {
        inbox.render(List.of(bash(1, "ls"), bash(2, "pwd"), bash(3, "id")));
        List<Component> cards = cards();
        focus.owner = named(cards.get(1), "note");
        press(focus.owner, KeyEvent.VK_ENTER, 0);
        inbox.render(List.of(bash(1, "ls"), bash(3, "id")));
        assertThat(focus.requested).containsExactly(cards.get(2));

        focus.owner = cards.get(2);
        press(focus.owner, KeyEvent.VK_A, 0);
        inbox.render(List.of(bash(1, "ls")));
        assertThat(focus.requested).containsExactly(cards.get(2), cards.get(0));

        focus.owner = cards.get(0);
        press(focus.owner, KeyEvent.VK_D, 0);
        inbox.render(List.of());
        assertThat(focus.requested).hasSize(2);
        assertThat(sent).hasSize(3);
    }

    /**
     * Claude Code withdraws the call while the user types an objection into its note: the rest of the typing
     * must not answer the card that moved up, which the user never looked at.
     */
    @Test
    void aCardWithdrawnWhileTheUserTypesInItsNoteLeavesTheKeyboardWhereNoKeyAnswers() {
        inbox.render(List.of(bash(1, "ls"), bash(2, "rm -rf build")));
        Component note = named(cards().getFirst(), "note");
        focus.owner = note;
        for (char c : "add a te".toCharArray()) {
            press(note, KeyEvent.getExtendedKeyCodeForChar(c), 0);
            type(note, c);
        }
        assertThat(sent).isEmpty();

        inbox.render(List.of(bash(2, "rm -rf build")));
        assertThat(focus.requested).hasSize(1);
        Component now = focus.requested.getLast();
        assertThat(now).isSameAs(named(inbox.component(), "inbox"));
        assertThat(now.isFocusable()).isTrue();
        for (int key : new int[] {KeyEvent.VK_A, KeyEvent.VK_S, KeyEvent.VK_ENTER, KeyEvent.VK_L, KeyEvent.VK_L, KeyEvent.VK_D}) {
            press(now, key, 0);
        }
        assertThat(sent).as("no answer from the keys typed after the withdrawal").isEmpty();
    }

    @Test
    void aCardLeavingWhileTheFocusIsElsewhereTakesNoFocus() {
        inbox.render(List.of(bash(1, "ls"), bash(2, "pwd")));
        focus.owner = new JTextField();
        inbox.render(List.of(bash(2, "pwd")));
        assertThat(focus.requested).isEmpty();
    }

    // ---- always: the rule shown, and a second press

    @Test
    void alwaysShowsItsRuleAndNeedsASecondPress() {
        inbox.render(List.of(bash(1, "git commit -m x")));
        Component card = cards().getFirst();
        assertThat(((JTextComponent) named(card, "rule")).getText()).isEqualTo("Always: Bash(git commit:*)");
        assertThat(((AbstractButton) named(card, "always")).getToolTipText()).contains("Bash(git commit:*)", "settings.local.json");

        press(card, KeyEvent.VK_L, 0);
        assertThat(sent).isEmpty();
        assertThat(((JLabel) named(card, "confirm")).getText()).isEqualTo("Press L again to always allow Bash(git commit:*)");

        now.addAndGet(ApprovalInbox.CONFIRM_MILLIS - 1);
        press(card, KeyEvent.VK_L, 0);
        assertThat(decision()).isEqualTo(new ApprovalDecision.Allow(ApprovalDecision.Scope.ALWAYS));
    }

    @Test
    void aSecondPressAfterTheWindowAsksAgain() {
        inbox.render(List.of(bash(1, "ls -la")));
        Component card = cards().getFirst();
        press(card, KeyEvent.VK_L, 0);
        now.addAndGet(ApprovalInbox.CONFIRM_MILLIS + 500);
        press(card, KeyEvent.VK_L, 0);
        assertThat(sent).isEmpty();
        press(card, KeyEvent.VK_L, 0);
        assertThat(decision()).isEqualTo(new ApprovalDecision.Allow(ApprovalDecision.Scope.ALWAYS));
    }

    @Test
    void clickingAlwaysAsksInTheCardFirst() {
        inbox.render(List.of(bash(1, "ls -la")));
        Component card = cards().getFirst();
        ((AbstractButton) named(card, "always")).doClick();
        assertThat(sent).isEmpty();
        assertThat(named(card, "confirm").getParent().isVisible()).isTrue();
        ((AbstractButton) named(card, "confirm-always")).doClick();
        assertThat(decision()).isEqualTo(new ApprovalDecision.Allow(ApprovalDecision.Scope.ALWAYS));
    }

    @Test
    void escapeCancelsAPendingAlwaysBeforeItDenies() {
        inbox.render(List.of(bash(1, "ls -la")));
        Component card = cards().getFirst();
        press(card, KeyEvent.VK_L, 0);
        press(card, KeyEvent.VK_ESCAPE, 0);
        assertThat(sent).isEmpty();
        assertThat(named(card, "confirm").getParent().isVisible()).isFalse();
        press(card, KeyEvent.VK_ESCAPE, 0);
        assertThat(decision()).isEqualTo(new ApprovalDecision.Deny(""));
    }

    @Test
    void aRuleThatMayNotBeSavedOffersTheSessionOnlyAndLDoesNothing() {
        ApprovalInbox sessionOnly = new ApprovalInbox(new WidgetContext(sent::add, new ActionRegistry()), now::get, focus,
                (tool, field) -> new PermissionRules.Rules(List.of(new PermissionRules.Rule(tool, "")), false));
        try {
            sessionOnly.render(List.of(new Approval(1, "work", ApprovalKind.TOOL, "mcp__x__delete_all", "{}", "delete all", T0)));
            Component card = cardsOf(sessionOnly).getFirst();
            assertThat(named(card, "always")).isNull();
            assertThat(((JTextComponent) named(card, "rule")).getText()).isEqualTo("For session: mcp__x__delete_all");
            assertThat(((AbstractButton) named(card, "session")).getToolTipText()).contains("mcp__x__delete_all", "this session");

            press(card, KeyEvent.VK_L, 0);
            press(card, KeyEvent.VK_L, 0);
            assertThat(sent).isEmpty();
            press(card, KeyEvent.VK_ESCAPE, 0);
            assertThat(decision()).isEqualTo(new ApprovalDecision.Deny(""));
        } finally {
            sessionOnly.unmount();
        }
    }

    @Test
    void severalRulesShowJoined() {
        ApprovalInbox compound = new ApprovalInbox(new WidgetContext(sent::add, new ActionRegistry()), now::get, focus,
                (tool, field) -> new PermissionRules.Rules(List.of(new PermissionRules.Rule("Bash", "git status"),
                        new PermissionRules.Rule("Bash", "npm test:*")), true));
        try {
            compound.render(List.of(bash(1, "git status && npm test")));
            Component card = cardsOf(compound).getFirst();
            assertThat(((JTextComponent) named(card, "rule")).getText()).isEqualTo("Always: Bash(git status), Bash(npm test:*)");
            press(card, KeyEvent.VK_L, 0);
            assertThat(((JLabel) named(card, "confirm")).getText()).isEqualTo("Press L again to always allow Bash(git status), Bash(npm test:*)");
            press(card, KeyEvent.VK_L, 0);
            assertThat(decision()).isEqualTo(new ApprovalDecision.Allow(ApprovalDecision.Scope.ALWAYS));
        } finally {
            compound.unmount();
        }
    }

    // ---- questions and plans

    @Test
    void aQuestionStartsWithNoChoiceAndShowsEachDescriptionAsText() {
        inbox.render(List.of(question(1)));
        Component card = cards().getFirst();
        List<JRadioButton> options = new ArrayList<>();
        collect(card, JRadioButton.class, options);
        assertThat(options).extracting(AbstractButton::getText).containsExactly("Fast", "Safe");
        assertThat(options).noneMatch(AbstractButton::isSelected);

        List<JTextComponent> descriptions = new ArrayList<>();
        collect(card, JTextComponent.class, descriptions);
        assertThat(descriptions.stream().filter(d -> "description".equals(d.getName())).map(JTextComponent::getText))
                .containsExactly("<html><img src='http://x/y.png'>skips the cache", "keeps the cache");

        press(card, KeyEvent.VK_ENTER, 0);
        assertThat(sent).isEmpty();
        assertThat(named(card, "choose").isVisible()).isTrue();

        options.get(1).setSelected(true);
        assertThat(named(card, "choose").isVisible()).isFalse();
        press(card, KeyEvent.VK_ENTER, 0);
        assertThat(decision()).isEqualTo(new ApprovalDecision.Answer(Map.of("Which approach?", "Safe")));
    }

    @Test
    void enterInTheOtherAnswerFieldAnswersWithIt() {
        inbox.render(List.of(question(1)));
        Component card = cards().getFirst();
        JTextField free = (JTextField) find(card, c -> c instanceof JTextField f && !"note".equals(f.getName()));
        type(free, 'a');
        press(free, KeyEvent.VK_A, 0);
        assertThat(sent).isEmpty();
        press(free, KeyEvent.VK_ENTER, 0);
        assertThat(decision()).isEqualTo(new ApprovalDecision.Answer(Map.of("Which approach?", "a")));
    }

    @Test
    void aPlanCanBeApprovedWithEditsAccepted() {
        inbox.render(List.of(plan(1)));
        AbstractButton edits = (AbstractButton) find(cards().getFirst(), c -> c instanceof AbstractButton b && b.getText().startsWith("Approve, auto-accept"));
        edits.doClick();
        assertThat(decision()).isEqualTo(new ApprovalDecision.Allow(ApprovalDecision.Scope.SESSION));
    }

    // ---- age

    @Test
    void theAgeTicksWhileCardsWait() {
        inbox.render(List.of(bash(1, "ls")));
        JLabel age = (JLabel) named(cards().getFirst(), "age");
        assertThat(age.getText()).isEqualTo("now");
        assertThat(inbox.ticking()).isTrue();

        now.addAndGet(130_000);
        inbox.tick();
        assertThat(age.getText()).isEqualTo("2 min ago");

        inbox.render(List.of());
        assertThat(inbox.ticking()).isFalse();
    }

    private static <T> void collect(Component root, Class<T> type, List<T> out) {
        if (type.isInstance(root)) {
            out.add(type.cast(root));
        }
        if (root instanceof Container k) {
            for (Component c : k.getComponents()) {
                collect(c, type, out);
            }
        }
    }

    /** A statement card names the connection it would change; the status-bar notice is gone after the next one. */
    @Test
    void aStatementCardNamesItsConnection() {
        Approval sql = new Approval(9, "work", ApprovalKind.SQL_WRITE, "db_run_sql",
                "{\"connection\":\"local QuestDB\",\"sql\":\"insert into t values (1)\"}", "Run on local QuestDB: insert", T0);
        assertThat(ApprovalInbox.title(sql)).isEqualTo("wants to run a statement on local QuestDB");
    }
}
