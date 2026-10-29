package dev.marregui.comando.core.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The kit's version as git gives it, and the newer releases its remote has: a release is a tag
 * {@code vMAJOR.MINOR.PATCH}.
 *
 * @param described what {@code git describe --tags --always} says of the root: a tag, a tag and the commits past it
 *                  ({@code v1.2.0-3-gabc1234}), or a commit; empty when the root is no git checkout
 * @param newer     the releases newer than the described one, newest first; every release when the root is past none
 * @param problem   why the remote's releases are unknown; empty when they were read
 */
public record KitVersion(String described, List<String> newer, String problem) {

    private static final Pattern RELEASE = Pattern.compile("v(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final Pattern DESCRIBED = Pattern.compile("(v\\d+\\.\\d+\\.\\d+)(?:-\\d+-g[0-9a-f]+)?");
    /** Newest first: by major, minor, then patch. */
    private static final Comparator<String> NEWEST_FIRST = Comparator.comparing((String t) -> parts(t)[0])
            .thenComparing(t -> parts(t)[1]).thenComparing(t -> parts(t)[2]).reversed();

    /** No version known: the root is no git checkout, or git could not be run. */
    public static final KitVersion UNKNOWN = new KitVersion("", List.of(), "");

    public KitVersion {
        described = described == null ? "" : described.strip();
        newer = List.copyOf(newer);
        problem = problem == null ? "" : problem;
    }

    /** The version read: {@code describe}'s output and {@code ls-remote --tags}'s, which may be empty. */
    public static KitVersion of(String describe, String lsRemote) {
        String d = describe == null ? "" : describe.strip();
        return new KitVersion(d, newerThan(release(d), lsRemote), "");
    }

    /** The release {@code described} is at or past, when it names one. */
    public static Optional<String> release(String described) {
        Matcher m = DESCRIBED.matcher(described == null ? "" : described.strip());
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /**
     * The releases {@code git ls-remote --tags} lists that are newer than {@code current}, newest first, each once: a
     * line {@code <sha>\trefs/tags/v1.3.0}, or its peeled {@code ^{}} twin; any other tag is not a release.
     */
    public static List<String> newerThan(Optional<String> current, String lsRemote) {
        List<String> out = new ArrayList<>();
        for (String line : (lsRemote == null ? "" : lsRemote).lines().toList()) {
            int at = line.indexOf("refs/tags/");
            if (at < 0) {
                continue;
            }
            String tag = line.substring(at + "refs/tags/".length()).strip();
            if (tag.endsWith("^{}")) {
                tag = tag.substring(0, tag.length() - 3);
            }
            if (RELEASE.matcher(tag).matches() && !out.contains(tag) && (current.isEmpty() || NEWEST_FIRST.compare(tag, current.get()) < 0)) {
                out.add(tag);
            }
        }
        out.sort(NEWEST_FIRST);
        return List.copyOf(out);
    }

    private static int[] parts(String release) {
        Matcher m = RELEASE.matcher(release);
        if (!m.matches()) {
            return new int[]{-1, -1, -1};
        }
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
    }

    /** The same version, with why the remote's releases are unknown. */
    public KitVersion failedRemote(String why) {
        return new KitVersion(described, List.of(), why);
    }

    /** The newest release past this one, when the remote has one. */
    public Optional<String> newest() {
        return newer.isEmpty() ? Optional.empty() : Optional.of(newer.getFirst());
    }

    /** The version as the status bar shows it: the release, else what git described. */
    public String shown() {
        return release(described).orElse(described);
    }

    /** "kit marregui v1.2.0", or "kit marregui v1.2.0 → v1.3.0 available" when a newer release is out. */
    public String label(String name) {
        String kit = "kit " + (name.isEmpty() ? "" : name + ' ') + shown();
        return newest().map(n -> kit.strip() + " → " + n + " available").orElse(kit.strip());
    }
}
