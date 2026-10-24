# XPilot client textures

`rock4.xpm` and `ball.xpm` are copied **unmodified** from `lib/textures/` of the XPilot 4.5.5 source
distribution. XPilot is Copyright © 1991-2002 by Bjørn Stabell, Ken Ronny Schouten,
Bert Gijsbers & Dick Balaska, and is distributed under the GNU General Public License,
version 2 or later (see `LICENSE` at the top of the repository and `META-INF/LICENSE` in the jars).
The XPilot authors were not involved in JXPilot.

The C client compiles these two files in (`src/client/texture.c`) as the built-in textures: `rock4.xpm`
for `texturedWalls` and `texturedDecor`, `ball.xpm` for `texturedBalls`, used when `wallTextureFile`,
`decorTextureFile` or `ballTextureFile` is empty or cannot be loaded. The JXPilot client reads these
copies from its classpath for the same purpose.
