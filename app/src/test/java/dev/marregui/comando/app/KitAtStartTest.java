package dev.marregui.comando.app;

import dev.marregui.comando.claude.Kit;
import dev.marregui.comando.ui.shell.Setup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class KitAtStartTest {

    private static Kit.Status status(String installed, List<String> existing) {
        return status(installed, true, true, existing);
    }

    private static Kit.Status status(String installed, boolean enabled, boolean fromCopy, List<String> existing) {
        boolean in = !installed.isEmpty();
        return new Kit.Status("1.2.3", installed, in && enabled, in ? "/k" : "", in && fromCopy, existing, List.of("x"));
    }

    @Test
    void theKitIsOfferedOnlyWhenClaudeCodeLacksItAndNoSetupOfTheUsersOwnStands() {
        assertThat(App.atStart(status("", List.of()), false)).isEqualTo(App.KitAtStart.OFFER);
        assertThat(App.atStart(status("", List.of()), true)).isEqualTo(App.KitAtStart.OFFER);
        assertThat(App.atStart(status("", List.of("~/.claude/skills/reflect")), false)).isEqualTo(App.KitAtStart.NOTHING);
        assertThat(App.atStart(status("1.2.3", List.of()), true)).as("the copy changed under an installed kit").isEqualTo(App.KitAtStart.UPDATED);
        assertThat(App.atStart(status("1.2.3", List.of()), false)).isEqualTo(App.KitAtStart.NOTHING);
        assertThat(App.atStart(status("1.2.3", List.of("~/.claude/skills/reflect")), true)).isEqualTo(App.KitAtStart.UPDATED);
        assertThat(App.atStart(status("1.2.3", false, true, List.of()), true)).as("disabled: nothing loads it").isEqualTo(App.KitAtStart.NOTHING);
        assertThat(App.atStart(status("1.2.3", true, false, List.of()), true)).as("loaded from a clone").isEqualTo(App.KitAtStart.NOTHING);
        assertThat(App.atStart(status("1.2.3", false, true, List.of("~/a")), false)).as("not offered either").isEqualTo(App.KitAtStart.NOTHING);
    }

    @Test
    void theOfferCarriesWhatTheKitFound() {
        Setup.KitOffer offer = KitSetup.offer(new Kit.Status("1.2.3", "1.0.0", false, "/k/kit", false, List.of("~/a"), List.of("Enable")));

        assertThat(offer).isEqualTo(new Setup.KitOffer(Kit.PLUGIN, "1.2.3", "1.0.0", false, "/k/kit", List.of("~/a"), List.of("Enable"), List.of()));
    }

    @Test
    void onlyTheUsersOwnDataRootIsOfferedTheKitAndItsPathIsNormalised(@TempDir Path tmp) {
        String root = tmp.resolve("x/../data").toString();
        assertThat(withProperties(root, null, AppConfig::fromEnvironment)).satisfies(c -> {
            assertThat(c.home()).isEqualTo(tmp.resolve("data"));
            assertThat(c.kitDir()).isEqualTo(tmp.resolve("data/kit"));
            assertThat(c.offerKit()).as("a data root of a test or a second window").isFalse();
        });
        assertThat(withProperties(root, "true", AppConfig::fromEnvironment).offerKit()).isTrue();
        String standard = Path.of(System.getProperty("user.home"), ".comando").toString();
        assertThat(withProperties(standard + "/./", null, AppConfig::fromEnvironment).offerKit()).isTrue();
        assertThat(withProperties(standard, "false", AppConfig::fromEnvironment).offerKit()).isFalse();
        assertThat(new AppConfig(tmp, "claude", tmp, false).offerKit()).as("tests and tools").isFalse();
    }

    private static <T> T withProperties(String home, String offerKit, Supplier<T> body) {
        String oldHome = System.getProperty("comando.home");
        String oldOffer = System.getProperty("comando.offerKit");
        try {
            System.setProperty("comando.home", home);
            if (offerKit == null) {
                System.clearProperty("comando.offerKit");
            } else {
                System.setProperty("comando.offerKit", offerKit);
            }
            return body.get();
        } finally {
            restore("comando.home", oldHome);
            restore("comando.offerKit", oldOffer);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
