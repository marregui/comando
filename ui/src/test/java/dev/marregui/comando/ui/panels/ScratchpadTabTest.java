package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.view.ViewSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScratchpadTabTest {

    private static ViewSpec.TreeNode file(String name) {
        return new ViewSpec.TreeNode(name, "", "comando-file:/s/" + name + "#0", List.of());
    }

    private static ViewSpec.TreeNode folder(String name) {
        return new ViewSpec.TreeNode(name, "", List.of());
    }

    @Test
    void eachKindOfEntryHasItsIcon() {
        assertThat(ScratchpadTab.iconName(folder("2026-10-14"))).isEqualTo("folder");
        assertThat(ScratchpadTab.iconName(folder("work"))).isEqualTo("folder-work");
        assertThat(ScratchpadTab.iconName(file("NEVSKY-PLAN.md"))).isEqualTo("plan");
        assertThat(ScratchpadTab.iconName(file("READ-01-X-OCT-14.md"))).isEqualTo("read");
        assertThat(ScratchpadTab.iconName(file("ALPHA.md"))).isEqualTo("index");
        assertThat(ScratchpadTab.iconName(file("notes.md"))).isEqualTo("markdown");
        assertThat(ScratchpadTab.iconName(file("Red.java"))).isEqualTo("code");
        assertThat(ScratchpadTab.iconName(file("codex.json"))).isEqualTo("json");
        assertThat(ScratchpadTab.iconName(file("run.sh"))).isEqualTo("terminal");
        assertThat(ScratchpadTab.iconName(file("q.sql"))).isEqualTo("database");
        assertThat(ScratchpadTab.iconName(file("shot.png"))).isEqualTo("image");
        assertThat(ScratchpadTab.iconName(file("cpu.jfr"))).isEqualTo("chart");
        assertThat(ScratchpadTab.iconName(file("fix.patch"))).isEqualTo("diff");
        assertThat(ScratchpadTab.iconName(file("gradle.log"))).isEqualTo("text");
        assertThat(ScratchpadTab.iconName(file("blob"))).isEqualTo("file");
    }
}
