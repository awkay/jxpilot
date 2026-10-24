# Server options: sources, precedence and status

Every option of XPilot 4.5.5's `src/server/cmdline.c` table is known to the Java server
(`common/.../config/Opt.java`), plus the JXPilot network options `bind`, `bindWait`, `snapshotDivisor`,
`radarDivisor`, `ipPacketRate`, `ipPacketBurst`, `sessionPacketRate` and `sessionPacketBurst`. An option the simulation does not use yet is still parsed, kept, and sent to clients
in the configuration text. It just has no effect.

## Where values come from

`Server.readOptions` reads the sources in C `Parser`'s order (parser.c:372-460):

1. the command line (`-name value`, `--name value`; booleans `-name` / `+name`), origin COMMAND;
2. the defaults file, origin DEFAULTS (`DefaultsFile`): XPilot's `lib/defaults`, bundled unmodified in
   the jar as `xpilot/defaults`. `--defaults FILE` (C `-defaultsFileName`) reads FILE instead, and
   `--defaults none` reads nothing. A named file that cannot be read is a startup error;
3. the optional positional server config file, also origin DEFAULTS, so it beats lib/defaults;
4. the map file, origin MAP;
5. the `expand` list from the command line.

Precedence is C's `Option_change_node` (option.c:340-460, `Options.allowChange`):

- The command line beats everything.
- A defaults-file value written with `\override:` beats the map.
- Otherwise the map beats the defaults file.
- Each option also has allowed origins (`Opt.F`: MAP, DEF, CMD, PWD). A value from an origin that isn't
  allowed is refused with a warning. For example, `denyHosts` and `logRobots` can't be set from a map.

What lib/defaults changes compared to the cmdline.c defaults:

- `gravity -0.25`, `framesPerSecond 14`, `movingItemProb 0.3`, `maxItemDensity` and the small
  `item*Prob` values;
- `allowNukes no`, `allowClusters no`, `allowModifiers yes`;
- through `expand: SoftWalls`, wall bounce speeds and angles of 180.

Maps that set any of these keep their own value. `default.xp`, for instance, keeps its 60 bounce speeds
and its 12 fps.

## Adding an option (for implementers)

- All C options are already in `Opt`. A JXPilot-only option gets a new `Opt` entry: name, alias,
  default string, `OptionType`, flags. Use `F.ANY|F.VIS` to make it settable from maps, defaults files
  and the command line.
- Read the value once, at world or host construction. Keep it in `Params` (`Params.from`) or in a final
  field of the class that owns the feature. Never look it up per tick. Use `getFrames` for `SEC`
  options and `getPerFrame` for `PER_SEC` options.
- Nothing else is needed: the defaults file, `\override:` and the precedence rules apply to every
  `Opt` automatically.
- Tests that build a `World` straight from `Options` don't get lib/defaults. To match the real server,
  call `DefaultsFile.applyBundled(options)` before parsing the map.

## Information requests

`-help`/`-h`, `-version`/`-v`, `-dump`, `-dumpMan`, `-dumpWindows` and `-dumpFlags` (one or two dashes)
print and exit, as C `Parse_check_info_request` does. `-dump` prints every option with its cmdline.c
default, in defaults-file format.

## Status of every option

"implemented" means the server uses the value. For "read ...; the feature belongs to X", the value is
read but the feature it controls isn't built yet; X is the M2 agent that owns it. The option names
follow C; the alias is the C command-line name.

