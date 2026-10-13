package dev.marregui.comando.ui.widget;

import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.ui.kit.action.ActionRegistry;

/** What a widget may use: the intent sink and the action registry. */
public record WidgetContext(IntentSink intents, ActionRegistry actions) {
}
