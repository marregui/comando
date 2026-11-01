package dev.marregui.comando.ui.dialogs;

import java.awt.Component;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JTextField;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.marregui.comando.ui.kit.async.OnEdt;
import dev.marregui.comando.ui.kit.theme.Theme;

import static org.assertj.core.api.Assertions.assertThat;

/// [FormRows] lays out the fields the Files tab folders window and the kit jobs window share.
/// - A row's label points at its input, and both explain it under the mouse; the input is named by the label, also
///   when it sits inside a field with a button.
/// - The message line says what was refused first, then the first problem by its label, then the note; a problem is
///   outlined and red only when it is marked.
@ExtendWith(OnEdt.class)
class FormRowsTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private static JLabel captionOf(final FormRows form, final Component input) {
        return Arrays.stream(form.panel().getComponents())
                .filter(c -> c instanceof JLabel l && l.getLabelFor() == input)
                .map(JLabel.class::cast)
                .findFirst().orElseThrow();
    }

    /// The label of a row points at its input, which takes the label as its accessible name and the tip as tooltip.
    @Test
    void rowGivesItsInputTheLabelAccessibleNameAndTooltip() {
        final FormRows form = new FormRows();
        final JTextField name = new JTextField();
        final JTextField path = new JTextField();
        final JButton choose = new JButton("…");
        form.row("Name", name, name, "name", "The row's name");
        form.row("Folder", FormRows.withButton(path, choose), path, "path", "The folder the row shows");

        final JLabel nameLabel = captionOf(form, name);
        assertThat(nameLabel.getText()).isEqualTo("Name");
        assertThat(nameLabel.getToolTipText()).isEqualTo("The row's name");
        assertThat(name.getAccessibleContext().getAccessibleName()).isEqualTo("Name");
        assertThat(name.getToolTipText()).isEqualTo("The row's name");

        final JLabel pathLabel = captionOf(form, path);
        assertThat(pathLabel.getText()).isEqualTo("Folder");
        assertThat(path.getAccessibleContext().getAccessibleName()).isEqualTo("Folder");
        assertThat(path.getToolTipText()).isEqualTo("The folder the row shows");
        assertThat(choose.getToolTipText()).as("the button keeps its own tip").isNull();
    }

    /// A marked problem is outlined and said in red by its label; an unmarked one is said in grey and not outlined;
    /// what was refused is said first, and the note when nothing fails.
    @Test
    void messageSaysRefusedThenFirstProblemThenNote() {
        final FormRows form = new FormRows();
        final JTextField name = new JTextField();
        final JTextField path = new JTextField();
        final JLabel facts = new JLabel(" ");
        form.row("Name", name, name, "name", "tip");
        form.row("Folder", path, path, "path", "tip");
        form.footer(facts, "Facts");
        final JLabel message = Arrays.stream(form.panel().getComponents())
                .filter(c -> c instanceof JLabel l && "Problem".equals(l.getAccessibleContext().getAccessibleName()))
                .map(JLabel.class::cast).findFirst().orElseThrow();

        final Map<String, String> problems = new LinkedHashMap<>();
        problems.put("path", "no such folder");
        form.show(problems, why -> !"checking…".equals(why), "", "a note");
        assertThat(form.messageShown()).isEqualTo("Folder: no such folder");
        assertThat(message.getForeground()).isEqualTo(Theme.DANGER);
        assertThat(path.getClientProperty("JComponent.outline")).isEqualTo("error");
        assertThat(name.getClientProperty("JComponent.outline")).isNull();

        problems.put("path", "checking…");
        form.show(problems, why -> !"checking…".equals(why), "", "a note");
        assertThat(form.messageShown()).isEqualTo("Folder: checking…");
        assertThat(message.getForeground()).isEqualTo(Theme.MUTED_STRONG);
        assertThat(path.getClientProperty("JComponent.outline")).isNull();

        form.show(problems, why -> true, "not taken", "a note");
        assertThat(form.messageShown()).isEqualTo("not taken");
        assertThat(message.getForeground()).isEqualTo(Theme.DANGER);

        form.show(Map.of(), why -> true, "", "a note");
        assertThat(form.messageShown()).isEqualTo("a note");
        assertThat(message.getForeground()).isEqualTo(Theme.MUTED_STRONG);
        assertThat(facts.getAccessibleContext().getAccessibleName()).isEqualTo("Facts");
    }
}
