package dev.marregui.comando.cdb.exec;

import java.util.List;

import dev.marregui.comando.cdb.CdbException;

/**
 * union (distinct rows of both), union all, except, intersect, on whole rows with NULL equal to NULL, as QuestDB
 * compares them. {@code union}, {@code except} and {@code intersect} return distinct rows in first-seen order, the
 * left's before the right's; {@code union all} returns the left's pages, then the right's, uncopied. {@code except}
 * and {@code intersect} read the whole right input first. The right's columns must be stored as the left's are.
 */
final class SetOpCursor implements Cursor {

    private final Kind kind;
    private final Cursor left;
    private final Cursor right;
    private final RowsPacker packer;
    private final RowsSet seen;
    private RowsSet rightRows;
    private boolean leftDone;
    private boolean rightDone;
    private Page page;
    private int pos;

    SetOpCursor(final String op, final Cursor left, final Cursor right) {
        this.left = left;
        this.right = right;
        try {
            kind = kind(op);
            check(left.fields(), right.fields(), op);
        } catch (final RuntimeException e) {
            Rows.close(left, right);
            throw e;
        }
        packer = new RowsPacker(left.fields());
        seen = kind == Kind.UNION_ALL ? null : new RowsSet(left.fields());
    }

    @Override
    public List<Field> fields() {
        return left.fields();
    }

    @Override
    public Page next() {
        if (kind == Kind.UNION_ALL) {
            return input();
        }
        if (kind != Kind.UNION && rightRows == null) {
            rightRows = readRight();
        }
        while (true) {
            if (page == null) {
                page = input();
                if (page == null) {
                    return packer.flush();
                }
                pos = 0;
            }
            final Page in = page;
            while (pos < in.size) {
                final int row = pos++;
                if (kept(in, row) && seen.add(in, row)) {
                    packer.append(in, row, 1);
                    final Page full = packer.full();
                    if (full != null) {
                        if (pos == in.size) {
                            page = null;
                        }
                        return full;
                    }
                }
            }
            page = null;
        }
    }

    @Override
    public void close() {
        page = null;
        rightRows = null;
        Rows.close(left, right);
    }

    private static Kind kind(final String op) {
        return switch (op) {
            case "union" -> Kind.UNION;
            case "union all" -> Kind.UNION_ALL;
            case "except" -> Kind.EXCEPT;
            case "intersect" -> Kind.INTERSECT;
            default -> throw new CdbException("cannot run an unknown set operation [op=" + op + "]", -1);
        };
    }

    private static void check(final List<Field> l, final List<Field> r, final String op) {
        if (l.size() != r.size()) {
            throw new CdbException("cannot combine queries of different column counts [op=" + op + ", left="
                    + l.size() + ", right=" + r.size() + "]", -1);
        }
        for (int c = 0; c < l.size(); c++) {
            if (!Rows.sameStorage(l.get(c).type(), r.get(c).type())) {
                throw new CdbException("cannot combine columns of different types [op=" + op + ", column="
                        + l.get(c).name() + ", left=" + l.get(c).type() + ", right=" + r.get(c).type() + "]", -1);
            }
        }
    }

    /** The next input page: the left's, then, for the unions, the right's; null at the end. */
    private Page input() {
        if (!leftDone) {
            final Page in = left.next();
            if (in != null) {
                return in;
            }
            leftDone = true;
            left.close();
        }
        if (rightDone || kind == Kind.EXCEPT || kind == Kind.INTERSECT) {
            return null;
        }
        final Page in = right.next();
        if (in == null) {
            rightDone = true;
            right.close();
        }
        return in;
    }

    private boolean kept(final Page in, final int row) {
        return switch (kind) {
            case UNION, UNION_ALL -> true;
            case EXCEPT -> !rightRows.contains(in, row);
            case INTERSECT -> rightRows.contains(in, row);
        };
    }

    private RowsSet readRight() {
        final RowsSet set = new RowsSet(right.fields());
        Page in;
        while ((in = right.next()) != null) {
            for (int r = 0; r < in.size; r++) {
                set.add(in, r);
            }
        }
        right.close();
        return set;
    }

    private enum Kind {
        UNION,
        UNION_ALL,
        EXCEPT,
        INTERSECT
    }
}
