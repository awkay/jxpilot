JXPilot @VERSION@: an independent Java reimplementation of XPilot 4.5.5. The
[user guide](https://github.com/awkay/jxpilot/blob/main/docs/guide/01-introduction.adoc#status) says what
works and where JXPilot differs from XPilot.

> JXPilot is an independent derivative port. The original XPilot authors were not involved and do not
> endorse it; please report problems here, not to them.

### Downloads

| File | What it is |
|---|---|
| `jxpilot-client-@VERSION@.jar` | Client for Windows, macOS and Linux (x64 and arm64). Needs Java 25. Play against robots: `java -jar jxpilot-client-@VERSION@.jar --local --map default` (the 19 maps are built in; `--list-maps` lists them); join a server: `--connect HOST` (or `xp://HOST` for an original XPilot server) |
| `jxpilot-server-@VERSION@.jar` | Headless server, any OS with Java 25: `java -jar jxpilot-server-@VERSION@.jar --map default -noQuit` |
| `jxpilot-maps-@VERSION@.zip` | The 19 original XPilot maps (unmodified, GPL), for editing or other XPilot programs. Not needed to play: the jars and apps have them built in |
| `JXPilot-@VERSION@-<platform>.zip` / `.tar.gz` | Client app with its own Java runtime and the built-in maps: no Java install needed. Opened without arguments it starts a game against robots |
| `jxpilot-guide-@VERSION@.zip` | The user guide as HTML |
| `openal-soft-source-<commit>.tar.gz` | Source of OpenAL Soft, the LGPL sound library inside the client (see `META-INF/NOTICE.txt` in the client jar) |
| `SHA256SUMS.txt` | Checksums: `sha256sum -c SHA256SUMS.txt --ignore-missing` |

macOS: the apps are not signed. After unzipping, run `xattr -dr com.apple.quarantine JXPilot.app` once, or
macOS will say the app is damaged. See the guide's Quick start for details.

JXPilot is free software under the GNU GPL v2 or later. The source for this release is attached below.
