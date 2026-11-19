package dev.marregui.comando.cdb.store;

/// The store's test switches for the tests of other packages, which cannot reach its package-private fields.
public final class StoreHooks {

    private StoreHooks() {
    }

    /// Holds the applier thread back, or lets it go: commits queue and stay queued, so a read whose lag allows it
    /// misses them, while a fresh reader applies them on its own thread.
    public static void pauseApplier(final Store store, final boolean paused) {
        store.applierPaused = paused;
    }
}
