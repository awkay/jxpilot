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
- Git: only the lead commits to `main` and pushes (remote: github.com/awkay/jxpilot, public).
  Agents in the main tree (`/Users/tonykay/Downloads/jxpilot`) never commit, reset, checkout, or stash.
  Parallel agents work in a dedicated git worktree under `/Users/tonykay/Downloads/jxpilot-wt/<branch>`
  and may commit only to that branch (never push, merge, or rebase); the lead merges into `main`.
- Worktree builds: NEVER `mvn install` from a worktree. It overwrites the shared jxpilot jars in
  ~/.m2 that main-tree agents build against. Build in-reactor instead:
  `MAVEN_OPTS= mvn -q -o -pl <module> -am test`.
- License: GPL-2.0-or-later (derivative of XPilot 4.5.5). Every `.java` file must start with the
  header in `license-header.txt`; the build fails without it (`mvn license:format` adds it).
  Never alter files in `maps/` (unmodified XPilot originals). Any new bundled asset needs a
  GPL-compatible license, its license text committed next to it, and a row in README.adoc's
  third-party table.
- Subagent effort: every implementation agent and reviewer is spawned with `effort: high`.
  Effort is fixed at spawn and can't be changed on resume, so agents running at another level
  get replaced at their next task boundary, with a handoff brief.
- Fidelity review: once an area is implemented (e.g. movement/walls, ship rules, combat, items,
  robots, networking, client painting/HUD), the lead runs a dedicated C-to-Java fidelity review
  of that area at `effort: xhigh`. It compares the Java line by line against the C functions it
  ports, and its findings go to the implementer like any other review.
- Docs follow every landing: after any merge or commit to `main` that changes behaviour, options,
  flags, protocol or status, the lead resumes (or re-spawns) the docs agent to update the user guide
  (`docs/guide/`): regenerate tables (`make -C docs/guide generate`), update status tables and the
  NOTEs about unmerged branches, and add or adjust any affected chapter. The docs update lands
  before the next release tag.
