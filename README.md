# JXPilot

A Java (25) + LWJGL/OpenGL reimplementation of [XPilot](https://www.xpilot.org/) 4.5.5,
the classic multiplayer gravity war game. It plays from the original block-based `.xp`
map files without changes. It is **not** network-compatible with the C XPilot client or
server; it uses its own UDP protocol (`docs/PROTOCOL.md`).

> **Independent derivative port.** JXPilot is an independent port of XPilot 4.5.5 to
> the JVM. The original XPilot authors (Bjørn Stabell, Ken Ronny Schouten, Bert Gijsbers,
> Dick Balaska, and the many XPilot contributors) were **not involved** in this project
> and do not endorse it. Please don't send them bug reports about JXPilot; open an issue
> here instead.

Status: work in progress. The map/config parser, protocol, and map renderer/viewer are
done. The server simulation and networked gameplay are being built. See `docs/SPEC.md`
for the plan and milestones.

## Building and running

Requires JDK 25 and Maven.

```bash
mvn install                                   # build + test all modules
mvn -pl client exec:exec                      # map viewer (default.xp)
mvn -pl client exec:exec -Dclient.args="--view-map maps/planetx.xp"
```

## Project layout

- `common/`: map and config parsing, options, wall geometry, ship shapes, network protocol
- `server/`: game simulation and server
- `client/`: LWJGL renderer, HUD, input, networking
- `maps/`: original XPilot maps, unmodified (see `maps/README.md`)
- `docs/`: spec, protocol, testing standard

## License

JXPilot is free software, licensed under the **GNU General Public License, version 2 or
(at your option) any later version**. See [`LICENSE`](LICENSE).

It is a derivative work of XPilot 4.5.5, which is
Copyright © 1991-2002 by Bjørn Stabell, Ken Ronny Schouten, Bert Gijsbers & Dick Balaska,
and is distributed under the same license (GPL v2 or later). The game rules, physics,
map format, and many algorithms were ported from the XPilot C sources and changed in the
process. Every source file carries a notice saying so, and the git history records the
changes.

### Third-party components

| Component | Where | License |
|---|---|---|
| XPilot 4.5.5 maps | `maps/*.xp` | GPL v2 or later (part of the XPilot distribution); see `maps/README.md` |
| Fira Code font | `client/src/main/resources/fonts/` | SIL Open Font License 1.1 (`FiraCode-LICENSE.txt`) |
| LWJGL 3 (Maven dependency, not bundled) | — | BSD 3-Clause |
| JUnit 5, PIT (test-time only) | — | EPL 2.0 / Apache 2.0 |
