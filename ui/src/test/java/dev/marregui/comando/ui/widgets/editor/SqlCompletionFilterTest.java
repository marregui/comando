package dev.marregui.comando.ui.widgets.editor;

import java.util.List;
import javax.swing.text.BadLocationException;
import javax.swing.text.PlainDocument;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.marregui.comando.ui.kit.async.OnEdt;

import static org.assertj.core.api.Assertions.assertThat;

/// [Completions] finds the words a typed prefix starts, as the editor's completion has always found them.
/// - a prefix finds the words it starts, whatever their case, in case-insensitive order
/// - no prefix finds every word; a prefix nothing starts finds none
/// - a word's exact text, case aside, names it
/// - the prefix is the letters, digits and underscores before the caret on its line
@ExtendWith(OnEdt.class)
class SqlCompletionFilterTest {

    /// `sel`, `SEL` and `Sel` each find `sel_items`, `SELECT` and `Select2`, in that order (`_` sorts before letters),
    /// and not `set` or `trades`; `sel_` finds only `sel_items`.
    @Test
    void aPrefixFindsTheWordsItStartsWhateverTheirCase() {
        final Completions words = words("trades", "SELECT", "set", "Select2", "sel_items");
        for (final String prefix : List.of("sel", "SEL", "Sel")) {
            assertThat(texts(words.matching(prefix))).as(prefix).containsExactly("sel_items", "SELECT", "Select2");
        }
        assertThat(texts(words.matching("sel_"))).containsExactly("sel_items");
        assertThat(texts(words.matching("SELECT2"))).containsExactly("Select2");
    }

    /// An empty prefix finds every word, sorted ignoring case; a prefix that starts no word, or one past the last
    /// word, finds none.
    @Test
    void noPrefixFindsEveryWordAndAnUnknownOneNone() {
        final Completions words = words("b", "A", "c", "B2");
        assertThat(texts(words.matching(""))).containsExactly("A", "b", "B2", "c");
        assertThat(words.size()).isEqualTo(4);
        assertThat(words.matching("x")).isEmpty();
        assertThat(words.matching("ab")).isEmpty();
        assertThat(words("only").matching("zz")).isEmpty();
        assertThat(new Completions().matching("a")).isEmpty();
    }

    /// `named` finds the word whose whole text is the one given, case aside, and not the longer words it starts.
    @Test
    void aWordsExactTextNamesIt() {
        final Completions words = words("ts", "TS_ms", "trades");
        assertThat(texts(words.named("TS"))).containsExactly("ts");
        assertThat(words.named("t")).isEmpty();
    }

    /// The prefix runs back over letters, digits and `_` and stops at anything else, or at the line's start; a caret
    /// after a space, a dot or a parenthesis has none.
    @Test
    void thePrefixIsTheWordCharactersBeforeTheCaretOnItsLine() throws BadLocationException {
        final PlainDocument doc = new PlainDocument();
        doc.insertString(0, "select c2_x, t.price\nfrom tr", null);
        assertThat(Completions.wordBefore(doc, "select c2_x".length())).isEqualTo("c2_x");
        assertThat(Completions.wordBefore(doc, "select c2".length())).isEqualTo("c2");
        assertThat(Completions.wordBefore(doc, "select c2_x, t.price".length())).isEqualTo("price");
        assertThat(Completions.wordBefore(doc, "select c2_x, t.".length())).isEmpty();
        assertThat(Completions.wordBefore(doc, "select ".length())).isEmpty();
        assertThat(Completions.wordBefore(doc, doc.getLength())).isEqualTo("tr");
        assertThat(Completions.wordBefore(doc, "select c2_x, t.price\n".length())).isEmpty();
        assertThat(Completions.wordBefore(doc, 0)).isEmpty();
        doc.insertString(doc.getLength(), " count(", null);
        assertThat(Completions.wordBefore(doc, doc.getLength())).isEmpty();
        assertThat(Completions.isWordChar('_')).isTrue();
        assertThat(Completions.isWordChar('9')).isTrue();
        assertThat(Completions.isWordChar('é')).isTrue();
        assertThat(Completions.isWordChar('-')).isFalse();
    }

    /// A column `count` of type INT and the keyword `COUNT` are listed column first, as the editor always listed
    /// them: equal names ignoring case are ordered by what they are, ignoring case.
    @Test
    void aColumnComesBeforeAKeywordOfTheSameNameIgnoringCase() {
        final Completions words = new Completions();
        words.set(List.of(new Completion("COUNT", "keyword"), new Completion("count", "INT")));
        assertThat(words.matching("cou")).extracting(Completion::description).containsExactly("INT", "keyword");
    }

    private static Completions words(final String... texts) {
        final Completions words = new Completions();
        words.set(List.of(texts).stream().map(t -> new Completion(t, "word")).toList());
        return words;
    }

    private static List<String> texts(final List<Completion> found) {
        return found.stream().map(Completion::text).toList();
    }
}
