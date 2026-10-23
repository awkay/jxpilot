# JXPilot

Java 25 / Maven / LWJGL reimplementation of XPilot 4.5.5 (C source: `/Users/tonykay/Downloads/xpilot-4.5.5`).

- Spec: `docs/SPEC.md` (stack, GC rules, time model, architecture, milestones).
- Testing: `docs/TESTING.md` is mandatory — fulcro-spec TDD principles translated to JUnit 5:
  enumerate every behavior, labelled falsifiable assertions, verify each test by breaking the
  behavior, effects at the edges, PIT mutation score ≥ 85% on logic packages.
- Skip guardrails-style contract checking: the type system is good enough.
- **PROCESS KILLING — HARD RULE.** Never run `pkill`, `killall`, `kill -9 -1`, `kill 0`, or any
  kill by name or pattern (`pkill java`, `pkill -f ...`, `killall java`). This machine runs other
  JVMs, IDEs and Claude sessions; a broad kill took them all down once already. Only kill a PID
  that you started yourself and captured (`$!` / the PID printed by your own launch), e.g.
  `kill <that-pid>`. Prefer self-terminating runs instead: `--frames N` / `--screenshot`, test
  timeouts, `timeout`. If something you didn't start is in the way, stop and report it.
- Shell gotcha: the environment exports `MAVEN_OPTS=-Dmaven.test.failure.ignore=true`. The root pom
  forces `testFailureIgnore=false`; also run builds as `MAVEN_OPTS= mvn ...`.
- C-like structs / bit fields (wire records, off-heap pools): where it beats hand-rolled
  `ByteBuffer` codecs (and stays allocation-free), use Javolution's `javolution.io.Struct`
  as a flyweight bound via `setByteBuffer(buffer, offset)`. Use the Java 21-cleaned vendored copy
  in `/Users/tonykay/fulcrologic/simplymeet/src/java/javolution` (vendor only the classes `Struct`
  transitively needs into this repo). Reference usage: `simplymeet/src/java/simplymeet/*Struct.java`
  and `simplymeet/src/main/simplymeet/lib/member_details_cache.clj`.
- Build/test: `mvn -q install` from the repo root.
- Implementation subagents must be pointed at both docs above.
- Team workflow: the lead orchestrates. Implementation agents own code changes. Read-only
  reviewers report to the lead: `bug-hunter` checks correctness, C fidelity and test adequacy;
  `perf-reviewer` checks allocation, GC, CPU and wire size. The lead triages findings and
  relays the accepted ones to the implementer. Each module passes both reviews before the
  next phase builds on it.
- Git: only the lead commits, as a checkpoint after each reviewed phase (on `main`, then `git push`; remote: github.com/awkay/jxpilot, private).
  Implementation agents and reviewers never commit, reset, checkout, or stash.
