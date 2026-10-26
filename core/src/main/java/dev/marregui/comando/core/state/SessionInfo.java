package dev.marregui.comando.core.state;

import java.util.List;
import java.util.Objects;

/**
 * What Claude Code reported about a running session in its {@code system/init} event, and the base effort it runs at.
 *
 * @param effort the effort level the session starts each turn at, as Claude Code's settings give it for the model the
 *               event names, empty when they give none; the stream does not report it, and a turn may raise it
 */
public record SessionInfo(String claudeSessionId, String model, String cwd, String cliVersion, String permissionMode, List<String> capabilities,
                          String effort) {

    public static final SessionInfo NONE = new SessionInfo("", "", "", "", "", List.of(), "");

    /** What the event reported, with no base effort known. */
    public SessionInfo(String claudeSessionId, String model, String cwd, String cliVersion, String permissionMode, List<String> capabilities) {
        this(claudeSessionId, model, cwd, cliVersion, permissionMode, capabilities, "");
    }

    public SessionInfo {
        effort = effort == null ? "" : effort;
        Objects.requireNonNull(claudeSessionId, "claudeSessionId");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(cwd, "cwd");
        Objects.requireNonNull(cliVersion, "cliVersion");
        Objects.requireNonNull(permissionMode, "permissionMode");
        capabilities = List.copyOf(capabilities);
    }

    public boolean hasCapability(String capability) {
        return capabilities.contains(capability);
    }
}
