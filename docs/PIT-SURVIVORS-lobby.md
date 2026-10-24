# PIT results: lobby mode

Run (in-reactor, never `install`; `common` and `server` must be built with the client, so keep `-am`):

```
MAVEN_OPTS= mvn -o -pl common,server -am test-compile org.pitest:pitest-maven:mutationCoverage \
  -DtargetClasses='com.fulcrologic.jxpilot.common.lobby.*,com.fulcrologic.jxpilot.common.net.LobbyMsg*,com.fulcrologic.jxpilot.server.lobby.*' \
  -DtargetTests='com.fulcrologic.jxpilot.common.lobby.*,com.fulcrologic.jxpilot.common.net.LobbyMsg*,com.fulcrologic.jxpilot.server.lobby.*' \
  -DfailWhenNoMutations=false -Dthreads=4
MAVEN_OPTS= mvn -o -pl client -am test-compile org.pitest:pitest-maven:mutationCoverage \
  -DtargetClasses='com.fulcrologic.jxpilot.client.net.LobbyClient,com.fulcrologic.jxpilot.client.game.hud.LobbyPanel' \
  -DtargetTests='com.fulcrologic.jxpilot.client.net.LobbyClientTest*,com.fulcrologic.jxpilot.client.net.ClientConnectionLobbyTest,com.fulcrologic.jxpilot.client.game.hud.LobbyPanelTest*' \
  -DfailWhenNoMutations=false -Dthreads=4
```

| Classes | Mutants | Killed | Notes |
|---|---|---|---|
| common: `LobbyMsg`, `LobbyListAssembler`, `LobbyKeys`, `LobbyOrder`, `LobbyEntry` | 80 | 76 (95%) | |
| server: `LobbyCatalog`, `LobbyConfig`, `LobbyOptionSources`, `LobbyRegistry`, `RoutedTransport`, `LobbyServer` | 342 | 254 (74%), test strength 93% | 69 mutants have no coverage: they are all in the effectful edges of `LobbyServer` (`start`, `runLoop`, `close`, `requested`, `gameLauncher`, `localAddress(es)`), which `LobbyEndToEndTest` (client module: a real lobby, real games, three real clients) drives and PIT's server-module run does not include |
| client: `LobbyClient`, `LobbyPanel` | 213 | 196 (92%) | |

Surviving mutants, all justified:

- Equivalent: `LobbyKeys.rowOf` bound `ch < 128` (`indexOf` of a larger code is -1 anyway); `LobbyConfig.header` / `multilineDelimiter`
  / `forceOverride` boundary mutants on blank-name and colon positions that give the same text; `LobbyClient.notice` and
  `onJoin` bound at exactly 80 characters (`substring(0, 80)` of an 80 character text is the text);
  `RoutedTransport.poll` drain bound (`>` vs `>=` changes only which of two equivalent loop exits is taken);
  `LobbyServer.routes`/`peers` array growth arithmetic (any growth that holds the channel works);
  `LobbyServer.<init>` `rescanMillis * 1_000_000` (only read by the socket thread's rescan, an edge).
- `LobbyServer.tick`, `Iterator::remove`: the peer is removed and its place freed on the same pass either way; a mutant that
  keeps it is removed again on the next tick.
- `LobbyCatalog` `MAX_MAPS` bound and `.xp`-before-`.map` ordering: would need 2049 map files, or a directory
  listing order the file system does not give; the ordering has its own test that passes for the listing order seen.
- `LobbyPanel.layout` panel height/fit arithmetic at view heights the tests do not use (`fit` for other heights), and the
  `putClipped` bound where a title is exactly as wide as its room (needs a width that is a multiple of the 7 px test font).
