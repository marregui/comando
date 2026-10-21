# 82. Threads by kind, with a budget

| Date | Status | Decided by |
|---|---|---|
| 2026-10-21 | accepted; amends the thread rule of the architecture (§6, §9 rule 2) | Miguel (adopt the kinds and the budget; one thread per session; owned errands rather than structured-concurrency scopes; the budget test first), Claude (the kinds, the measurements, timed strands in place of a shared clock, the GC flags dropped on their measurement) |

## 1. Context

With five sessions comando ran 85 platform threads, 30 of them its own: ten singletons, each part's dedicated
scheduler or executor, and four per Claude process (stdout, stdin, stderr, exit). 37 call sites started a virtual
thread no owner closed. The design had a thread table but no budget and no classification, so the count followed the
number of parts and sessions. The literature for interactive applications converges on event loops that own the state,
a small layer of sequential workers behind queues, one timer facility and an owner for every task (Half-Sync/Half-Async,
Active Object, SEDA, actors, structured concurrency). The inventory, the literature and the measurements are in the
session's notes outside the repository.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| kinds | event loop (the EDT, the reducer), watchdog, session reader, strand, errand, fork; only the first three are platform threads | each kind has one mechanism; a new platform thread needs a new kind |
| the budget | two plus one per running session: `comando-reducer`, `watchdog`, `claude-out-<session>` | measured: `ThreadBudgetTest` with five fake sessions, 7 threads (was 29), none after the close |
| a session's threads | stdout on a platform thread; stdin and the exit on virtual threads; stderr to a file in the temp folder, its last 40 lines read at the exit, the file deleted | measured on JDK 25: a virtual thread blocked on a process pipe holds an operating-system thread (100 readers, 101 carriers); `waitFor` holds none; a session's stdout must be read live, stderr only at the exit |
| the watchdogs | one platform `watchdog` runs the EDT's probe and the reducer's look every 100 ms | both are non-blocking looks; one thread wakes 10 times a second instead of 20 |
| timers | each part keeps its scheduler, now a timed strand: a `ScheduledThreadPoolExecutor` on one virtual thread; no shared clock thread | a virtual thread waiting for its next task holds no operating-system thread and the JDK's one delay thread keeps the timers, so a shared clock would only add a hand-off and a thread |
| sequential workers | strands on virtual threads: the store, the keychain, each database lane, Mermaid, local images, the topic reader, the session strands, the Changes view's reads and writes | measured: 200 virtual threads blocked on sockets ran on 12 threads; JDBC waits on sockets; `synchronized` no longer pins since JDK 24 (JEP 491) |
| one-off work | an errand of its owner (`App`, `ToolsAdapter`, `EmbeddedQuestDb`, the window's `UiErrands`), interrupted and waited for 2 s at the owner's close; a thread its caller joins within the call is a fork | Miguel chose owned executors over `StructuredTaskScope`, a preview API in JDK 25 (JEP 505) |
| the rule's guard | `ThreadRulesTest` (ArchUnit, every module): `Thread.ofPlatform` only in `StateLoop`, `Watchdog`, `ClaudeProcess` and `Main`; `Thread.ofVirtual`, `Executors` (but per-task virtual executors), pools and `java.util.Timer` only in `Threads` | a rule that a build checks does not erode |
| GC threads | the JVM's defaults stay | measured on `:ui:transcriptGate`, three runs each: `-XX:ParallelGCThreads=2 -XX:ConcGCThreads=1` lengthened the pause p99 from 5–6 ms to 30–48 ms and the longest pause from 7–10 to 52–66 ms; idle GC threads cost nothing |

## 3. Consequences

The process with five sessions holds about 45 resident threads instead of 85; the rest are the JVM's, the toolkit's,
the libraries' and the JDK's process reapers, which comando does not start. The store's threads are now daemons: what is
queued is written because `close()` waits for it, at a quit and in the shutdown hook. A strand's thread no longer shows
in `Thread.getAllStackTraces()`; tests find it with `Threads.alive(name)`, and a JDK thread dump in JSON
(`jcmd <pid> Thread.dump_to_file -format=json`) shows it. A process's stderr file is left in the temp folder only
when comando dies before the process does.
