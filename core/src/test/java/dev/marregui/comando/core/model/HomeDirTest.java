package dev.marregui.comando.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [HomeDir] expands a home shorthand and shortens only paths contained by the current home directory.
///
/// - the bare home directory maps to `~`
/// - child paths retain their suffixes
/// - matching name prefixes outside the directory remain literal
class HomeDirTest {

    private final String home = System.getProperty("user.home");

    /// `~` and `~/notes` expand from the current home, while other-user and absolute paths stay literal; shortening reverses only complete home boundaries.
    @Test
    void theHomeFolderIsExpandedAndShortenedAtAPathBoundaryOnly() {
        assertThat(HomeDir.expand("~")).isEqualTo(home);
        assertThat(HomeDir.expand("~/notes")).isEqualTo(home + "/notes");
        assertThat(HomeDir.expand("~other/x")).as("another user's home is not expanded").isEqualTo("~other/x");
        assertThat(HomeDir.expand("/abs")).isEqualTo("/abs");

        assertThat(HomeDir.shorten(home)).isEqualTo("~");
        assertThat(HomeDir.shorten(home + "/.claude/KB")).isEqualTo("~/.claude/KB");
        assertThat(HomeDir.shorten(home + "by/project")).as("a sibling of home that starts with its name").isEqualTo(home + "by/project");
        assertThat(HomeDir.shorten("/etc")).isEqualTo("/etc");
    }
}
