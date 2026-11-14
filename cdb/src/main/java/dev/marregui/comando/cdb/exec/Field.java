package dev.marregui.comando.cdb.exec;

import dev.marregui.comando.cdb.CdbType;

/** One column of a cursor's output: its name as the result shows it, and its type. */
public record Field(String name, CdbType type) {
}
