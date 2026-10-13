package dev.marregui.comando.ui.kit.text;

import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JRadioButton;
import javax.swing.plaf.basic.BasicHTML;

import static org.assertj.core.api.Assertions.assertThat;

class PlainTest {

    private static final String HOSTILE = "<html><img src='http://127.0.0.1:9/x.png'>";

    @Test
    void swingRendersAnHtmlPrefixUnlessGuarded() {
        assertThat(new JLabel(HOSTILE).getClientProperty(BasicHTML.propertyKey)).as("control: Swing builds an HTML view").isNotNull();
        assertThat(new JLabel(Plain.text(HOSTILE)).getClientProperty(BasicHTML.propertyKey)).isNull();
        JRadioButton option = Plain.only(new JRadioButton());
        option.setText(HOSTILE);
        assertThat(option.getClientProperty(BasicHTML.propertyKey)).isNull();
        assertThat(Plain.only(new JRadioButton(HOSTILE)).getClientProperty(BasicHTML.propertyKey)).as("text set before the guard is rebuilt").isNull();
    }

    @Test
    void ordinaryTextIsUnchanged() {
        assertThat(Plain.text("SELECT 1")).isEqualTo("SELECT 1");
        assertThat(Plain.text("<b>")).isEqualTo("<b>");
        assertThat(Plain.text(null)).isNull();
        assertThat(Plain.text("<HTML>x")).startsWith("​");
    }
}
