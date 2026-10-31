package dev.marregui.comando.core.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Linear ticket ids in a text, each with the address Linear shows it at. Which ids are tickets, and the workspace whose
 * address they take, are learnt from Linear's own links ({@code linear.app/<workspace>/issue/<TEAM>-<n>}), so that a
 * word shaped like an id ({@code UTF-8}, {@code ISO-8859}) is never taken for one.
 */
public final class LinearTickets {

    /** A ticket and where Linear shows it. */
    public record Ticket(String id, String url) {
    }

    /** The workspace in Linear's addresses and the team keys seen in them; empty until a link is seen. */
    public record Site(String workspace, Set<String> teams) {
        public static final Site NONE = new Site("", Set.of());

        public Site {
            teams = Set.copyOf(new TreeSet<>(teams));
        }

        public boolean known() {
            return !workspace.isEmpty() && !teams.isEmpty();
        }
    }

    private static final Pattern LINK = Pattern.compile("linear\\.app/([a-z0-9][a-z0-9-]*)/issue/([A-Z][A-Z0-9]{1,9})-\\d+");
    private static final Pattern ID = Pattern.compile("(?<![A-Za-z0-9/-])([A-Z][A-Z0-9]{1,9})-(\\d{1,6})(?![A-Za-z0-9])");
    private static final Pattern BLANK_LINE = Pattern.compile("\\n[ \\t]*\\n");

    private LinearTickets() {
    }

    /**
     * {@code known} with the workspace and team keys of the Linear links in {@code text}; the last link's workspace
     * wins.
     */
    public static Site learn(final Site known, final String text) {
        if (text.indexOf("linear.app/") < 0) {
            return known;
        }
        String workspace = known.workspace();
        final Set<String> teams = new TreeSet<>(known.teams());
        final Matcher m = LINK.matcher(text);
        while (m.find()) {
            workspace = m.group(1);
            teams.add(m.group(2));
        }
        final Site learnt = new Site(workspace, teams);
        return learnt.equals(known) ? known : learnt;
    }

    /**
     * The tickets {@code text} names, at most {@code max}: those of its last paragraph first, in the order written,
     * then the others from the newest back; each once. None while {@code site} is not {@link Site#known()}.
     */
    public static List<Ticket> in(final String text, final Site site, final int max) {
        if (!site.known()) {
            return List.of();
        }
        int cut = 0;
        final Matcher blank = BLANK_LINE.matcher(text);
        while (blank.find()) {
            cut = blank.end();
        }
        final List<String> last = new ArrayList<>();
        final List<String> earlier = new ArrayList<>();
        final Matcher m = ID.matcher(text);
        while (m.find()) {
            if (site.teams().contains(m.group(1))) {
                (m.start() >= cut ? last : earlier).add(m.group());
            }
        }
        final Set<String> ids = new LinkedHashSet<>(last);
        for (int i = earlier.size() - 1; i >= 0; i--) {
            ids.add(earlier.get(i));
        }
        return ids.stream().limit(max).map(id -> new Ticket(id, "https://linear.app/" + site.workspace() + "/issue/" + id)).toList();
    }
}
