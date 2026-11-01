package dev.marregui.comando.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import dev.marregui.comando.core.files.OwnerOnly;
import dev.marregui.comando.core.view.Clip;

/**
 * Append-only JSON lines of every tool call and approval decision, one file per UTC day. The folder and its files are the
 * user's alone (rule 15): they hold every tool call's arguments. A folder deleted while comando runs is made again so,
 * and each day's file is born so.
 */
public final class AuditLog {

    private static final System.Logger LOG = System.getLogger(AuditLog.class.getName());
    private static final int MAX_FIELD = 2_000;

    private final Path dir;
    private final ObjectMapper json = new ObjectMapper();

    public AuditLog(final Path dir) {
        this.dir = dir;
    }

    /** A log that writes nowhere, for tests. */
    public static AuditLog none() {
        return new AuditLog(null);
    }

    public synchronized void record(final String session, final String event, final String tool, final String detail, final String outcome) {
        if (dir == null) {
            return;
        }
        final ObjectNode line = json.createObjectNode();
        line.put("time", Instant.now().toString());
        line.put("session", session);
        line.put("event", event);
        line.put("tool", tool);
        line.put("detail", Clip.keepStart(detail, MAX_FIELD));
        line.put("outcome", outcome);
        try {
            if (!Files.isDirectory(dir)) {
                OwnerOnly.folder(dir);
            }
            final Path file = dir.resolve("audit-" + LocalDate.now(ZoneOffset.UTC) + ".jsonl");
            if (!Files.exists(file)) {
                try {
                    OwnerOnly.file(file);
                } catch (final FileAlreadyExistsException e) {
                    // made in between by another writer: appended to as it is
                }
            }
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot write audit log [dir=" + dir + ']', e);
        }
    }
}
