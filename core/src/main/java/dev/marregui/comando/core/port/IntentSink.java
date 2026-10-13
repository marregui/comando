package dev.marregui.comando.core.port;

import dev.marregui.comando.core.intent.Intent;

/** Where adapters and the UI send intents; implemented by the state loop. Never blocks. */
@FunctionalInterface
public interface IntentSink {

    void dispatch(Intent intent);
}
