# 21. Painted transcript

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted | Claude |

## 1. Context

[Record 15](0015-stopgap-transcript-in-m1.md) put a stopgap transcript in M1 and planned a renderer for M2 that streams
a 10 k-token turn within a 16 ms frame budget. This record replaces the stopgap.

## 2. Decision

A transcript painted with Java2D: Markdown to paragraphs, rich text laid out with `LineBreakMeasurer`, one item per
block, virtualised painting, layout of the visible part first and the rest in idle slices of up to 8 ms.

Claude decided during implementation; Miguel accepted the record on 2026-10-13.

## 3. Consequences

Frame p99 on the synthetic session is about 3 ms: 3.39 ms on 2026-10-18 (Apple silicon, 12 cores, JDK 25, headless;
the measured figures are in [The frame-time gate](../ui.md#43-the-frame-time-gate)). `./gradlew :ui:transcriptGate`
fails when either p99 (the streamed session, the open thinking block) exceeds 16 ms. CI runs it without enforcing it,
because runner speed varies ([The transcript](../ui.md#4-the-transcript)). Text selection, find and links are
comando's own code.
