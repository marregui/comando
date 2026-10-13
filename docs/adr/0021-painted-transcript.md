# 21. Painted transcript

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted by Miguel 2026-10-13 | Claude, during implementation |

## 1. Context

[0015](0015-stopgap-transcript-in-m1.md) planned a renderer that streams a 10 k-token turn within a 16 ms frame budget.

## 2. Decision

A transcript painted with Java2D: Markdown to paragraphs, rich text laid out with `LineBreakMeasurer`, one item per block, virtualised painting, layout of the visible part first and the rest in 8 ms idle slices.

## 3. Consequences

Frame p99 is about 3 ms on the synthetic session, no frame over 16 ms ([Quality](../design/quality.md) keeps the measured figures). `./gradlew :ui:transcriptGate` fails when either p99 (the streamed session, the open thinking block) exceeds 16 ms; CI runs it without enforcing it, because runner speed varies ([Transcript renderer](../design/transcript-renderer.md)). Text selection, find and links are comando's own code.
