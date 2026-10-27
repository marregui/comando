# 10. Own table and plot on Java2D

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted | Miguel |

## 1. Context

The table must scroll 1 M rows and the plot draw millions of points. Library components box values or draw every
point.

## 2. Decision

The grid and the plot are comando's own: the grid reads the columnar result, the plot reduces each pixel column to its
minimum and maximum. Libraries serve the editor, diffs, Markdown and the terminal.

Miguel decided, on the default proposed in the review of the design plan.

## 3. Consequences

The grid is `GridTable`, the plot `PlotCanvas` ([Widgets](../ui.md#3-widgets),
[The database](../workbench.md#3-the-database)). Diffs use java-diff-utils behind the kit's `Diffs`.

## 4. Alternatives

| Option | Why rejected |
|---|---|
| library table and plot components | they box values or draw every point |
