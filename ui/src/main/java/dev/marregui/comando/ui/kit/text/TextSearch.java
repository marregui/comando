package dev.marregui.comando.ui.kit.text;

import org.fife.ui.rtextarea.RTextArea;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;
import org.fife.ui.rtextarea.SearchResult;

/** Find in a text area with every match marked: the one search behaviour of text-based widgets. */
public final class TextSearch {

    private final RTextArea area;
    private final SearchContext context = new SearchContext();

    public TextSearch(RTextArea area) {
        this.area = area;
        context.setMarkAll(true);
        context.setMatchCase(false);
        context.setWholeWord(false);
        context.setRegularExpression(false);
    }

    public int find(String text, boolean forward) {
        context.setSearchFor(text);
        context.setSearchForward(forward);
        SearchResult r = SearchEngine.find(area, context);
        if (!r.wasFound()) {
            // wrap around
            area.setCaretPosition(forward ? 0 : area.getDocument().getLength());
            r = SearchEngine.find(area, context);
        }
        return r.getMarkedCount();
    }

    public void clear() {
        context.setSearchFor("");
        SearchEngine.markAll(area, context);
    }
}
