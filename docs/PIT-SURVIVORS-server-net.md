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
