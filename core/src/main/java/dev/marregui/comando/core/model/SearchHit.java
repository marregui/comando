package dev.marregui.comando.core.model;

import java.util.Objects;

/**
 * One place a search found its text.
 *
 * @param kind        what was found
 * @param source      the file's absolute path; for a conversation, the path of its transcript
 * @param conversation the conversation's id, empty for a file
 * @param group       the name the hit is listed under: the folder's, or the conversation's session or title
 * @param line        the line in the file, from 1; 0 for a name or a conversation
 * @param snippet     the text around the match, on one line
 * @param matchStart  where the match starts in {@code snippet}, in chars
 * @param matchLength how long the match is, in chars
 * @param modifiedMillis when the source last changed
 * @param context     the message around the match, for a conversation, whose file is no text to show; else empty
 * @param contextStart where the match starts in {@code context}, in chars
 * @param root        the folder of the folder tabs (Kit, Brain, Scratchpad) searched, as walked, that holds
 *                    {@code source}; empty for a conversation
 * @param heading     where in its source the hit is: the path of Markdown headings above a file's line, joined by
 *                    {@link #HEADING_SEPARATOR}, or who wrote a conversation's message and when; empty for none
 */
public record SearchHit(
        Kind kind,
        String source,
        String conversation,
        String group,
        int line,
        String snippet,
        int matchStart,
        int matchLength,
        long modifiedMillis,
        String context,
        int contextStart,
        String root,
        String heading
) {

    /** What joins the headings of {@link #heading}, outermost first. */
    public static final String HEADING_SEPARATOR = " › ";

    /** What a hit is. */
    public enum Kind {
        /** A file whose name holds the text. */
        NAME,
        /** A line of a file. */
        FILE,
        /** A message of a stored conversation. */
        CONVERSATION
    }

    public SearchHit {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(snippet, "snippet");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(heading, "heading");
    }

    /** A hit in a file, whose preview reads the file. */
    public SearchHit(
            final Kind kind,
            final String source,
            final String conversation,
            final String group,
            final int line,
            final String snippet,
            final int matchStart,
            final int matchLength,
            final long modifiedMillis
    ) {
        this(kind, source, conversation, group, line, snippet, matchStart, matchLength, modifiedMillis, "", 0, "", "");
    }

    /** This hit, found under the folder {@code at} and the headings {@code under}. */
    public SearchHit placed(final String at, final String under) {
        return new SearchHit(kind, source, conversation, group, line, snippet, matchStart, matchLength,
                modifiedMillis, context, contextStart, at, under);
    }

    /** The text that matched, as the source has it. */
    public String match() {
        final int start = Math.min(matchStart, snippet.length());
        return snippet.substring(start, Math.min(start + matchLength, snippet.length()));
    }
}
