# XPilot server defaults file

`defaults` is copied **unmodified** from `lib/defaults` of the XPilot 4.5.5 source
distribution. XPilot is Copyright © 1991-2002 by Bjørn Stabell, Ken Ronny Schouten,
Bert Gijsbers & Dick Balaska, and is distributed under the GNU General Public License,
version 2 or later (see `LICENSE` at the top of the repository and `META-INF/LICENSE` in the jars).
The XPilot authors were not involved in JXPilot.

The C server installs this file as `LIBDIR/defaults` and reads it at every start
(`src/server/parser.c` `Parser`, option `defaultsFileName`). The JXPilot server reads
this copy from its classpath in the same place in the same order, unless
`--defaults FILE` names another file or `--defaults none` turns it off. That is why a map without
its own `gravity` plays at -0.25 and, through `expand: SoftWalls`, with wall bounce speeds and
angles of 180.

The file must stay byte-for-byte identical to the original. To change settings, copy it, edit the
copy, and start the server with `--defaults <your copy>`.

`server-help.txt` holds the help text of every server option, extracted unchanged (line breaks included)
from the option table in XPilot 4.5.5's `src/server/cmdline.c`, same license and authors. `-help` prints
it as the C server does.
