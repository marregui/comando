# 21. Painted transcript

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted by Miguel 2026-10-13 | Claude, during implementation |

## 1. Context

[0015](0015-stopgap-transcript-in-m1.md) planned a renderer that streams a 10 k-token turn within a 16 ms frame budget.

## 2. Decision

A transcript painted with Java2D: Markdown to paragraphs, rich text laid out with `LineBreakMeasurer`, one item per block, virtualised painting, layout of the visible part first and the rest in 8 ms idle slices.

## 3. Consequences

Frame p99 is 3.26 ms on the synthetic session; `:ui:transcriptGate` fails the build of the gate above 16 ms ([Transcript renderer](../design/transcript-renderer.md)). Text selection, find and links are comando's own code.
