# 2. Swing and FlatLaf as the UI toolkit

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted | Miguel, on the defaults proposed in the plan review |

## 1. Context

The window needs a code editor, a terminal, a large table, a large plot and a transcript of rich text. JavaFX, Compose Desktop and a web UI were compared against Swing ([plan, §4.6]). Swing has RSyntaxTextArea and JediTerm, keeps the existing code, and ships smallest.

## 2. Decision

Swing with FlatLaf. RSyntaxTextArea for the editor. A JavaFX `WebView` only as the fallback for documents, if the painted transcript fails.

## 3. Consequences

The transcript is painted on Java2D ([0021](0021-painted-transcript.md)); the fallback was not needed. The DMG is 83 MB with the jlinked runtime.
