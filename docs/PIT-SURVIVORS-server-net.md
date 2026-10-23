# PIT survivors: `com.fulcrologic.jxpilot.server.net`

Run (in-reactor, never `install`):
`MAVEN_OPTS= mvn -o -pl server -am test-compile org.pitest:pitest-maven:mutationCoverage -Dthreads=6`

The run also mutates `common`. The `server` report includes `server.world`, which has no tests
on this branch, so the numbers below count `server.net` classes only.

Excluded from mutation, in `server/pom.xml`. These are effectful edges and orchestration, and
`LoopbackIntegrationTest` covers them: `ServerMain`, `UdpTransport`, `Server`, `JdkInterfaceSource`
and `JdkResolver`. `StubGameHost` is also excluded: it is a placeholder for the real simulation
adapter. `AllocationTest` and `LoopbackIntegrationTest` are excluded as mutation-killing tests.

Score: **99.0%** (383 of 387 mutants killed or timed out; 4 survivors, all justified below).

| class:line | mutant | why it survives |
|---|---|---|
| `ServerNet:401` (`drop`) | `i < activeCount` → `i <= activeCount` | Equivalent. The connection being dropped is always in `active[0..activeCount)`, so the loop breaks before the boundary matters. |
| `ServerNet:474` (`peers`) | `byChannel.length * 2` → `/ 2` | Equivalent. `Math.max(channel + 1, …)` always makes the array large enough; the doubling only reduces how often it grows. |
| `ServerArgs:94` (`isFlag`) | `a.length() < 2` → `<= 2` | Equivalent. No `Opt` has a one-character name, so a two-character `-x` is never the option being merged. |
| `RateLimiter:84` (`slot`) | `h >>> 16` → `h << 16` | Equivalent for behaviour. The slot stays in range and buckets still work; only the hash spread changes. The spread test passes with both, because the multiplicative step already separates consecutive keys. |
