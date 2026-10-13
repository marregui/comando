package dev.marregui.comando.core.state;

import java.util.List;
import java.util.Objects;

/** What Claude Code reported about a running session in its {@code system/init} event. */
public record SessionInfo(String claudeSessionId, String model, String cwd, String cliVersion, String permissionMode, List<String> capabilities) {

    public static final SessionInfo NONE = new SessionInfo("", "", "", "", "", List.of());

    public SessionInfo {
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
