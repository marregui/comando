package dev.marregui.comando.ui.kit.format;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [Versions#compare] orders Claude Code's and Node's version texts by their numbers, the same rule for both.
class VersionsTest {

    /// The same text is equal; a missing part is 0, so a longer version is newer only by a part above 0.
    @Test
    void equalTextsAreEqualAndAMissingPartIsZero() {
        assertThat(Versions.compare("2.1.316", "2.1.316")).isZero();
        assertThat(Versions.compare("2.1", "2.1.0")).isZero();
        assertThat(Versions.compare("2.1.0.1", "2.1")).as("longer, with a part above 0").isPositive();
        assertThat(Versions.compare("2.1", "2.1.0.1")).isNegative();
    }

    /// A leading `v`, as Node's folders are named, is ignored; parts compare by number, not text.
    @Test
    void aLeadingVIsIgnoredAndPartsCompareByNumber() {
        assertThat(Versions.compare("v22.20.0", "22.20.0")).isZero();
        assertThat(Versions.compare("22.20.0", "20.9.1")).isPositive();
        assertThat(Versions.compare("v22.20.0", "v20.9.1")).isPositive();
        assertThat(Versions.compare("v9.0.0", "v10.0.0")).as("by number, not text").isNegative();
    }

    /// An empty text, a session that has not reported its version, is older than any version and equal to itself.
    @Test
    void anEmptyTextIsOldest() {
        assertThat(Versions.compare("", "0.0.1")).isNegative();
        assertThat(Versions.compare("0.0.1", "")).isPositive();
        assertThat(Versions.compare("", "")).isZero();
    }

    /// A part is read by its leading digits: "316-rc1" is 316, never 3161, and a part without digits is 0.
    @Test
    void aPartIsReadByItsLeadingDigits() {
        assertThat(Versions.compare("2.1.316-rc1", "2.1.316")).isZero();
        assertThat(Versions.compare("2.1.316-rc1", "2.1.317")).isNegative();
        assertThat(Versions.compare("2.1.x", "2.1.0")).isZero();
    }
}
