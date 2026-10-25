# PIT survivors: `com.fulcrologic.jxpilot.server.net`

Run (in-reactor, never `install`):
`MAVEN_OPTS= mvn -o -pl server -am test-compile org.pitest:pitest-maven:mutationCoverage -Dthreads=6`

The run also mutates `common`. The `server` report includes `server.world`, which has no tests
on this branch, so the numbers below count `server.net` classes only.

Excluded from mutation, in `server/pom.xml`. These are effectful edges and orchestration, and
`LoopbackIntegrationTest` covers them: `ServerMain`, `UdpTransport`, `Server`, `JdkInterfaceSource`
and `JdkResolver`. `StubGameHost` is also excluded: it is a placeholder for the real simulation
adapter. `AllocationTest`, `LoopbackIntegrationTest` and `ServerMainTest` (real sockets and threads)
are excluded as mutation-killing tests. `UdpTransport.selectMillis` and `TickScheduler` are still
unit tested in `UtilitiesTest`.

Score: **99.4%** (500 of 503 mutants killed or timed out; 3 survivors, all equivalent).

| class:line | mutant | why it survives |
|---|---|---|
| `ServerNet` (`drop`, the `active` scan) | `i < activeCount` → `i <= activeCount` | Equivalent. The connection being dropped is always in `active[0..activeCount)`, so the loop breaks before the boundary matters. |
| `ServerNet` (`peers`) | `byChannel.length * 2` → `/ 2` | Equivalent. `Math.max(channel + 1, …)` always makes the array large enough; the doubling only reduces how often it grows. |
| `ServerArgs:94` (`isFlag`) | `a.length() < 2` → `<= 2` | Equivalent. No `Opt` has a one-character name, so a two-character `-x` is never the option being merged. |

The previous `RateLimiter.slot` survivor is gone: slots are now the key's low bits (keys are already
secret-keyed SplitMix64 hashes, see `SourceKeys`), and a known-answer test pins `SourceKeys.of`.

## Latency phase 1b fix round: InputBuffer and KernelState

PIT on `server.net.InputBuffer` and `server.host.KernelState` with the `server.net` and `server.host` tests:
InputBuffer **68/70 (97%)**, KernelState **94/99 (95%)**. Killed afterwards (verified by hand): InputBuffer
reset's `Arrays.fill(full, false)` (an entry buffered before a reset must not be the new session's:
`InputBufferTest.reset`) and KernelState.track's `k < tracked` → `<=` (a wormhole reopened at once on the blocks
just untracked: `KernelStateTest.untrack`). Equivalent:

| file:line | mutator | why equivalent |
|---|---|---|
| InputBuffer.java:98 | `Arrays.fill(starved, false)` removed in reset | `starved` is read only behind `open`, which reset clears. |
| KernelState.java:89 | the initial capacity sum | capacity only: `track` grows the arrays. |
| KernelState.java:119 | `2 * tracked` → `/` (no coverage) | growth beyond cannons + targets + 64 open wormhole ends; capacity only. |
| KernelState.java:174 | `i - mapCount` → `+` (shadow index) | equivalent: the shadow arrays grow to fit, and each wormhole slot still compares with its own shadow. |
| KernelState.java:173 | `i < n` → `<=` | not strictly equivalent: one extra WORM entry for the slot past the count, which every reader ignores (it is beyond WORM_COUNT, and the slot is rewritten before it is counted). Harmless; not worth a test. |
