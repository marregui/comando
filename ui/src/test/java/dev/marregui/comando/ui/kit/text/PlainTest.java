package dev.marregui.comando.ui.kit.text;

import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JRadioButton;
import javax.swing.plaf.basic.BasicHTML;

import static org.assertj.core.api.Assertions.assertThat;

/// [Plain] keeps Swing from rendering text as HTML.
/// - Swing builds an HTML view for text that starts with `<html>` unless guarded.
/// - [Plain#text] defuses the prefix in any case; [Plain#only] guards a button's later and earlier text.
/// - Ordinary text and null pass unchanged.
class PlainTest {

    private static final String HOSTILE = "<html><img src='http://127.0.0.1:9/x.png'>";

    /// A label with an `<html><img>` text gets an HTML view (the control), while the same text through [Plain#text], set
    /// on a guarded radio button, or set before the guard gets none, so no remote image is fetched.
    @Test
    void swingRendersAnHtmlPrefixUnlessGuarded() {
        assertThat(new JLabel(HOSTILE).getClientProperty(BasicHTML.propertyKey)).as("control: Swing builds an HTML view").isNotNull();
        assertThat(new JLabel(Plain.text(HOSTILE)).getClientProperty(BasicHTML.propertyKey)).isNull();
        JRadioButton option = Plain.only(new JRadioButton());
        option.setText(HOSTILE);
        assertThat(option.getClientProperty(BasicHTML.propertyKey)).isNull();
        assertThat(Plain.only(new JRadioButton(HOSTILE)).getClientProperty(BasicHTML.propertyKey)).as("text set before the guard is rebuilt").isNull();
    }

    /// Text without the prefix, a lone tag and null are unchanged; `<HTML>` in upper case is defused with a leading
    /// zero-width space.
    @Test
    void ordinaryTextIsUnchanged() {
        assertThat(Plain.text("SELECT 1")).isEqualTo("SELECT 1");
        assertThat(Plain.text("<b>")).isEqualTo("<b>");
        assertThat(Plain.text(null)).isNull();
        assertThat(Plain.text("<HTML>x")).startsWith("​");
    }
}
