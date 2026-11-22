package dev.marregui.comando.ui.kit.format;

import java.time.ZoneId;

import dev.marregui.comando.core.port.MachinesPort.Grant;
import dev.marregui.comando.core.port.MachinesPort.Paired;

/** How a paired machine's connection and grant read, the same in the Machines window and the Instances tab. */
public final class MachineTexts {

    private MachineTexts() {
    }

    /** A paired machine's connection: connected, or not and when it last was. */
    public static String connection(final Paired p, final long nowMillis, final ZoneId zone) {
        if (p.connected()) {
            return "connected";
        }
        return p.lastConnectedMillis() > 0
                ? "not connected; last connected " + Labels.startedAt(p.lastConnectedMillis(), nowMillis, zone)
                : "not connected; never connected";
    }

    /** What {@code g} lets the machine do here, after its name. */
    public static String grant(final Grant g) {
        return g.text() + switch (g) {
            case OVERSEER -> ": its instance main asks as this machine's overseer";
            case PEER -> ": a question from it runs in a session in plan mode";
        };
    }
}
