# 140. Every file is read mapped

| Date | Status | Decided by |
|---|---|---|
| 2026-11-07 | accepted; built | Miguel and Claude |

## 1. Context

comando read files in four ways: whole Strings, whole line lists, streams and channels, with one mapped reader of its
own (`RunSettings`, now on `Mapped` too). About 50 sites in about 30 classes. The stored conversations are the large
case: about 1 GB, with lines of megabytes, and the first load held every line as a String. Miguel set the direction on 2026-11-02 (map files, scan
bytes in place, keep offsets, decode only the slice needed) and made it general on 2026-11-07: every file comando reads
is mapped.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| how a file is read | through `core.files.Mapped`: `read` runs a body on the mapping, `open` keeps it for one thread, and `text`, `bytes`, `lines`, `head` and `linesWith` are the common reads | one way of doing things, and one place that knows the fault below |
| a whole-file read | allowed where the reader needs all of it (a JSON parser, the editor), through the mapping: `stream()` for a library, `text` for a String | a parser and a text component hold the text anyway; only the read changes |
| a part of a file | a slice of the mapping: mapping reads no page, so a tail or a head costs what it touches | no seek, no buffer, no offset arithmetic of its own |
| lines that matter | `linesWith` scans the bytes and decodes only the lines holding a needle | the rule's point: a file of tool output is not decoded to find the few lines asked for |
| a file cut while mapped | bytes leave a mapping one by one through `segment.get`, whose fault the JDK reports as an `InternalError`; `read` turns it into an `IOException`, and a reader on `open` handles it itself. No `MemorySegment.copy`, `asByteBuffer` or other bulk copy touches a mapping | the failure mapped files add. A compiled bulk copy off a file cut in place raises SIGBUS in `StubRoutines::copy_byte_f` and ends the JVM (Temurin 25, aarch64), so one rotated log could take comando down; `MappedCutTest` forks a warmed child to prove the route |
| malformed UTF-8 in a whole file | `text` and `lines` fail with an `IOException`, as `Files.readString` did | a reader that writes the text back must not turn bytes it could not decode into replacement characters; `FileView` tells a binary file this way |
| malformed UTF-8 in a slice | decodes to the replacement character | a slice is read for showing or searching: a damaged line of a log or a transcript should not lose the rest; nothing writes it back |
| what stays outside | a library that takes only a path: `ZipFile`, `Font.createFont`, `JFileChooser`; the process's own pipes; the lock on `comando.lock`; writes | none reads a file for comando's own use |

## 3. Consequences

`FileReadRulesTest` fails on a new read by another route and on a bulk copy off a segment. A byte-wise copy is slower
than a bulk one; it runs on the slices decoded and the whole files read, and the scan that decides what to decode reads
in place either way. A small JSON file is mapped too: no faster than a read, and
kept for the single rule. Pictures read their header through `MemoryCacheImageInputStream`, so `ImageIO` writes no cache
file. A mapping belongs to one thread, since its arena is confined: a stream or a slice must not leave the body.

## 4. Alternatives

Mapping only files over a size: two routes to keep, and the threshold would need a reason. An index of the conversations:
ruled out with the Atlas, and a warm scan needs none.
