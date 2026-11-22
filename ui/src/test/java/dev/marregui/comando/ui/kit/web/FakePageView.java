package dev.marregui.comando.ui.kit.web;

import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JPanel;

/// A [PageView] for tests: a plain panel as its component, the calls it took recorded, a given picture and text
/// answered at once.
public final class FakePageView implements PageView {

    private final JPanel component = new JPanel();
    private final List<Path> loaded = new CopyOnWriteArrayList<>();
    private final List<Boolean> scripts = new CopyOnWriteArrayList<>();
    private final AtomicInteger reloads = new AtomicInteger();
    private final AtomicInteger closes = new AtomicInteger();
    private final AtomicReference<Consumer<URI>> link = new AtomicReference<>(uri -> {
    });
    private final BufferedImage image;
    private final Text text;

    /// A page whose picture is `image` and whose text is `text`.
    public FakePageView(final BufferedImage image, final Text text) {
        this.image = image;
        this.text = text;
    }

    /// A page drawn as a 4 by 3 picture, with no text.
    public FakePageView() {
        this(new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), new Text("", "", ""));
    }

    @Override
    public JComponent component() {
        return component;
    }

    @Override
    public void load(final Path file, final boolean scripts) {
        loaded.add(file);
        this.scripts.add(scripts);
    }

    @Override
    public void reload() {
        reloads.incrementAndGet();
    }

    @Override
    public void onLink(final Consumer<URI> l) {
        link.set(l);
    }

    @Override
    public CompletableFuture<BufferedImage> snapshot() {
        return CompletableFuture.completedFuture(image);
    }

    @Override
    public CompletableFuture<Text> text() {
        return CompletableFuture.completedFuture(text);
    }

    @Override
    public void close() {
        closes.incrementAndGet();
    }

    /// The files loaded, in order.
    public List<Path> loaded() {
        return List.copyOf(loaded);
    }

    /// Whether each load let the page's scripts run, in the order of [#loaded()].
    public List<Boolean> scripts() {
        return List.copyOf(scripts);
    }

    /// How many times the page was shown again from disk.
    public int reloads() {
        return reloads.get();
    }

    /// How many times the page was closed.
    public int closes() {
        return closes.get();
    }

    /// The user follows `uri` on the page; call on the EDT, as the page does.
    public void follow(final URI uri) {
        link.get().accept(uri);
    }
}
