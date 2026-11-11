package dev.marregui.comando.ui.panels;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;

import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

import dev.marregui.comando.core.logbook.LogbookTask;
import dev.marregui.comando.core.model.LinearTickets;
import dev.marregui.comando.ui.kit.text.TextLinks;

/** A task's page as the Logbook tab shows it: its section of the index, with its tickets and documents as links. */
final class TaskPages {

    /**
     * The section is read as the Markdown view reads it, tables included, each node knowing where it stands in the
     * text, so that links are added only where the view shows text or a code span.
     */
    private static final Parser PARSER = Parser.builder().extensions(List.of(TablesExtension.create()))
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build();

    private TaskPages() {
    }

    /**
     * The task's section as Markdown with links added; nothing else changes and no file is read.
     * <ul>
     *   <li>A Linear ticket id in the section's text becomes a link to its issue on {@code site} when its team is one
     *   of {@code site}'s or the task names it among its tickets; none does while {@code site} has no workspace
     *   ({@link LinearTickets.Site#NONE}). An id in code (a code span, a fenced block of any fence, an indented block),
     *   in a link, in inline HTML or in a link the text shows on its own (a web address, a wiki link) stays as
     *   written.</li>
     *   <li>A code span outside links whose text is one of the task's documents (the paths of its Documents line)
     *   becomes a link to that file, resolved against {@code dayFolder}, as a {@code file:} address; none does while
     *   {@code dayFolder} is null.</li>
     * </ul>
     */
    static String markdown(final LogbookTask task, final Path dayFolder, final LinearTickets.Site site) {
        final boolean tickets = !site.workspace().isEmpty();
        final boolean documents = dayFolder != null && !task.documents().isEmpty();
        if (!tickets && !documents) {
            return task.markdown();
        }
        final Linker linker = new Linker(task, documents ? dayFolder : null, tickets ? site : null);
        PARSER.parse(task.markdown()).accept(linker);
        return linker.page();
    }

    /** The {@code file:} address of {@code path} under {@code dayFolder}; null when it is no path on this system. */
    private static String fileAddress(final Path dayFolder, final String path) {
        try {
            return dayFolder.resolve(path).toUri().toString();
        } catch (final InvalidPathException _) {
            return null;
        }
    }

    /**
     * Writes a task's section again as it walks the section's nodes, with links added in its text and code spans: the
     * source between them is copied as written.
     */
    private static final class Linker extends AbstractVisitor {

        /** Where the documents resolve; null when they are not linked. */
        private final Path dayFolder;
        private final StringBuilder out;
        /** The site the tickets link to; null when they are not linked. */
        private final LinearTickets.Site site;
        private final String source;
        private final LogbookTask task;
        /** How much of {@link #source} is in {@link #out}. */
        private int copied;

        Linker(final LogbookTask task, final Path dayFolder, final LinearTickets.Site site) {
            this.task = task;
            this.dayFolder = dayFolder;
            this.site = site;
            this.source = task.markdown();
            this.out = new StringBuilder(source.length() + 256);
        }

        /** A link's text and destination stay as written. */
        @Override
        public void visit(final Link link) {
        }

        /** An image's description and destination stay as written. */
        @Override
        public void visit(final Image image) {
        }

        @Override
        public void visit(final Code code) {
            final List<SourceSpan> spans = code.getSourceSpans();
            if (dayFolder == null || spans.isEmpty() || !task.documents().contains(code.getLiteral())) {
                return;
            }
            final String file = fileAddress(dayFolder, code.getLiteral());
            if (file == null) {
                return;
            }
            final int start = spans.getFirst().getInputIndex();
            final int end = spans.getLast().getInputIndex() + spans.getLast().getLength();
            copyTo(start);
            out.append('[').append(source, start, end).append("](").append(file).append(')');
            copied = end;
        }

        @Override
        public void visit(final Text text) {
            if (site == null) {
                return;
            }
            for (final SourceSpan span : text.getSourceSpans()) {
                tickets(span.getInputIndex(), span.getInputIndex() + span.getLength());
            }
        }

        /** The section with its links. */
        String page() {
            copyTo(source.length());
            return out.toString();
        }

        private static boolean inside(final List<TextLinks.Ref> refs, final int index) {
            for (final TextLinks.Ref r : refs) {
                if (index >= r.start() && index < r.end()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Each ticket id in the text from {@code start} to {@code end} a link to its issue, except in the links the
         * view finds in text on its own.
         */
        private void tickets(final int start, final int end) {
            final List<TextLinks.Ref> shown = TextLinks.refs(source.substring(start, end), false);
            final Matcher id = LinearTickets.ID.matcher(source).region(start, end).useTransparentBounds(true);
            while (id.find()) {
                if (inside(shown, id.start() - start)
                        || !site.teams().contains(id.group(1)) && !task.tickets().contains(id.group())) {
                    continue;
                }
                copyTo(id.start());
                out.append('[').append(id.group()).append("](").append(LinearTickets.url(site, id.group())).append(')');
                copied = id.end();
            }
        }

        private void copyTo(final int index) {
            out.append(source, copied, index);
            copied = index;
        }
    }
}
