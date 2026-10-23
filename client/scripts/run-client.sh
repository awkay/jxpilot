#!/bin/bash
# Run the JXPilot client from this checkout's compiled classes (build first with
#   MAVEN_OPTS= mvn -q -o -pl client -am test-compile
# from the repository root). All arguments go to ClientMain, e.g.
#   client/scripts/run-client.sh --connect xp://127.0.0.1:15345 --name Pilot
#   client/scripts/run-client.sh --connect 127.0.0.1:15345
#   client/scripts/run-client.sh --view-map maps/default.xp --demo
# MAIN=com.fulcrologic.jxpilot.client.app.DevRun selects the scripted-screenshot dev harness instead.
set -eu
ROOT="$(cd "$(dirname "$0")/../.." > /dev/null && pwd)"
M2="${M2_REPO:-$HOME/.m2/repository}"
V=3.3.6
case "$(uname -s)-$(uname -m)" in
    Darwin-arm64) NAT=natives-macos-arm64; JOPT=-XstartOnFirstThread;;
    Darwin-*) NAT=natives-macos; JOPT=-XstartOnFirstThread;;
    Linux-*) NAT=natives-linux; JOPT=;;
    *) NAT=natives-windows; JOPT=;;
esac
CP="$ROOT/client/target/classes:$ROOT/client/target/test-classes:$ROOT/common/target/classes:$ROOT/server/target/classes"
for a in lwjgl lwjgl-glfw lwjgl-opengl lwjgl-stb; do
    CP="$CP:$M2/org/lwjgl/$a/$V/$a-$V.jar:$M2/org/lwjgl/$a/$V/$a-$V-$NAT.jar"
done
cd "$ROOT"
exec java $JOPT --enable-native-access=ALL-UNNAMED -XX:+UseZGC -Xms64m -XX:SoftMaxHeapSize=128m \
    -cp "$CP" "${MAIN:-com.fulcrologic.jxpilot.client.ClientMain}" "$@"
