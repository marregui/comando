# 10. Own table and plot on Java2D

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted | Miguel, on the defaults proposed in the plan review |

## 1. Context

The table must scroll 1 M rows and the plot draw millions of points. Library components box values or draw every point.

## 2. Decision

The grid and the plot are comando's own: the grid over the columnar result, the plot reducing each pixel column to its minimum and maximum. Libraries for the editor, diffs, Markdown and the terminal.

## 3. Consequences

See [Widgets](../design/widgets.md) and [Database workbench](../design/database-workbench.md). Diffs use java-diff-utils behind the kit's `Diffs`.
