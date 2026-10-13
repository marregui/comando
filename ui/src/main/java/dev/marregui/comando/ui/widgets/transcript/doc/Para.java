package dev.marregui.comando.ui.widgets.transcript.doc;

import java.util.List;
import java.util.Objects;

/**
 * One block of a Markdown document as the transcript renders it. Records compare by value, so a renderer
 * keeps the layout of every paragraph that did not change while text streams into the last one.
 */
public sealed interface Para {

    enum Kind { PARAGRAPH, HEADING, ITEM, QUOTE }

    /**
     * Flowing text.
     *
     * @param level  heading level 1 to 6, 0 otherwise
     * @param depth  list or quote nesting, 0 at the top
     * @param marker the list marker ("•", "3."), empty otherwise; a continuation paragraph of an item has none
     */
    record Text(Kind kind, int level, int depth, String marker, List<Span> spans) implements Para {
        public Text {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(marker, "marker");
            spans = List.copyOf(spans);
        }

        public String plain() {
            StringBuilder sb = new StringBuilder();
            for (Span s : spans) {
                sb.append(s.text());
            }
            return sb.toString();
        }
    }

    /** A fenced or indented code block. */
    record Code(String language, String text, int depth) implements Para {
        public Code {
            Objects.requireNonNull(language, "language");
            Objects.requireNonNull(text, "text");
        }
    }

    /** A table; the first row is the header. */
    record Table(List<List<List<Span>>> rows, int depth) implements Para {
        public Table {
            rows = rows.stream().map(r -> r.stream().map(List::copyOf).toList()).toList();
        }
    }

    record Rule(int depth) implements Para {
    }
}
