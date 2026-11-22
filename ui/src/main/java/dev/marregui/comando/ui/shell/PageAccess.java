package dev.marregui.comando.ui.shell;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.imageio.ImageIO;

import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.port.PagePort;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.web.PageView;
import dev.marregui.comando.ui.panels.EditorPanel;

/**
 * The editor panels' HTML pages as agents reach them. Which panel shows which page is read on the EDT, bounded by
 * {@link #EDT_BOUND}; the picture and the text are waited for on the calling thread, bounded by {@link #PAGE_BOUND}.
 * Until the window is built, no page is listed and every action is refused.
 */
public final class PageAccess implements PagePort {

    /** How long a call waits for the EDT: as the terminals' port waits, the port answers whatever the EDT does. */
    private static final Duration EDT_BOUND = Duration.ofSeconds(2);
    /**
     * How long a call waits for the page to draw or read itself: the port answers "within a few seconds", and a page
     * that takes longer than 5 s to draw what it already shows has stalled.
     */
    private static final Duration PAGE_BOUND = Duration.ofSeconds(5);
    /**
     * How many pictures the folder keeps: an agent looks at a picture right after asking for it, so the last few are
     * enough, and the folder stays a few MB however long comando runs (2026-11-22).
     */
    static final int KEPT_PICTURES = 20;

    private final Supplier<Function<PanelId, EditorPanel>> panels;
    private final Supplier<AppState> state;
    /** The folder this process's pictures go to, made at the first snapshot; MCP request threads, under its lock. */
    private Path pictures;

    /**
     * @param panels the editor panel of a panel id (null when it has none), null before the window is built; called on
     *               the EDT only
     * @param state  the latest state; called on the EDT only
     */
    public PageAccess(final Supplier<Function<PanelId, EditorPanel>> panels, final Supplier<AppState> state) {
        this.panels = panels;
        this.state = state;
    }

    @Override
    public List<Page> list() {
        return onEdt(this::pages).orElseThrow(PageAccess::noAnswer);
    }

    /** Every editor panel whose file is an HTML page, in the panels' order; on the EDT. */
    private List<Page> pages(final Function<PanelId, EditorPanel> views) {
        if (views == null) {
            return List.of();
        }
        final List<PanelState> all = state.get().panels();
        final List<Page> out = new ArrayList<>(all.size());
        for (final PanelState p : all) {
            final EditorPanel v = views.apply(p.id());
            if (v != null && v.isHtml()) {
                out.add(new Page(p.id(), p.title(), v.file(), v.shownPage().isPresent()));
            }
        }
        return out;
    }

    @Override
    public String snapshot(final PanelId panel) {
        final Optional<Reach> reached = onEdt(v -> v == null ? new Refused(notReady(panel)) : reach(v, panel));
        if (reached.isEmpty()) {
            return noAnswerFor(panel);
        }
        return switch (reached.get()) {
            case Refused(final String refusal) -> refusal;
            case Shown(final String title, final Path _, final PageView page) -> draw(panel, title, page);
        };
    }

    /** The page's picture, written to a new PNG file, and its absolute path; or the refusal. */
    private String draw(final PanelId panel, final String title, final PageView page) {
        final BufferedImage image;
        try {
            image = waitFor(page.snapshot());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return "refused: interrupted before the page was drawn [panel=" + panel.value() + ']';
        } catch (final TimeoutException e) {
            return "refused: the page did not draw in time [panel=" + panel.value() + ", title=" + title
                    + ", boundMillis=" + PAGE_BOUND.toMillis() + ']';
        } catch (final ExecutionException | CancellationException e) {
            return "refused: cannot draw the page [panel=" + panel.value() + ", title=" + title + ", reason="
                    + reason(e) + ']';
        }
        try {
            final Path out = newPicture();
            if (!ImageIO.write(image, "png", out.toFile())) {
                Files.deleteIfExists(out);
                return "refused: no PNG writer for the page's picture [panel=" + panel.value() + ']';
            }
            return out.toAbsolutePath().toString();
        } catch (final IOException e) {
            return "refused: cannot write the page's picture [panel=" + panel.value() + ", reason=" + e.getMessage()
                    + ']';
        }
    }

