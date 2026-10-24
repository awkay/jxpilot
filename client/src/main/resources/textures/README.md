# XPilot client textures

Every file here is copied **unmodified** from `lib/textures/` of the XPilot 4.5.5 source
distribution. XPilot is Copyright © 1991-2002 by Bjørn Stabell, Ken Ronny Schouten,
Bert Gijsbers & Dick Balaska, and is distributed under the GNU General Public License,
version 2 or later (see `LICENSE` at the top of the repository and `META-INF/LICENSE` in the jars).
The XPilot authors were not involved in JXPilot.

- `rock4.xpm` and `ball.xpm`: the C client compiles these two in (`src/client/texture.c`) as the built-in
  textures: `rock4.xpm` for `texturedWalls` and `texturedDecor`, `ball.xpm` for `texturedBalls`, used when
  `wallTextureFile`, `decorTextureFile` or `ballTextureFile` is empty or cannot be loaded.
- The `*.ppm` files: the block bitmaps the C client draws with `texturedObjects` (`src/client/blockbitmaps.c`
  `xp_pixmaps`): walls, cannons, bases, fuel, mines, shots, ships, items, meters and the score window's
  background and logo.

The JXPilot client reads these copies from its classpath for the same purposes.
