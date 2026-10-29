package dev.marregui.comando.mcp;

import dev.marregui.comando.core.view.Clip;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** Append-only JSON lines of every tool call and approval decision, one file per UTC day. */
public final class AuditLog {

    private static final System.Logger LOG = System.getLogger(AuditLog.class.getName());
    private static final int MAX_FIELD = 2_000;

    private final Path dir;
    private final ObjectMapper json = new ObjectMapper();

    public AuditLog(Path dir) {
        this.dir = dir;
    }

    /** A log that writes nowhere, for tests. */
    public static AuditLog none() {
        return new AuditLog(null);
    }

    public synchronized void record(String session, String event, String tool, String detail, String outcome) {
        if (dir == null) {
            return;
        }
        ObjectNode line = json.createObjectNode();
        line.put("time", Instant.now().toString());
        line.put("session", session);
        line.put("event", event);
        line.put("tool", tool);
        line.put("detail", Clip.keepStart(detail, MAX_FIELD));
        line.put("outcome", outcome);
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve("audit-" + LocalDate.now(ZoneOffset.UTC) + ".jsonl");
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot write audit log [dir=" + dir + ']', e);
        }
    }
}
