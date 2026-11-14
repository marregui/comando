package dev.marregui.comando.cdb.exec.expr;

import dev.marregui.comando.cdb.exec.Page;
import dev.marregui.comando.cdb.exec.Vector;

/** A compiled expression: its values for every row of a page, of the type its {@code Bound} has. */
@FunctionalInterface
public interface VectorFn {

    /** The values for rows {@code [0, page.size)}; the vector may be reused by the next call. */
    Vector eval(Page page);
}
