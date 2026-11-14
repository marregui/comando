package dev.marregui.comando.app.mqtt;

/**
 * Topic Names and Topic Filters (MQTT 5 §4.7): which strings are valid, and which names a filter matches. Length and
 * encoding are a UTF-8 Encoded String's (§1.5.4), checked where the string is written or read.
 */
public final class Topics {

    private Topics() {
    }

    /** Whether {@code name} is a Topic Name: at least one character, no wildcard, no U+0000 (§4.7.3, §3.3.2.1). */
    static boolean validName(final String name) {
        return !name.isEmpty() && name.indexOf('+') < 0 && name.indexOf('#') < 0 && name.indexOf('\u0000') < 0;
    }

    /**
     * Whether {@code filter} is a Topic Filter: at least one character, no U+0000, {@code +} alone in its level and
     * {@code #} alone in the last one (§4.7.1).
     */
    static boolean validFilter(final String filter) {
        if (filter.isEmpty() || filter.indexOf('\u0000') >= 0) {
            return false;
        }
        final int n = filter.length();
        for (int i = 0; i < n; i++) {
            final char c = filter.charAt(i);
            final boolean levelStart = i == 0 || filter.charAt(i - 1) == '/';
            final boolean levelEnd = i == n - 1 || filter.charAt(i + 1) == '/';
            if (c == '+' && !(levelStart && levelEnd)) {
                return false;
            }
            if (c == '#' && !(levelStart && i == n - 1)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code filter} matches topic name {@code topic} (§4.7): {@code +} matches one level, {@code #} the
     * parent level and every level below it, and a name that starts with {@code $} only a filter that does not start
     * with a wildcard (§4.7.2).
     */
    public static boolean matches(final String filter, final String topic) {
        if (!topic.isEmpty() && topic.charAt(0) == '$' && !filter.isEmpty()
                && (filter.charAt(0) == '+' || filter.charAt(0) == '#')) {
            return false;
        }
        int f = 0;
        int t = 0;
        final int fn = filter.length();
        final int tn = topic.length();
        while (true) {
            if (f < fn && filter.charAt(f) == '#') {
                return true;
            }
            final int fEnd = levelEnd(filter, f);
            final int tEnd = levelEnd(topic, t);
            final boolean plus = fEnd - f == 1 && filter.charAt(f) == '+';
            if (!plus && (fEnd - f != tEnd - t || !filter.regionMatches(f, topic, t, fEnd - f))) {
                return false;
            }
            final boolean filterDone = fEnd == fn;
            final boolean topicDone = tEnd == tn;
            if (filterDone || topicDone) {
                // "a/#" matches "a": the topic ended, and what is left of the filter is its multi-level wildcard
                return filterDone && topicDone || topicDone && fEnd + 2 == fn && filter.charAt(fEnd + 1) == '#';
            }
            f = fEnd + 1;
            t = tEnd + 1;
        }
    }

    private static int levelEnd(final String s, final int from) {
        final int slash = s.indexOf('/', from);
        return slash < 0 ? s.length() : slash;
    }
}
