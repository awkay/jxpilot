JXPilot @VERSION@: an independent Java reimplementation of XPilot 4.5.5. **Work in progress**: see the
[user guide](https://github.com/awkay/jxpilot/blob/main/docs/guide/01-introduction.adoc#status) for what
works today.

> JXPilot is an independent derivative port. The original XPilot authors were not involved and do not
> endorse it; please report problems here, not to them.

### Downloads

| File | What it is |
|---|---|
| `jxpilot-client-@VERSION@.jar` | Client for Windows, macOS and Linux (x64 and arm64). Needs Java 25. Play against robots: `java -jar jxpilot-client-@VERSION@.jar --local --map maps/default.xp`; join a server: `--connect HOST` (or `xp://HOST` for an original XPilot server) |
| `jxpilot-server-@VERSION@.jar` | Headless server, any OS with Java 25: `java -jar jxpilot-server-@VERSION@.jar --map maps/default.xp` |
| `jxpilot-maps-@VERSION@.zip` | The 19 original XPilot maps (unmodified, GPL) |
| `JXPilot-@VERSION@-<platform>.zip` / `.tar.gz` | Client app with its own Java runtime and the maps: no Java install needed. Opened without arguments it starts a game against robots |
| `jxpilot-guide-@VERSION@.zip` | The user guide as HTML |
| `openal-soft-source-<commit>.tar.gz` | Source of OpenAL Soft, the LGPL sound library inside the client (see `META-INF/NOTICE.txt` in the client jar) |
| `SHA256SUMS.txt` | Checksums: `sha256sum -c SHA256SUMS.txt --ignore-missing` |

macOS: the apps are not signed. After unzipping, run `xattr -dr com.apple.quarantine JXPilot.app` once, or
macOS will say the app is damaged. See the guide's Quick start for details.

JXPilot is free software under the GNU GPL v2 or later. The source for this release is attached below.
