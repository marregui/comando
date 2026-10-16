---
name: deep-review
description: Run a review, QA round or audit before a change is called done - adversarial, against the stated requirements, with every finding proved by a failure sequence and, for serious ones, a failing test.
---

# Deep review

How a review, QA round or audit is run before a deliverable is called done.

**Stance.** Assume every change, ours included, carries a defect that will reach production unless this review finds
it. The author's good faith, a green CI, an earlier approval and a note marked "fixed" are not evidence. Aggressive in
the hunt, defensive in the verdict: when in doubt, it does not ship.

## The yardstick and the scope

- **Never audit code against a spec derived from that code.** The yardstick is the task's stated requirements and every
  document attached to it. A claim those documents make that the code contradicts is a finding.
- **Findings outside the yardstick don't count.** Complexity, generality or hardening nobody asked for is listed under
  "deliberately not counted".
- **Scope is the diff plus every seam it leans on**: the services, schedulers, stores and parsers it calls without
  touching. A pre-existing defect that the change now exposes counts, and is fixed in the same change.
- **A document review also checks the untouched claims on the topics in scope**, against the same sources.

## Who reviews

- After the code is done: one reviewer, or up to 4 lens reviewers in parallel, each from a spec in the day's
  `SCRATCHPAD/…/work/` folder (template: the kit's `templates/agent-spec.md`) naming the diff, the requirements, the
  failure classes it hunts and the findings schema. Each lens writes its own output file.
- Failure classes: lost signals and missed wake-ups; failure injection (every future or callback can fail, arrive late
  or twice); configuration abuse; hostile input at every parser (`null`, wrong types, trailing documents, empty after
  normalisation, conflicting duplicates); outcome truthfulness (no failure path reports success); lifecycle timing;
  contracts across module boundaries; two ways to do one thing (duplicated logic that can diverge on a value the
  requirements guarantee); and, for fixes, regressions next to the change.
- A small diff gets one reviewer with every class. A large one is split into lenses, each class in exactly one lens.

## Lifecycle cases

For every component in scope that starts, stops, connects or cleans up, walk each row; each gets a test that fails at
the reviewed head, or `n/a` with a one-line reason. Tests reach the phase with a latch or barrier, never a fixed sleep.

| # | Case | Must hold |
|---|------|-----------|
| 1 | Stop arrives while start is still pending | stop waits for or cancels the start; nothing leaks |
| 2 | Stop arrives during final cleanup | a typed outcome; cleanup completes or is reported |
| 3 | Cleanup fails after the work succeeded | result kept, failure reported, a later retry releases the resource |
| 4 | Cleanup fails after the work failed | the primary error is not masked |
| 5 | One of several children fails to stop | the others are not stopped twice; the parent is not reported stopped |
| 6 | A callback arrives after close | no state comes back to life, nothing is attributed to the wrong session |
| 7 | Restart after a failed stop | no duplicate polls, subscriptions or scheduled tasks |
| 8 | Executor shutdown with work queued | pending futures complete exceptionally; none hang |

## Evidence

- **Context proof before findings.** Every reviewer writes, before its first finding: the subsystem's purpose, the
  invariants that must not break, the change's intent, its unknowns, and a confidence of 0–100 for each. A wrong
  invariant or an open unknown sends the lens back before any of its findings is trusted.
- **Evidence is a red test.** Every serious finding comes with a test that asserts the safe behaviour and fails at the
  reviewed head, before any fix; a finding only source analysis can show (a lock cycle) says so.
- **Converters and parsers get probes**: generated inputs against the production class, every dimension generalised,
  every separator and escape inside a name, every numeric bound ±1.
- **Earlier fixes are re-checked by class**, not by instance, whoever raised them.
- **A review holds for its head only.** Every report names the commit it ran on (`git rev-parse HEAD`, `dirty` when the
  tree had uncommitted changes); a rebase or merge after the round needs a round on that delta.

## Verdict

- Read the diff cold, before the description; form the judgment, then compare it with the narrative.
- Verify every finding (a concrete failure sequence, never a suspicion) before acting on it. Report a "verified
  correct" list and a "deliberately not counted" list. A finding with no failure sequence reachable on real input is a
  wish, not a defect.
- A parallel review is partial until every lens has written its file; a lens that died is re-run, never counted clean.
- When a requirement rests on a capability the code lacks, the outcome is PARTIAL: what shipped and how it was
  verified, what did not and where the capability is absent, and the follow-up work scoped now.
- Another round only if the fixes changed the code materially, on the new diff, with the known findings passed in.
