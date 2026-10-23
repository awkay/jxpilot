# JXPilot Testing Standard

Mandatory for all code. Java translation of the fulcro-spec TDD principles
(`~/.claude/skills/fulcro-spec-tdd/SKILL.md` — read it for the full rationale).
Testability is a design quality: if something is hard to test, restructure the code.

## Procedure (per method/function)
1. Write the method.
2. Enumerate its behaviors in its Javadoc (`BEHAVIOR: ...` lines). A behavior is each branch,
   boundary, error path, and observable effect.
3. Write a test for EACH behavior, with a message that states the behavior.
4. **Verify each test**: temporarily break that specific behavior in the implementation and
   confirm the matching assertion (and ideally only it) fails. Restore. No cascading failures:
   a micro-change to one behavior should fail one targeted assertion, not the whole suite.
5. Run PIT mutation testing on the module (see below) and kill surviving mutants or justify them.

## Test shape
- One `@Nested` class per method/area (the analogue of `component`); one `@Test` per *setup*,
  holding several labelled assertions via `assertAll` (the analogue of a multi-triple
  `assertions` block, ~10-15 max). Don't create one test method per trivial assertion, and
  don't write a giant test with unlabelled asserts.
- Every assertion has a message that is a positive statement of the behavior:
  `assertEquals(35, b.x(), "places the base at the centre of its block")`.
- The message must be falsified by any code change that invalidates it. Counts alone prove
  only counts: assert the actual contents (`assertEquals(List.of(...), ...)`, set equality,
  exact coordinates), and order only where order matters.
- Prove what code DOES. Negative claims must be conditional and paired with the positive case
  ("fires iff reload counter is zero" — test both sides).
- Floating point: use explicit tolerances with a stated reason.
- Name tests as readable outlines: `@DisplayName` or method names that read as sentences.

## Design rules that enable this
- **Side effects at the edges.** Physics, collision, rules, robot decisions, snapshot
  encode/decode, interpolation, HUD layout math, wall geometry: pure/deterministic code over
  plain state, no I/O, no clock, no global RNG. Orchestrators (net loop, GLFW loop, server main)
  are thin and call tested pieces.
- **Inject nondeterminism.** Time via a `Clock`/tick counter passed in; randomness via an injected
  seeded RNG (`SplittableRandom`/own xorshift) owned by the World. Tests fix the seed.
- **Single level of abstraction** per method: orchestration methods call named steps; low-level
  math lives in small helpers that are tested directly. Test each level on its own behaviors;
  test the orchestrator by substituting its collaborators — no combinatorial explosion.
- **Fakes, not mocking frameworks.** Collaborators that do effects (network channel, GL, file
  reading, clock) are behind small interfaces; tests use hand-written fakes/spies that record
  calls. Real implementations get their own limited integration tests (e.g. real UDP over
  loopback, real parsing of every shipped map).
- **No guardrails equivalent.** The Java type system stands in for guardrails; don't add
  runtime contract-checking layers or validating fakes for that purpose.
- GL code can't be unit-tested headlessly: keep geometry/vertex generation, colour choice, text
  layout, and camera/wrap math in pure classes that write into a plain `FloatBuffer`/array, and
  test those. The GL calls themselves stay in a thin layer.

## Coverage of behaviors, not lines
Cover: every branch, boundaries (zero, max capacity, wrap edges, exactly-at-wall), error paths
(malformed map lines, truncated packets, pool exhaustion), and every option that changes behavior
(both settings). For physics, prefer small scenario tests with exact expected values derived from
the C code's formulas (cite the C file/function in a comment), over "it moves somewhere".

## Mutation testing (automated "break it and see")
PIT (`org.pitest:pitest-maven` + `pitest-junit5-plugin`) is configured per module.
Run `mvn -pl <module> org.pitest:pitest-maven:mutationCoverage`. Target: mutation score ≥ 85%
for pure logic packages. Surviving mutants are either killed with a new behavior test or listed
with a one-line justification (equivalent mutant, hot-path perf assert, etc.) in the agent report.
Excluded from the threshold: GL/GLFW glue and `main` classes.

## Allocation tests
See SPEC.md: hot paths assert zero steady-state allocation via
`com.sun.management.ThreadMXBean.getCurrentThreadAllocatedBytes()`.

## What to skip
Trivial getters/records, pure delegation with no logic, and third-party behavior.
