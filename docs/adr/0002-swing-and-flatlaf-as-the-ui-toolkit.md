# 2. Swing and FlatLaf as the UI toolkit

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted | Miguel |

## 1. Context

The window needs a code editor, a terminal, a table and a plot over large results, and a transcript of rich text. The
design plan (§4.6) compared Swing with JavaFX, Compose Desktop and a web UI. Swing has RSyntaxTextArea for the editor
and JediTerm for the terminal, keeps the existing code, and gave the smallest package of the four.

## 2. Decision

Swing with FlatLaf, and RSyntaxTextArea for the editor. A JavaFX `WebView` is the fallback for documents only, used if
the painted transcript fails.

Miguel decided, on the default proposed in the review of the design plan.

## 3. Consequences

The transcript is painted on Java2D ([record 21](0021-painted-transcript.md)); the fallback was not needed, and no
module depends on JavaFX. The disk image measured 83 MB with its jlinked runtime when this record was written; the
image built on 2026-10-26 measures 98 MB.

## 4. Alternatives

| Option | Why rejected |
|---|---|
| JavaFX, Compose Desktop, a web UI | RSyntaxTextArea and JediTerm are Swing components, the existing code is Swing, and Swing gave the smallest package in the plan's comparison |
