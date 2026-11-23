package dev.marregui.comando.claude;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import dev.marregui.comando.core.files.OwnerOnly;
import dev.marregui.comando.core.model.VersionedLines;
import dev.marregui.comando.core.transcript.Block;

/**
 * The prompts comando delivered to one session from another session or instance, so that its stored conversation,
 * whose lines keep no such mark, names a sender on those alone: a prompt the user typed or pasted with the same header
 * line stays the user's after a reload. One file a session in the data root's {@code delivered} folder, a line file of
 * {@link VersionedLines} (record 133): {@code # comando delivered, version 1}, then {@code began<TAB><millis>}, when
 * the file was made, then, oldest first, a line {@code <sentMillis><TAB><sha256>} per prompt: when comando sent it,
 * epoch milliseconds, and the SHA-256, in hex, of its text as Claude Code stores it (the text sent, stripped). It
 * outlives a restart of comando and a resume of the conversation, whose stored lines keep their text and time. A
 * stored prompt from before the file began, all of a session's from before comando kept it, reads its header as it
 * did; from then on the record decides. A file of a later version is kept as it is, read as none and not written.
 * Confined to the session's strand.
 */
final class DeliveredPrompts {

    static final String FORMAT = "delivered";
    static final int VERSION = 1;
    /**
     * The most deliveries a file keeps; the oldest go first. The stored conversations of this machine held at most 6
     * delivered prompts each (2026-11-23, every conversation under Claude Code's projects folder), and a session runs a
     * conversation per checkpoint: 1,000 is past any session's history by two orders, about 80 kB. A prompt that fell
     * out reads as typed after a reload.
     */
    static final int CAP = 1_000;
    /**
     * How long before comando's send a stored line may be written: both clocks are this machine's, so only a step of
     * the clock moves a line before its send; five seconds covers the steps NTP makes.
     */
    static final long EARLY_MILLIS = 5_000;
    /**
     * How long after comando's send a stored line may be written. Claude Code writes the line when the prompt leaves
     * its queue: 20 ms after the send when the session is idle, 2 s at its process's start, at the end of the running
     * turn when one runs (a stored conversation of 2026-11-23); a day is past any turn, and a later line with the same
     * text is the user's.
     */
    static final long LATE_MILLIS = 24L * 60 * 60 * 1_000;
    /** The record of a session that has none and keeps none: every stored header is read, as before records. */
    static final DeliveredPrompts NONE = new DeliveredPrompts(null, List.of(), Long.MIN_VALUE, false, "");
    private static final System.Logger LOG = System.getLogger(DeliveredPrompts.class.getName());
    /** How a stored prompt from before the record may name a sender: the header's opening. */
    private static final String HEADER = "[from ";
    /** The line that says when the file began, ahead of the deliveries. */
    private static final String BEGAN = "began\t";

    /** Null when nothing is written: no file could be read, or it is of a later version. */
    private final Path file;
    private final List<Entry> entries;
    /** Why the file is kept as it is, for the user; empty when it reads. */
    private final String refused;
    /**
     * When the file began: a stored line before it reads its header. The least long for a file without the line,
     * which then decides every line.
     */
    private long beganMillis;
    /** The file exists: the record decides which stored prompts from its start on were delivered. */
    private boolean kept;

    private DeliveredPrompts(final Path file, final List<Entry> entries, final long beganMillis, final boolean kept,
            final String refused) {
        this.file = file;
        this.entries = new ArrayList<>(entries);
        this.beganMillis = beganMillis;
        this.kept = kept;
        this.refused = refused;
    }

    /** The file that keeps {@code session}'s record in {@code dir}: its name encoded, so any session name is a file. */
    static Path fileOf(final Path dir, final String session) {
        // the encoder leaves '.' and '*': a name ".." must not name the folder above
        return dir.resolve(URLEncoder.encode(session, StandardCharsets.UTF_8).replace(".", "%2E").replace("*", "%2A"));
    }

