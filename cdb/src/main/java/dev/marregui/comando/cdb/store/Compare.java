package dev.marregui.comando.cdb.store;

/** A comparison of a column with a constant, as a scan pre-filter applies it ({@link PartitionReader#selectLong}). */
public enum Compare {
    EQ,
    NE,
    LT,
    LE,
    GT,
    GE;

    /** True when {@code order}, the sign of {@code value - constant}, satisfies this comparison. */
    public boolean holds(final int order) {
        return switch (this) {
            case EQ -> order == 0;
            case NE -> order != 0;
            case LT -> order < 0;
            case LE -> order <= 0;
            case GT -> order > 0;
            case GE -> order >= 0;
        };
    }
}
