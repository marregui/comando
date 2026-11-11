package dev.marregui.comando.ui.kit.theme;

import java.awt.Font;
import java.awt.font.TextAttribute;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.JLabel;
import javax.swing.LookAndFeel;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.plaf.UIResource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.marregui.comando.ui.kit.async.OnEdt;

import static org.assertj.core.api.Assertions.assertThat;

/// A theme's [Style] sets how the window is drawn around its colours (record 151).
/// - [Theme#style] follows the theme chosen, and is plain for a ground and tint that make no theme.
/// - Under the console style the corners are square and the tabs are in the technical face; plain chosen again puts
///   back every default the plain look had.
/// - The technical face is a DIN face when one is installed, else the look's font, bold at the caption step, tracked.
/// - A sidebar header already shown takes the technical face under the console style, and its own font back under
///   the plain one.
@ExtendWith(OnEdt.class)
class StyleTest {

    /// The defaults the style sets.
    private static final List<String> KEYS = List.of("Component.arc", "Button.arc", "TextComponent.arc", "ScrollBar.thumbArc",
            "TabbedPane.font");

    /// Plain is the default; a console theme chosen makes it console, a ground and tint that make no theme plain, the
    /// other console theme console again, and graphite and gold plain.
    @Test
    void theStyleFollowsTheThemeChosen() {
        try {
            assertThat(Theme.style()).isEqualTo(Style.PLAIN);
            choose(NamedTheme.CONSOLE_AMBER);
            assertThat(Theme.style()).isEqualTo(Style.CONSOLE);
            Theme.choose(Ground.MIDNIGHT, Tint.TREASURE);
            assertThat(Theme.style()).as("a ground and tint no theme pairs").isEqualTo(Style.PLAIN);
            choose(NamedTheme.CONSOLE_CYAN);
            assertThat(Theme.style()).isEqualTo(Style.CONSOLE);
            choose(NamedTheme.GRAPHITE_GOLD);
            assertThat(Theme.style()).isEqualTo(Style.PLAIN);
        } finally {
            choose(NamedTheme.GRAPHITE_GOLD);
        }
    }

    /// On comando's look, plain has today's arcs and the look's own tab font; console has square corners and the tabs
    /// in the technical face, as a UI resource; plain chosen again leaves every one of those defaults as it was.
    @Test
    void plainConsolePlainLeavesTheLookAsItWas() throws UnsupportedLookAndFeelException {
        final LookAndFeel before = UIManager.getLookAndFeel();
        Theme.install();
        try {
            final Map<String, Object> plain = defaults();
            assertThat(plain).containsEntry("Component.arc", Theme.RADIUS).containsEntry("Button.arc", Theme.RADIUS)
                    .containsEntry("TextComponent.arc", Theme.RADIUS).containsEntry("ScrollBar.thumbArc", 999);
            assertThat(plain.get("TabbedPane.font")).as("the look's own").isNotNull().isNotEqualTo(Theme.technical());
            choose(NamedTheme.CONSOLE_AMBER);
            final Map<String, Object> console = defaults();
            assertThat(console).containsEntry("Component.arc", 0).containsEntry("Button.arc", 0)
                    .containsEntry("TextComponent.arc", 0).containsEntry("ScrollBar.thumbArc", 0);
            assertThat(console.get("TabbedPane.font")).isInstanceOf(UIResource.class).isEqualTo(Theme.technical());
            choose(NamedTheme.GRAPHITE_GOLD);
            assertThat(defaults()).isEqualTo(plain);
        } finally {
            choose(NamedTheme.GRAPHITE_GOLD);
            UIManager.setLookAndFeel(before);
        }
    }

    /// Both DIN faces installed give DIN Condensed; DIN Alternate alone gives it; neither gives the look's font, and no
    /// look's font Java's logical sans. Each is bold at the caption step of the look's size, tracked by 0.08.
    @Test
    void theTechnicalFaceIsADinFaceElseTheLooksFont() {
        final Font ui = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        final Font both = Theme.pickTechnical(Set.of("Helvetica", "DIN Alternate", "DIN Condensed"), ui);
        assertThat(both.getName()).isEqualTo("DIN Condensed");
        assertThat(Theme.pickTechnical(Set.of("DIN Alternate"), ui).getName()).isEqualTo("DIN Alternate");
        final Font none = Theme.pickTechnical(Set.of("Helvetica"), ui);
        assertThat(none.getName()).as("the look's font").isEqualTo(Font.SANS_SERIF);
        assertThat(Theme.pickTechnical(Set.of(), null).getName()).as("no look's font").isEqualTo(Font.DIALOG);
        for (final Font f : List.of(both, none)) {
            assertThat(f.isBold()).as(f.getName()).isTrue();
            assertThat(f.getSize2D()).as(f.getName()).isEqualTo(Theme.size(13, Theme.TextSize.CAPTION));
            assertThat(f.getAttributes().get(TextAttribute.TRACKING)).as(f.getName()).isEqualTo(Theme.TECHNICAL_TRACKING);
        }
        assertThat(Theme.TECHNICAL_TRACKING).isEqualTo(0.08f);
        assertThat(Theme.pickTechnical(Set.of(), ui.deriveFont(12f)).getSize2D()).as("the caption step of a 12 pt look")
                .isEqualTo(Theme.size(12, Theme.TextSize.CAPTION));
    }

    /// A header built under plain is in its spaced capitals; console chosen, the same header, already shown, is in the
    /// technical face, as [SidebarLook#headerFont] is; plain chosen again, it has its font back. A header built under
    /// console reads the same.
    @Test
    void aHeaderAlreadyShownFollowsTheStyle() {
        try {
            final JLabel header = SidebarLook.header("Brain");
            final Font plain = header.getFont();
            assertThat(plain).isEqualTo(SidebarLook.headerFont(UIManager.getFont("Label.font")));
            assertThat(plain.getAttributes().get(TextAttribute.TRACKING)).isEqualTo(0.12f);
            choose(NamedTheme.CONSOLE_CYAN);
            assertThat(header.getFont()).isEqualTo(Theme.technical());
            assertThat(SidebarLook.headerFont(plain)).isEqualTo(Theme.technical());
            final JLabel builtUnderConsole = SidebarLook.header("Brain");
            assertThat(builtUnderConsole.getFont()).isEqualTo(Theme.technical());
            choose(NamedTheme.GRAPHITE_GOLD);
            assertThat(header.getFont()).as("its font back").isEqualTo(plain);
            assertThat(builtUnderConsole.getFont()).as("built under console, its plain font").isEqualTo(plain);
        } finally {
            choose(NamedTheme.GRAPHITE_GOLD);
        }
    }

    private static void choose(final NamedTheme t) {
        Theme.choose(t.ground, t.tint);
    }

    private static Map<String, Object> defaults() {
        final Map<String, Object> out = new HashMap<>();
        for (final String key : KEYS) {
            out.put(key, UIManager.get(key));
        }
        return out;
    }
}