    /** {@code file}'s record; none kept yet when it is missing, and one read as none when it cannot be read. */
    static DeliveredPrompts read(final Path file) {
        final VersionedLines.Read read;
        try {
            read = VersionedLines.read(file, VERSION);
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "cannot read the delivered prompts, stored headers read as before [file=" + file
                            + ", reason=" + e.getMessage() + ']');
            return new DeliveredPrompts(null, List.of(), Long.MIN_VALUE, false, "");
        }
        if (!read.refused().isEmpty()) {
            LOG.log(System.Logger.Level.WARNING,
                    "delivered prompts kept as they are, stored headers read as before [file=" + file
                            + ", reason=" + read.refused() + ']');
            return new DeliveredPrompts(null, List.of(), Long.MIN_VALUE, false, read.refused());
        }
        final List<Entry> entries = new ArrayList<>();
        long beganMillis = Long.MIN_VALUE;
        for (final String line : read.lines()) {
            try {
                if (line.startsWith(BEGAN)) {
                    beganMillis = Long.parseLong(line.substring(BEGAN.length()));
                } else {
                    final int tab = line.indexOf('\t');
                    final long sentMillis = Long.parseLong(line.substring(0, Math.max(tab, 0)));
                    entries.add(new Entry(sentMillis, line.substring(tab + 1)));
                }
            } catch (final NumberFormatException _) {
                LOG.log(System.Logger.Level.DEBUG,
                        "delivered prompt line skipped [file=" + file + ", line=" + line + ']');
            }
        }
        return new DeliveredPrompts(file, entries, beganMillis, Files.isRegularFile(file), "");
    }

    /** Records that comando sent {@code text} to Claude Code at {@code sentMillis}, as delivered. */
    void add(final long sentMillis, final String text) {
        if (file == null) {
            return;
        }
        entries.add(new Entry(sentMillis, digest(text.strip())));
        final boolean rewrite = !kept || entries.size() > CAP;
        while (entries.size() > CAP) {
            entries.removeFirst();
        }
        try {
            OwnerOnly.folder(file.getParent());
            if (rewrite) {
                final long began = kept ? beganMillis : sentMillis;
                final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, VersionedLines.text(FORMAT, VERSION, lines(began)));
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                beganMillis = began;
            } else {
                Files.writeString(file, line(entries.getLast()) + '\n', StandardOpenOption.APPEND);
            }
            kept = true;
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "cannot record a delivered prompt, after a reload it reads as typed [file=" + file
                            + ", reason=" + e.getMessage() + ']');
        }
    }

    /** Why the file is kept as it is and nothing is saved to it, a later comando having written it; else empty. */
    String refused() {
        return refused;
    }

    /** Reads one stored conversation's prompts against this record, a delivery matching one stored prompt at most. */
    Matcher matcher() {
        return new Matcher();
    }

    private static String line(final Entry e) {
        return e.sentMillis() + "\t" + e.digest();
    }

    private static String digest(final String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("no SHA-256 in this JDK", e);
        }
    }

    private List<String> lines(final long began) {
        final List<String> out = new ArrayList<>(entries.size() + 1);
        if (began != Long.MIN_VALUE) {
            out.add(BEGAN + began);
        }
        for (final Entry e : entries) {
            out.add(line(e));
        }
        return out;
    }

    /** One delivery: when it was sent and its text's digest. */
    private record Entry(long sentMillis, String digest) {
    }

    /** The stored prompts of one read of a conversation, in order, against the record. */
    final class Matcher {
        private final boolean[] used = new boolean[entries.size()];

        private Matcher() {
        }

        /**
         * Whether the stored prompt {@code text} (stripped), written at {@code atMillis}, is one comando delivered: the
         * oldest delivery not yet matched with its text, sent from {@link #LATE_MILLIS} before the line to
         * {@link #EARLY_MILLIS} after it, or any time for a line with no time. With no record, or for a line from
         * before the record began, a prompt headed as a delivered one is, its header the only mark it has.
         */
        boolean delivered(final String text, final long atMillis) {
            if (!kept || atMillis != Block.UNKNOWN_TIME && atMillis < beganMillis) {
                return text.startsWith(HEADER);
            }
            final String d = digest(text);
            for (int i = 0; i < used.length; i++) {
                final Entry e = entries.get(i);
                final long lateMillis = atMillis - e.sentMillis();
                if (!used[i] && e.digest().equals(d) && (atMillis == Block.UNKNOWN_TIME
                        || lateMillis >= -EARLY_MILLIS && lateMillis <= LATE_MILLIS)) {
                    used[i] = true;
                    return true;
                }
            }
            return false;
        }
    }
}
