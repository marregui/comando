package dev.marregui.comando.cdb.catalog;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Field;
import dev.marregui.comando.cdb.exec.Page;

import static org.assertj.core.api.Assertions.assertThat;

/// How listings of any length are cut into pages: full pages, a remainder, none when empty.
class PageBuilderTest {

    private static final List<Field> FIELDS = List.of(new Field("n", CdbType.LONG), new Field("s", CdbType.VARCHAR));

    private static List<Page> build(final int rows) {
        final PageBuilder builder = new PageBuilder(FIELDS);
        for (int i = 0; i < rows; i++) {
            builder.integer(0, i);
            builder.text(1, "r" + i);
            builder.endRow();
        }
        return builder.finish();
    }

    /// No rows give no page, since a cursor never returns an empty page.
    @Test
    void noRowsGiveNoPage() {
        assertThat(build(0)).isEmpty();
    }

    /// 1024 rows are one full page and no empty page after it.
    @Test
    void exactlyOnePageOfRowsGivesOnePage() {
        assertThat(build(Page.CAPACITY)).extracting(p -> p.size).containsExactly(1024);
    }

    /// 2500 rows are two full pages and a remainder, the values kept in order across them.
    @Test
    void rowsBeyondOnePageSpillIntoTheNext() {
        final List<Page> pages = build(2500);
        assertThat(pages).extracting(p -> p.size).containsExactly(1024, 1024, 452);
        assertThat(pages.get(1).columns[0].longs[0]).isEqualTo(1024);
        assertThat(pages.get(2).columns[1].texts[451]).isEqualTo("r2499");
    }
}
