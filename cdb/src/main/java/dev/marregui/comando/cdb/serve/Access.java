package dev.marregui.comando.cdb.serve;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.sql.Ast;
import dev.marregui.comando.cdb.sql.Parser;

/** What the read-only secret may run, the same rule for both listeners. */
final class Access {

    private Access() {
    }

    /**
     * Whether {@code sql} only reads: a query, a {@code SHOW} or an {@code EXPLAIN} (of any statement, which plans it
     * without running it). Text that does not parse counts as reading: the engine then refuses it with its parse error,
     * and runs nothing.
     */
    static boolean readOnly(final String sql) {
        final Ast.Statement statement;
        try {
            statement = Parser.parse(sql);
        } catch (final CdbException _) {
            return true;
        }
        return switch (statement) {
            case Ast.Query _, Ast.Show _, Ast.Explain _ -> true;
            case Ast.CreateTable _, Ast.Update _, Ast.Insert _, Ast.AlterTable _, Ast.DropTable _, Ast.Truncate _,
                    Ast.DropPartition _, Ast.Delete _ -> false;
        };
    }
}