| Option | Alias | Type | Default (cmdline.c) | Where | Java server status |
|---|---|---|---|---|---|
| `help` |  | void | `0` | 0 | implemented (server-batch2): ServerInfo prints and exits; also -dumpMan, -dumpWindows, -dumpFlags, -h, -v |
| `version` |  | void | `0` | 0 | implemented (server-batch2): ServerInfo prints and exits; also -dumpMan, -dumpWindows, -dumpFlags, -h, -v |
| `dump` |  | void | `0` | 0 | implemented (server-batch2): ServerInfo prints and exits; also -dumpMan, -dumpWindows, -dumpFlags, -h, -v |
| `expand` |  | list | `` | CMD | implemented (Options.finishParsing; also expand: lines in files) |
| `gravity` |  | real | `-0.14` | ANY+VIS | implemented (MapLoader) |
| `shipMass` |  | real | `20.0` | ANY+VIS | implemented (Params) |
| `ballMass` |  | real | `50.0` | ANY+VIS | server-modes (balls/treasures) |
| `minItemMass` |  | real | `1.0` | ANY+VIS | implemented (server-items) |
| `shotMass` |  | real | `0.1` | ANY+VIS | implemented (Params) |
| `shotSpeed` |  | real | `21.0` | ANY+VIS | implemented (Params) |
| `shotLife` |  | int | `60` | ANY+VIS | implemented (Params) |
| `shotHitFuelDrainUsesKineticEnergy` |  | bool | `yes` | DEF+CMD+VIS | implemented (Params) |
| `fireRepeatRate` | fireRepeat | int | `2` | ANY+VIS | implemented (Params) |
| `maxRobots` | robots | int | `4` | ANY+VIS | implemented (Params) |
| `minRobots` |  | int | `-1` | ANY+VIS | implemented (Params) |
| `robotFile` |  | string | `(none)` | CMD+DEF | implemented (SimGameHostFactory; also from a defaults file) |
| `robotsTalk` |  | bool | `false` | ANY+VIS | implemented (Params) |
| `robotsLeave` |  | bool | `true` | ANY+VIS | implemented (Params) |
| `robotLeaveLife` |  | int | `50` | ANY+VIS | implemented (RobotManager) |
| `robotLeaveScore` |  | int | `-90` | ANY+VIS | implemented (RobotManager) |
| `robotLeaveRatio` |  | int | `-5` | ANY+VIS | implemented (RobotManager) |
| `robotTeam` |  | int | `0` | ANY+VIS | implemented (Params) |
| `restrictRobots` |  | bool | `true` | ANY+VIS | implemented (Params) |
| `reserveRobotTeam` |  | bool | `true` | ANY+VIS | implemented (Params) |
| `robotRealName` |  | string | `robot` | ANY+VIS | used in the logRobots line only (no real names on the wire) |
| `robotHostName` |  | string | `xpilot.org` | ANY+VIS | unsupported: the native protocol carries no real or host names (C shows them in the player list); robotRealName is only used in the logRobots line |
| `tankRealName` |  | string | `tank` | ANY+VIS | implemented (server-items): tanks are pseudo-players named "NAME's tank"; the real/host name part is unsupported like robotRealName/robotHostName |
| `tankHostName` |  | string | `tanks.org` | ANY+VIS | implemented (server-items): tanks are pseudo-players named "NAME's tank"; the real/host name part is unsupported like robotRealName/robotHostName |
| `tankScoreDecrement` | tankDecrement | int | `500` | ANY+VIS | implemented (server-items) |
| `turnThrust` | turnFuel | bool | `false` | ANY+VIS | implemented (Params) |
| `selfImmunity` |  | bool | `false` | ANY+VIS | implemented (Params) |
| `defaultShipShape` |  | string | `(NM:Default)...` | ANY+VIS | not implemented: a player or robot without a valid shape gets the built-in standard ship, which is also this option's default |
| `tankShipShape` |  | string | `(NM:fueltank)...` | ANY+VIS | server-items (fuel tanks detached as pseudo-players) |
| `maxPlayerShots` | shots | int | `256` | ANY+VIS | implemented (Params) |
| `shotsGravity` |  | bool | `true` | ANY+VIS | implemented (Params) |
| `idleRun` | rawMode | bool | `false` | CMD+DEF+VIS | implemented (rawMode) |
| `noQuit` |  | bool | `false` | CMD+DEF+VIS | deliberately unsupported: the server never quits when the last human leaves (C quits unless -noQuit). Lead decision wanted; see report |
| `logRobots` |  | bool | `true` | CMD+DEF+VIS | implemented (server-batch2): "NAME (n, robotRealName) starts at startpos B." on the server log |
| `mapWidth` |  | int | `100` | ANY+VIS | implemented (MapLoader) |
| `mapHeight` |  | int | `100` | ANY+VIS | implemented (MapLoader) |
| `mapFileName` | map | string | `(none)` | CMD+DEF | implemented (Server) |
| `mapName` |  | string | `unknown` | ANY+VIS | implemented (SimGameHost, MapLoader) |
| `mapAuthor` |  | string | `anonymous` | ANY+VIS | implemented (SimGameHost, MapLoader) |
| `contactPort` | port | int | `15345` | CMD+DEF+VIS | implemented (Server) |
| `bind` |  | list | `all` | CMD+DEF | implemented (ServerArgs, Server) |
| `bindWait` |  | bool | `false` | CMD+DEF | implemented (Server) |
| `snapshotDivisor` |  | int | `1` | CMD+DEF | implemented (ServerNetConfig) |
| `radarDivisor` |  | int | `3` | CMD+DEF | implemented (ServerNetConfig) |
| `ipPacketRate` |  | real | `400` | CMD+DEF | implemented (ServerNetConfig) |
| `ipPacketBurst` |  | real | `800` | CMD+DEF | implemented (ServerNetConfig) |
| `sessionPacketRate` |  | real | `400` | CMD+DEF | implemented (ServerNetConfig) |
| `sessionPacketBurst` |  | real | `800` | CMD+DEF | implemented (ServerNetConfig) |
| `serverHost` |  | string | `(none)` | CMD+DEF+VIS | implemented (server-batch2): binds to that host when --bind is not given (server.c:163-171) |
| `mapData` |  | string | `(none)` | ANY+VIS | implemented |
| `allowPlayerCrashes` |  | bool | `yes` | ANY+VIS | implemented (Params) |
| `allowPlayerBounces` |  | bool | `yes` | ANY+VIS | implemented (Params) |
| `allowPlayerKilling` | killings | bool | `yes` | ANY+VIS | implemented (Params) |
| `allowShields` | shields | bool | `yes` | ANY+VIS | implemented (Params) |
| `playerStartsShielded` | playerStartShielded | bool | `yes` | ANY+VIS | implemented (Params) |
| `shotsWallBounce` |  | bool | `no` | ANY+VIS | implemented (Params) |
| `ballsWallBounce` |  | bool | `yes` | ANY+VIS | read (Params); the feature belongs to server-modes (balls) |
| `ballCollisions` |  | bool | `no` | ANY+VIS | server-modes (balls/treasures) |
| `ballSparkCollisions` |  | bool | `yes` | ANY+VIS | server-modes (balls/treasures) |
| `minesWallBounce` |  | bool | `no` | ANY+VIS | implemented (server-items) |
| `itemsWallBounce` |  | bool | `yes` | ANY+VIS | implemented (server-items) |
| `missilesWallBounce` |  | bool | `no` | ANY+VIS | implemented (server-items) |
| `sparksWallBounce` |  | bool | `no` | ANY+VIS | implemented (Params) |
| `debrisWallBounce` |  | bool | `no` | ANY+VIS | implemented (Params) |
| `asteroidsWallBounce` |  | bool | `yes` | ANY+VIS | read (Params); the feature belongs to server-modes (asteroids) |
| `cloakedExhaust` |  | bool | `yes` | ANY+VIS | implemented (server-items) |
| `cloakedShield` |  | bool | `yes` | ANY+VIS | implemented (server-items) |
| `maxObjectWallBounceSpeed` | maxObjectBounceSpeed | real | `40` | ANY+VIS | implemented (Params) |
| `maxShieldedWallBounceSpeed` | maxShieldedBounceSpeed | real | `50` | ANY+VIS | implemented (Params) |
| `maxUnshieldedWallBounceSpeed` | maxUnshieldedBounceSpeed | real | `20` | ANY+VIS | implemented (Params) |
| `maxShieldedPlayerWallBounceAngle` | maxShieldedBounceAngle | real | `90` | ANY+VIS | implemented (Params) |
| `maxUnshieldedPlayerWallBounceAngle` | maxUnshieldedBounceAngle | real | `30` | ANY+VIS | implemented (Params) |
| `playerWallBounceBrakeFactor` | playerWallBrake | real | `0.89` | ANY+VIS | implemented (Params) |
| `objectWallBounceBrakeFactor` | objectWallBrake | real | `0.95` | ANY+VIS | implemented (Params) |
| `objectWallBounceLifeFactor` |  | real | `0.80` | ANY+VIS | implemented (Params) |
| `wallBounceFuelDrainMult` | wallBounceDrain | real | `1.0` | ANY+VIS | implemented (Params) |
| `wallBounceDestroyItemProb` |  | real | `0.0` | ANY+VIS | implemented (server-items) |
| `reportToMetaServer` | reportMeta | bool | `yes` | CMD+DEF+VIS | unsupported: no meta server speaks the JXPilot protocol |
| `searchDomainForXPilot` |  | bool | `no` | CMD+DEF+VIS | unsupported: no meta server speaks the JXPilot protocol |
| `denyHosts` |  | string | `` | CMD+DEF+VIS | implemented (server-batch2): CONNECTs from those addresses/networks are ignored (contact.c) |
| `maxClientsPerIP` | maxPerIP | int | `4` | CMD+DEF+VIS | implemented (ServerNetConfig) |
| `limitedVisibility` |  | bool | `no` | ANY+VIS | implemented (server-items) |
| `minVisibilityDistance` | minVisibility | real | `0.0` | ANY+VIS | implemented (server-items) |
| `maxVisibilityDistance` | maxVisibility | real | `0.0` | ANY+VIS | implemented (server-items) |
| `limitedLives` |  | bool | `no` | ANY+VIS | implemented (Params) |
| `worldLives` | lives | int | `0` | ANY+VIS | implemented (Params) |
| `reset` |  | bool | `yes` | ANY+VIS | implemented (Params) |
| `resetOnHuman` | humanReset | int | `0` | ANY+VIS | implemented (Params) |
| `allowAlliances` | alliances | bool | `yes` | ANY+VIS | not implemented, no owner: alliances (alliance.c, the /ally commands and client support) are not in any M2 agent's list |
| `announceAlliances` |  | bool | `no` | ANY+VIS | not implemented, no owner: alliances (alliance.c, the /ally commands and client support) are not in any M2 agent's list |
| `teamPlay` | teams | bool | `no` | ANY+VIS | implemented (MapLoader) |
| `teamFuel` |  | bool | `no` | ANY+VIS | implemented (Params) |
| `teamCannons` |  | bool | `no` | ANY+VIS | implemented (Params, MapLoader) |
| `cannonSmartness` |  | int | `1` | ANY+VIS | implemented (Params) |
| `cannonsUseItems` | cannonsPickupItems | bool | `no` | ANY+VIS | implemented (server-items) |
| `cannonsDefend` |  | bool | `yes` | ANY+VIS | implemented (server-items) |
| `cannonFlak` | cannonAAA | bool | `yes` | ANY+VIS | implemented (Params) |
| `cannonDeadTime` |  | sec | `72` | ANY+VIS | implemented (Params) |
| `keepShots` |  | bool | `no` | ANY+VIS | implemented (Params) |
| `teamAssign` |  | bool | `yes` | ANY+VIS | implemented (Params) |
| `teamImmunity` |  | bool | `yes` | ANY+VIS | implemented (Params) |
| `teamShareScore` |  | bool | `no` | ANY+VIS | implemented (Params) |
| `ecmsReprogramMines` |  | bool | `yes` | ANY+VIS | implemented (server-items) |
| `ecmsReprogramRobots` |  | bool | `yes` | ANY+VIS | implemented (server-items) |
| `targetKillTeam` |  | bool | `no` | ANY+VIS | implemented (Params) |
| `targetTeamCollision` | targetCollision | bool | `yes` | ANY+VIS | implemented (Params) |
| `targetSync` |  | bool | `no` | ANY+VIS | implemented (Params) |
| `targetDeadTime` |  | sec | `60` | ANY+VIS | implemented (Params) |
| `treasureKillTeam` |  | bool | `no` | ANY+VIS | server-modes (balls/treasures) |
| `captureTheFlag` | ctf | bool | `no` | ANY+VIS | server-modes (balls/treasures) |
| `treasureCollisionDestroys` | treasureCollisionDestroy | bool | `yes` | ANY+VIS | server-modes (balls/treasures) |
| `ballConnectorSpringConstant` |  | real | `1500.0` | ANY+VIS | server-modes (balls/treasures) |
| `ballConnectorDamping` |  | real | `2.0` | ANY+VIS | server-modes (balls/treasures) |
| `maxBallConnectorRatio` |  | real | `0.30` | ANY+VIS | server-modes (balls/treasures) |
| `ballConnectorLength` |  | real | `120` | ANY+VIS | read by the robot AI only; the feature belongs to server-modes |
| `connectorIsString` |  | bool | `no` | ANY+VIS | server-modes (balls/treasures) |
| `treasureCollisionMayKill` | treasureUnshieldedCollisionKills | bool | `no` | ANY+VIS | read by the robot AI only; the feature belongs to server-modes |
| `wreckageCollisionMayKill` | wreckageUnshieldedCollisionKills | bool | `no` | ANY+VIS | implemented (server-batch2): wreckage kills unshielded ships without armor, or takes one armor (collision.c:1099-1138); robots avoid it |
| `asteroidCollisionMayKill` | asteroidUnshieldedCollisionKills | bool | `yes` | ANY+VIS | read by the robot AI only; the feature belongs to server-modes |
| `ignore20MaxFPS` |  | bool | `true` | CMD+DEF+VIS | not applicable: the native protocol has no client maxFPS request; snapshots run at the map's framesPerSecond |
| `timing` | race | bool | `no` | ANY+VIS | implemented (MapLoader) |
| `ballrace` |  | bool | `false` | ANY+VIS | server-modes (race) |
| `ballraceConnected` |  | bool | `false` | ANY+VIS | server-modes (race) |
| `edgeWrap` |  | bool | `no` | ANY+VIS | implemented (MapLoader) |
| `edgeBounce` |  | bool | `yes` | ANY+VIS | implemented (Params) |
| `extraBorder` |  | bool | `no` | ANY+VIS | implemented (MapLoader) |
| `gravityPoint` |  | position | `0,0` | ANY+VIS | implemented (MapLoader) |
| `gravityAngle` |  | real | `90` | ANY+VIS | implemented (MapLoader) |
| `gravityPointSource` |  | bool | `false` | ANY+VIS | implemented (MapLoader) |
| `gravityClockwise` |  | bool | `false` | ANY+VIS | implemented (MapLoader) |
| `gravityAnticlockwise` |  | bool | `false` | ANY+VIS | implemented (MapLoader) |
| `gravityVisible` |  | bool | `true` | ANY+VIS | implemented (server-batch2): hidden blocks are blanked in the map sent to clients (netserver.c:225-262) |
| `wormholeVisible` |  | bool | `true` | ANY+VIS | implemented (server-batch2) for the client map; robots read it too |
| `itemConcentratorVisible` |  | bool | `true` | ANY+VIS | implemented (server-batch2) for the client map |
| `asteroidConcentratorVisible` |  | bool | `true` | ANY+VIS | implemented (server-batch2) for the client map |
| `wormTime` |  | sec | `0` | ANY+VIS | implemented (Params) |
| `defaultsFileName` | defaults | string | `(none)` | CMD | implemented (server-batch2): --defaults FILE|none; default is the bundled lib/defaults |
| `passwordFileName` |  | string | `(none)` | CMD+DEF | unsupported: no operator commands yet (C command.c) |
| `motdFileName` | motd | string | `(none)` | CMD+DEF | not implemented: the native protocol has no MOTD request; needs a protocol and client change |
| `scoreTableFileName` | scoretable | string | `(none)` | CMD+DEF | accepted, no effect: C 4.5.5 declares it but never reads it |
| `adminMessageFileName` | adminMessage | string | `(none)` | CMD+DEF | implemented (server-batch2): "god:" chat appended to the file (no default file: C uses LIBDIR/log) |
| `adminMessageFileSizeLimit` | adminMessageLimit | int | `20202` | CMD+DEF | implemented (server-batch2) |
| `framesPerSecond` | FPS | int | `14` | ANY+VIS | implemented (one tick per C frame) |
| `allowSmartMissiles` | allowSmarts | bool | `True` | ANY+VIS | implemented (server-items) |
| `allowHeatSeekers` | allowHeats | bool | `True` | ANY+VIS | implemented (server-items) |
| `allowTorpedoes` | allowTorps | bool | `True` | ANY+VIS | implemented (server-items) |
| `allowNukes` | nukes | bool | `False` | ANY+VIS | implemented (server-items) |
| `allowClusters` | clusters | bool | `False` | ANY+VIS | implemented (server-items) |
| `allowModifiers` | modifiers | bool | `False` | ANY+VIS | implemented (server-items) |
| `allowLaserModifiers` | lasermodifiers | bool | `False` | ANY+VIS | implemented (server-items) |
| `allowShipShapes` | ShipShapes | bool | `True` | ANY+VIS | implemented (RobotManager) |
| `playersOnRadar` | playersRadar | bool | `True` | ANY+VIS | implemented (server-batch2): off hides other teams from the radar (frame.c:1152-1170) |
| `missilesOnRadar` | missilesRadar | bool | `True` | ANY+VIS | implemented (server-items) |
| `minesOnRadar` | minesRadar | bool | `False` | ANY+VIS | implemented (server-items) |
| `nukesOnRadar` | nukesRadar | bool | `True` | ANY+VIS | implemented (server-items) |
| `treasuresOnRadar` | treasuresRadar | bool | `False` | ANY+VIS | server-modes (balls/treasures) |
| `asteroidsOnRadar` | asteroidsRadar | bool | `False` | ANY+VIS | server-modes (asteroids) |
| `distinguishMissiles` |  | bool | `True` | ANY+VIS | implemented (server-items) |
| `maxMissilesPerPack` |  | int | `4` | ANY+VIS | implemented (server-items) |
| `maxMinesPerPack` |  | int | `2` | ANY+VIS | implemented (server-items) |
| `identifyMines` |  | bool | `True` | ANY+VIS | implemented (server-items) |
| `shieldedItemPickup` | shieldItem | bool | `False` | ANY+VIS | implemented (server-items) |
| `shieldedMining` | shieldMine | bool | `False` | ANY+VIS | implemented (server-items) |
| `laserIsStunGun` | stunGun | bool | `False` | ANY+VIS | implemented (server-items) |
| `nukeMinSmarts` |  | int | `7` | ANY+VIS | implemented (server-items) |
| `nukeMinMines` |  | int | `4` | ANY+VIS | implemented (server-items) |
| `nukeClusterDamage` |  | real | `1.0` | ANY+VIS | accepted, no effect: C 4.5.5 declares it but never reads it |
| `mineFuseTime` |  | sec | `0.0` | ANY+VIS | implemented (server-items) |
| `mineLife` |  | int | `0` | ANY+VIS | implemented (server-items) |
| `minMineSpeed` |  | real | `0` | ANY+VIS | implemented (server-items) |
| `missileLife` |  | int | `0` | ANY+VIS | implemented (server-items) |
| `baseMineRange` |  | int | `0` | ANY+VIS | implemented (server-items) |
| `mineShotDetonateDistance` |  | int | `0` | ANY+VIS | implemented (server-items) |
| `shotKillScoreMult` |  | real | `1.0` | ANY+VIS | implemented (Params) |
| `torpedoKillScoreMult` |  | real | `1.0` | ANY+VIS | implemented (server-items) |
| `smartKillScoreMult` |  | real | `1.0` | ANY+VIS | implemented (server-items) |
| `heatKillScoreMult` |  | real | `1.0` | ANY+VIS | implemented (server-items) |
| `clusterKillScoreMult` |  | real | `1.0` | ANY+VIS | implemented (Params) |
| `laserKillScoreMult` |  | real | `1.0` | ANY+VIS | implemented (server-items) |
| `tankKillScoreMult` |  | real | `0.44` | ANY+VIS | implemented (server-items) |
| `runoverKillScoreMult` |  | real | `0.33` | ANY+VIS | implemented (Params) |
| `ballKillScoreMult` |  | real | `1.0` | ANY+VIS | server-modes (balls/treasures) |
| `explosionKillScoreMult` |  | real | `0.33` | ANY+VIS | implemented (Params) |
| `shoveKillScoreMult` |  | real | `0.5` | ANY+VIS | implemented (Params) |
| `crashScoreMult` |  | real | `0.33` | ANY+VIS | implemented (Params) |
| `mineScoreMult` |  | real | `0.17` | ANY+VIS | implemented (server-items) |
| `selfKillScoreMult` |  | real | `0.5` | ANY+VIS | implemented (Params) |
| `selfDestructScoreMult` |  | real | `0` | ANY+VIS | implemented (Params) |
| `unownedKillScoreMult` |  | real | `0.5` | ANY+VIS | implemented (Params) |
| `asteroidPoints` |  | real | `1.0` | ANY+VIS | server-modes (asteroids) |
| `cannonPoints` |  | real | `1.0` | ANY+VIS | implemented (Params) |
| `asteroidMaxScore` |  | real | `100.0` | ANY+VIS | server-modes (asteroids) |
| `cannonMaxScore` |  | real | `100.0` | ANY+VIS | implemented (Params) |
| `movingItemProb` |  | real | `0.2` | ANY+VIS | implemented (server-items) |
| `randomItemProb` |  | real | `0.0` | ANY+VIS | implemented (server-items) |
| `dropItemOnKillProb` |  | real | `0.5` | ANY+VIS | implemented (server-items) |
| `detonateItemOnKillProb` |  | real | `0.5` | ANY+VIS | implemented (server-items) |
| `destroyItemInCollisionProb` |  | real | `0.0` | ANY+VIS | implemented (server-items) |
| `asteroidItemProb` |  | real | `0.0` | ANY+VIS | server-modes (asteroids) |
| `asteroidMaxItems` |  | int | `0` | ANY+VIS | server-modes (asteroids) |
| `itemProbMult` | itemProbFact | real | `1.0` | ANY+VIS | implemented (server-items) |
| `cannonItemProbMult` |  | real | `1.0` | ANY+VIS | implemented (server-items) |
| `maxItemDensity` |  | real | `0.00012` | ANY+VIS | implemented (server-items) |
| `asteroidProb` |  | real | `5e-7` | ANY+VIS | server-modes (asteroids) |
| `maxAsteroidDensity` |  | real | `0` | ANY+VIS | server-modes (asteroids) |
| `itemConcentratorRadius` | itemConcentratorRange | int | `10` | ANY+VIS | implemented (server-items) |
| `itemConcentratorProb` |  | real | `1.0` | ANY+VIS | implemented (server-items): random item spawn and cannon item probabilities (C Set_world_items) |
| `asteroidConcentratorRadius` | asteroidConcentratorRange | int | `10` | ANY+VIS | server-modes (asteroids) |
| `asteroidConcentratorProb` |  | real | `1.0` | ANY+VIS | server-modes (asteroids) |
| `rogueHeatProb` |  | real | `1.0` | ANY+VIS | implemented (server-items) |
| `rogueMineProb` |  | real | `1.0` | ANY+VIS | implemented (server-items) |
| `itemEnergyPackProb` |  | real | `1e-9` | ANY+VIS | implemented (server-items): random item spawn and cannon item probabilities (C Set_world_items) |
| `itemTankProb` |  | real | `1e-9` | ANY+VIS | implemented (server-items): random item spawn and cannon item probabilities (C Set_world_items) |
| `itemECMProb` |  | real | `1e-9` | ANY+VIS | implemented (server-items): random item spawn and cannon item probabilities (C Set_world_items) |
| `itemArmorProb` |  | real | `1e-9` | ANY+VIS | implemented (server-items): random item spawn and cannon item probabilities (C Set_world_items) |
| `itemMineProb` |  | real | `1e-9` | ANY+VIS | implemented (server-items): random item spawn and cannon item probabilities (C Set_world_items) |
| `itemMissileProb` |  | real | `1e-9` | ANY+VIS | implemented (server-items): random item spawn and cannon item probabilities (C Set_world_items) |
| `itemCloakProb` |  | real | `1e-9` | ANY+VIS | implemented (server-items): random item spawn and cannon item probabilities (C Set_world_items) |
| `itemSensorProb` |  | real | `1e-9` | ANY+VIS | implemented (server-items): random item spawn and cannon item probabilities (C Set_world_items) |
| `itemWideangleProb` |  | real | `1e-9` | ANY+VIS | implemented (server-items): random item spawn and cannon item probabilities (C Set_world_items) |
| `itemRearshotProb` |  | real | `1e-9` | ANY+VIS | implemented (server-items): random item spawn and cannon item probabilities (C Set_world_items) |
| `itemAfterburnerProb` |  | real | `1e-9` | ANY+VIS | implemented (server-items): random item spawn and cannon item probabilities (C Set_world_items) |
| `itemTransporterProb` |  | real | `1e-9` | ANY+VIS | implemented (server-items): random item spawn and cannon item probabilities (C Set_world_items) |
| `itemMirrorProb` |  | real | `1e-9` | ANY+VIS | implemented (server-items): random item spawn and cannon item probabilities (C Set_world_items) |
| `itemDeflectorProb` |  | real | `1e-9` | ANY+VIS | implemented (server-items): random item spawn and cannon item probabilities (C Set_world_items) |
| `itemHyperJumpProb` |  | real | `1e-9` | ANY+VIS | implemented (server-items): random item spawn and cannon item probabilities (C Set_world_items) |
| `itemPhasingProb` |  | real | `1e-9` | ANY+VIS | implemented (server-items): random item spawn and cannon item probabilities (C Set_world_items) |
| `itemLaserProb` |  | real | `1e-9` | ANY+VIS | implemented (server-items): random item spawn and cannon item probabilities (C Set_world_items) |
| `itemEmergencyThrustProb` |  | real | `1e-9` | ANY+VIS | implemented (server-items): random item spawn and cannon item probabilities (C Set_world_items) |
| `itemTractorBeamProb` |  | real | `1e-9` | ANY+VIS | implemented (server-items): random item spawn and cannon item probabilities (C Set_world_items) |
| `itemAutopilotProb` |  | real | `1e-9` | ANY+VIS | implemented (server-items): random item spawn and cannon item probabilities (C Set_world_items) |
| `itemEmergencyShieldProb` |  | real | `1e-9` | ANY+VIS | implemented (server-items): random item spawn and cannon item probabilities (C Set_world_items) |
| `initialFuel` |  | int | `1000` | ANY+VIS | implemented (Params) |
| `initialTanks` |  | int | `0` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `initialECMs` |  | int | `0` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `initialArmor` | initialArmors | int | `0` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `initialMines` |  | int | `0` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `initialMissiles` |  | int | `0` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `initialCloaks` |  | int | `0` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `initialSensors` |  | int | `0` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `initialWideangles` |  | int | `0` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `initialRearshots` |  | int | `0` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `initialAfterburners` |  | int | `0` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `initialTransporters` |  | int | `0` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `initialMirrors` |  | int | `0` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `initialDeflectors` |  | int | `0` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `initialHyperJumps` |  | int | `0` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `initialPhasings` |  | int | `0` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `initialLasers` |  | int | `0` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `initialEmergencyThrusts` |  | int | `0` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `initialTractorBeams` |  | int | `0` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `initialAutopilots` |  | int | `0` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `initialEmergencyShields` |  | int | `0` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `maxFuel` |  | int | `10000` | ANY+VIS | implemented (Params) |
| `maxTanks` |  | int | `8` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `maxECMs` |  | int | `10` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `maxMines` |  | int | `10` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `maxMissiles` |  | int | `10` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `maxCloaks` |  | int | `10` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `maxSensors` |  | int | `10` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `maxWideangles` |  | int | `10` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `maxRearshots` |  | int | `10` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `maxAfterburners` |  | int | `10` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `maxTransporters` |  | int | `10` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `maxArmor` | maxArmors | int | `10` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `maxMirrors` |  | int | `10` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `maxDeflectors` |  | int | `10` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `maxPhasings` |  | int | `10` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `maxHyperJumps` |  | int | `10` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `maxEmergencyThrusts` |  | int | `10` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `maxLasers` |  | int | `5` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `maxTractorBeams` |  | int | `4` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `maxAutopilots` |  | int | `10` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `maxEmergencyShields` |  | int | `10` | ANY+VIS | implemented (server-items): starting items and the item limit (C World.items[].initial / limit) |
| `gameDuration` | time | real | `0.0` | ANY+VIS | implemented (Params) |
| `allowViewing` |  | bool | `false` | ANY+VIS | implemented (server-items): a paused player with a lock sees through any locked player (Locks.viewOf), and paused / dead players get every frame instead of every 4th / 2nd (SimGameHost.snapshotDue) |
| `friction` |  | real | `0.0` | ANY+VIS | implemented (Params) |
| `blockFriction` |  | real | `0.0` | ANY+VIS | implemented (Params) |
| `blockFrictionVisible` |  | bool | `true` | ANY+VIS | implemented (server-batch2): friction is sent as filled decor, or blank when off |
| `coriolis` |  | int | `0` | ANY+VIS | implemented (Params) |
| `checkpointRadius` |  | real | `6.0` | ANY+VIS | server-modes (race) |
| `raceLaps` |  | int | `3` | ANY+VIS | server-modes (race) |
| `lockOtherTeam` |  | bool | `true` | ANY+VIS | implemented (server-items): Locks.lockAllowed (C Player_lock_allowed) |
| `loseItemDestroys` |  | bool | `false` | ANY+VIS | implemented (server-items) |
| `useWreckage` |  | bool | `true` | ANY+VIS | implemented (server-batch2): ship and cannon explosions leave wreckage (ship.c Make_wreckage), golden-traced against C |
| `maxOffensiveItems` |  | int | `100` | ANY+VIS | implemented (server-items) |
| `maxDefensiveItems` |  | int | `100` | ANY+VIS | implemented (server-items) |
| `roundDelay` | roundDelaySeconds | int | `0` | ANY+VIS | implemented (Params) |
| `maxRoundTime` |  | int | `0` | ANY+VIS | implemented (Params) |
| `roundsToPlay` |  | int | `0` | ANY+VIS | implemented (Params) |
| `maxVisibleObject` | maxVisibleObjects | int | `1000` | ANY+VIS | not implemented: the snapshot byte budget (nearest first) limits objects instead |
| `pLockServer` |  | bool | `false` | CMD+DEF+VIS | not applicable: JVM server (no mlock, no signal timer) |
| `timerResolution` |  | int | `0` | CMD+DEF+VIS | not applicable: JVM server (no mlock, no signal timer) |
| `password` |  | string | `(none)` | CMD+DEF+PWD | unsupported: no operator commands yet (C command.c) |
| `clientPortStart` |  | int | `0` | CMD+DEF+VIS | not applicable: one UDP socket per bound address serves every client |
| `clientPortEnd` |  | int | `0` | CMD+DEF+VIS | not applicable: one UDP socket per bound address serves every client |
| `maxPauseTime` |  | sec | `3600` | ANY+VIS | implemented (Params) |
