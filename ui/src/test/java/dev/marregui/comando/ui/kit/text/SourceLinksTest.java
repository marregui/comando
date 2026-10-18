package dev.marregui.comando.ui.kit.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceLinksTest {

    @Test
    void findsPathsWithLinesInText() {
        String text = "See db/src/main/java/Lane.java:118 and core/Reducer.java:42.";
        assertThat(SourceLinks.refs(text)).extracting(SourceLinks.Ref::href)
                .containsExactly(SourceLinks.SCHEME + "db/src/main/java/Lane.java#118", SourceLinks.SCHEME + "core/Reducer.java#42");
        SourceLinks.Ref first = SourceLinks.refs(text).getFirst();
        assertThat(text.substring(first.start(), first.end())).isEqualTo("db/src/main/java/Lane.java:118");
        assertThat(SourceLinks.path(first.href())).isEqualTo("db/src/main/java/Lane.java");
        assertThat(SourceLinks.line(first.href())).isEqualTo(118);
    }

    @Test
    void leavesTimesAndAddressesAlone() {
        assertThat(SourceLinks.refs("at 10:30 or http://h:8080/x")).isEmpty();
    }
}