    @Override
    public Optional<Reading> read(final PanelId panel, final int maxChars) {
        final Optional<Reach> reached = onEdt(v -> v == null ? new Refused(notReady(panel)) : reach(v, panel));
        if (reached.isEmpty()) {
            throw noAnswer();
        }
        if (!(reached.get() instanceof Shown(final String _, final Path file, final PageView page))) {
            return Optional.empty();
        }
        final PageView.Text text;
        try {
            text = waitFor(page.text());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted before the page was read [panel=" + panel.value() + ']', e);
        } catch (final TimeoutException e) {
            throw new IllegalStateException("cannot read the page, it did not answer in time [panel=" + panel.value()
                    + ", boundMillis=" + PAGE_BOUND.toMillis() + ']', e);
        } catch (final ExecutionException | CancellationException e) {
            throw new IllegalStateException("cannot read the page [panel=" + panel.value() + ']', e);
        }
        final String body = text.body();
        final int cut = cut(body, maxChars);
        final boolean more = cut < body.length();
        return Optional.of(new Reading(text.title(), file, text.selection(), body.substring(0, cut), more));
    }

    /** Where {@code body} is cut to hold at most {@code maxChars} characters, never inside a surrogate pair. */
    private static int cut(final String body, final int maxChars) {
        if (body.length() <= maxChars) {
            return body.length();
        }
        final int at = Math.max(0, maxChars);
        return at > 0 && Character.isHighSurrogate(body.charAt(at - 1)) ? at - 1 : at;
    }

    /** The panel's page while it shows rendered, or the refusal; on the EDT. */
    private Reach reach(final Function<PanelId, EditorPanel> views, final PanelId panel) {
        final Optional<PanelState> p = state.get().panel(panel);
        final EditorPanel v = p.isEmpty() ? null : views.apply(panel);
        if (v == null) {
            return new Refused("refused: no such panel [panel=" + panel.value() + ']');
        }
        final String title = p.get().title();
        if (!v.isHtml()) {
            return new Refused("refused: the panel holds no HTML page [panel=" + panel.value() + ", title=" + title
                    + ']');
        }
        return v.shownPage().<Reach>map(page -> new Shown(title, v.file(), page))
                .orElseGet(() -> new Refused("refused: the panel shows the page's text, not the page [panel="
                        + panel.value() + ", title=" + title + ']'));
    }

    /** {@code future}'s value, waited for at most {@link #PAGE_BOUND}. */
    private static <T> T waitFor(final CompletableFuture<T> future)
            throws InterruptedException, TimeoutException, ExecutionException {
        return future.get(PAGE_BOUND.toNanos(), TimeUnit.NANOSECONDS);
    }

    private static String reason(final Exception e) {
        final Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        return String.valueOf(cause.getMessage());
    }

    /**
     * {@code task} run on the EDT over the panels (null before the window is built); empty when the EDT did not run it
     * within {@link #EDT_BOUND}. The task never answers null.
     */
    private <T> Optional<T> onEdt(final Function<Function<PanelId, EditorPanel>, T> task) {
        final AtomicReference<T> out = new AtomicReference<>();
        if (!Edt.runAndWait(() -> out.set(task.apply(panels.get())), EDT_BOUND)) {
            return Optional.empty();
        }
        return Optional.of(out.get());
    }

    /**
     * A new file in this process's picture folder, the oldest pictures beyond {@link #KEPT_PICTURES} deleted; the
     * folder and what it holds are deleted when the process exits.
     */
    private synchronized Path newPicture() throws IOException {
        if (pictures == null) {
            pictures = Files.createTempDirectory("comando-pages-");
            pictures.toFile().deleteOnExit();
        }
        final List<Path> held = new ArrayList<>();
        try (DirectoryStream<Path> in = Files.newDirectoryStream(pictures, "*.png")) {
            in.forEach(held::add);
        }
        held.sort(Comparator.comparing(Path::getFileName));
        for (int i = 0; i <= held.size() - KEPT_PICTURES; i++) {
            Files.deleteIfExists(held.get(i));
        }
        // the time first in the name, zero-padded, so the names sort oldest first
        final String name = String.format("page-%015d-", System.currentTimeMillis());
        final Path out = Files.createTempFile(pictures, name, ".png");
        out.toFile().deleteOnExit();
        return out;
    }

    private static IllegalStateException noAnswer() {
        return new IllegalStateException("cannot reach the pages, the window did not answer [boundMillis="
                + EDT_BOUND.toMillis() + ']');
    }

    private static String notReady(final PanelId panel) {
        return "refused: pages are not ready, the window is not built [panel=" + panel.value() + ']';
    }

    private static String noAnswerFor(final PanelId panel) {
        return "refused: the window did not answer in time [panel=" + panel.value() + ", boundMillis="
                + EDT_BOUND.toMillis() + ']';
    }

    /** A panel's rendered page, or why an action cannot reach it. */
    private sealed interface Reach permits Refused, Shown {
    }

    private record Refused(String answer) implements Reach {
    }

    private record Shown(String title, Path file, PageView page) implements Reach {
    }
}
