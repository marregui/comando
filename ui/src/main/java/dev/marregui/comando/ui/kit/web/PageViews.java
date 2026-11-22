package dev.marregui.comando.ui.kit.web;

/** Makes the views that render HTML pages. */
public final class PageViews {

    private PageViews() {
    }

    /**
     * A new view, showing nothing until a page is loaded. EDT.
     *
     * @throws UnsupportedOperationException when pages cannot be rendered here: no display, or JavaFX does not start
     */
    public static PageView create() {
        return WebPageView.create();
    }
}
