package dev.marregui.comando.core.state;

/** The selected tab of the lower panel; {@code viewId} names the view when {@code kind} is VIEW. */
public record LowerTab(Kind kind, long viewId) {

    public enum Kind { RESULTS, SESSION, APPROVALS, TERMINAL, VIEW }

    public static final LowerTab RESULTS = new LowerTab(Kind.RESULTS, 0L);
    public static final LowerTab SESSION = new LowerTab(Kind.SESSION, 0L);
    public static final LowerTab APPROVALS = new LowerTab(Kind.APPROVALS, 0L);
    public static final LowerTab TERMINAL = new LowerTab(Kind.TERMINAL, 0L);

    public static LowerTab view(long viewId) {
        return new LowerTab(Kind.VIEW, viewId);
    }
}
