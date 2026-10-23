/*
 * JXPilot, a Java reimplementation of XPilot, a multiplayer gravity war game.
 * Copyright (C) 2026 Tony Kay and the JXPilot contributors.
 *
 * Derived from XPilot 4.5.5, Copyright (C) 1991-2002 by Bjørn Stabell,
 * Ken Ronny Schouten, Bert Gijsbers and Dick Balaska. JXPilot is an
 * independent derivative port: the original XPilot authors were not involved
 * in it and do not endorse it. Ported to Java and modified starting in 2026;
 * see the git history for the changes.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see <https://www.gnu.org/licenses/>.
 */
package com.fulcrologic.jxpilot.client.e2e;

import com.fulcrologic.jxpilot.client.game.Players;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.server.host.SimGameHost;
import com.fulcrologic.jxpilot.server.host.SimGameHostFactory;
import com.fulcrologic.jxpilot.server.net.NetIf;
import com.fulcrologic.jxpilot.server.net.Server;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole game end to end on the Java server: a real {@link Server} (ServerNet + SimGameHost + Simulation +
 * robots) on loopback UDP, and the real client stack ({@code GameConnections.open} → {@code ClientConnection}
 * → {@code ClientGame}) driven like a player, in real time.
 */
@DisplayName("Java server end to end")
class JavaServerEndToEndTest {
    static final long SEC = 1_000_000_000L;
    static final long THRUST = ControlKey.THRUST.mask();
    static final long FIRE = ControlKey.FIRE_SHOT.mask();
    static final long SHIELD = ControlKey.SHIELD.mask();
    static final long LEFT = ControlKey.TURN_LEFT.mask();

    /** A server on loopback with the real game host, timed. */
    static final class Running implements AutoCloseable {
        final Server server;
        final int port;
        final AtomicReference<TimingHost> host = new AtomicReference<>();
        final List<String> log = Collections.synchronizedList(new ArrayList<>());

        Running(String map, String... options) throws IOException {
            InetAddress lo = InetAddress.ofLiteral("127.0.0.1");
            port = freePort(lo);
            Server.Settings s = new Server.Settings();
            s.interfaces = () -> List.of(new NetIf("lo0", true, true, List.of(lo)));
            s.resolver = h -> List.of();
            s.log = log::add;
            SimGameHostFactory real = new SimGameHostFactory(() -> 4242);
            s.factory = (o, m) -> {
                TimingHost t = new TimingHost(real.create(o, m), 20_000);
                host.set(t);
                return t;
            };
            List<String> args = new ArrayList<>(List.of("--map", map, "--port", Integer.toString(port), "--bind",
                "127.0.0.1"));
            args.addAll(Arrays.asList(options));
            server = Server.start(args.toArray(String[]::new), s);
        }

        SimGameHost sim() {
            return (SimGameHost) host.get().host;
        }

        @Override
        public void close() throws InterruptedException {
            server.close();
        }
    }

    static int freePort(InetAddress a) throws IOException {
        try (DatagramChannel ch = DatagramChannel.open()) {
            ch.bind(new InetSocketAddress(a, 0));
            return ((InetSocketAddress) ch.getLocalAddress()).getPort();
        }
    }

    /** Sleep to the next frame boundary of a fixed cadence (real time). */
    static long pace(long next) throws InterruptedException {
        long now = System.nanoTime();
        while (now < next) {
            long ms = (next - now) / 1_000_000L;
            if (ms > 1) {
                Thread.sleep(ms - 1);
            } else {
                Thread.onSpinWait();
            }
            now = System.nanoTime();
        }
        return now;
    }

    @Test
    @Timeout(value = 150, unit = TimeUnit.SECONDS)
    @DisplayName("a player joins doggy.xp with 8 robots, flies, chats, fights and scores for 60+ s; a second player"
        + " joins and leaves; nothing is malformed and the server ticks steadily")
    void fullGame() throws Exception {
        Path map = Path.of("..", "maps", "doggy.xp");
        Running srv = new Running(map.toString(), "-maxRobots", "8", "-minRobots", "8");
        E2eClient a = new E2eClient(srv.port, "Tester", 11);
        E2eClient b = null;
        int bId = -1;
        int bBase = -1;
        boolean bJoined = false;
        boolean bLeft = false;
        long t0 = System.nanoTime();
        a.start(t0);
        long spawn = -1;
        float restX = 0;
        float restY = 0;
        float thrustVx = 0;
        float thrustVy = 0;
        float thrustX = 0;
        float thrustY = 0;
        float restSpeed = -1;
        boolean chatted = false;
        long next = t0;
        long end = Long.MAX_VALUE;
        long frame = 0;
        try {
            while (true) {
                long now = pace(next);
                next += 8_000_000L;
                frame++;
                if (spawn < 0 && a.playing() && a.game.world().hasSelf()) {
                    spawn = now;
                    end = spawn + 64 * SEC;
                }
                long t = spawn < 0 ? -1 : now - spawn;
                long keys = 0;
                if (t >= SEC && t < SEC + 600_000_000L) {
                    keys = THRUST;
                } else if (t >= 3 * SEC && t < 3 * SEC + 150_000_000L) {
                    keys = SHIELD;
                } else if (t >= 4 * SEC) {
                    long phase = (t / 100_000_000L) % 30;
                    keys = THRUST | (phase % 3 == 0 ? FIRE : 0) | (phase < 4 ? LEFT : 0);
                }
                a.step(now, keys);
                if (t >= 950_000_000L && restSpeed < 0) {
                    restX = a.game.world().selfX();
                    restY = a.game.world().selfY();
                    restSpeed = (float) Math.hypot(a.game.world().selfVx(), a.game.world().selfVy());
                }
                if (t >= SEC + 700_000_000L && thrustX == 0 && thrustY == 0) {
                    thrustX = a.game.world().selfX();
                    thrustY = a.game.world().selfY();
                    thrustVx = a.game.world().selfVx();
                    thrustVy = a.game.world().selfVy();
                }
                if (t >= 2 * SEC && !chatted) {
                    a.chat("hello from the e2e test", now);
                    chatted = true;
                }
                if (t >= 8 * SEC && b == null) {
                    b = new E2eClient(srv.port, "Visitor", 12);
                    b.start(now);
                }
                if (b != null && !bLeft) {
                    b.step(now, 0);
                    if (!bJoined && b.playing() && b.game.world().hasSelf()) {
                        bJoined = true;
                        bId = b.selfId();
                    }
                    if (bJoined && t >= 18 * SEC) {
                        bBase = baseOf(b);
                        b.conn.disconnect(now);
                        bLeft = true;
                    }
                }
                if (now >= end || now - t0 > 120 * SEC) {
                    break;
                }
            }
            a.conn.disconnect(System.nanoTime());
        } finally {
            a.close();
            if (b != null) {
                b.close();
            }
            srv.close();
        }
        TimingHost timing = srv.host.get();
        World w = srv.sim().simulation().world;
        int selfId = a.selfId();
        long robotJoins = a.joins.stream().filter(j -> (j.flags() & Msg.PlayerJoin.FLAG_ROBOT) != 0)
            .map(E2eClient.Join::id).distinct().count();
        long kills = a.gameEvents.stream().filter(e -> e.kind() == Msg.GameEvent.KILL).count();
        long ownScoreTexts = a.scoreObjects.stream().filter(s -> !s.text().isEmpty()).count();
        int robotsInModel = 0;
        Players pl = a.game.players();
        for (int i = 0; i < pl.count(); i++) {
            if ((pl.flags(pl.slotAt(i)) & Msg.PlayerJoin.FLAG_ROBOT) != 0) {
                robotsInModel++;
            }
        }
        int robotsInModelFinal = robotsInModel;
        long gameTicks = a.game.world().newestTick() - a.firstTick;
        int fps = w.params.fps;
        List<String> humansLeft = new ArrayList<>();
        for (int k = 0; k < w.numPlayers; k++) {
            Player p = w.playerAt(k);
            if (!p.robot) {
                humansLeft.add(p.name.toString());
            }
        }
        String chatLine = "hello from the e2e test [Tester]";
        boolean chatBack = a.texts.stream().anyMatch(x -> x.text().equals(chatLine) && x.kind() == Msg.Text.KIND_CHAT
            && x.from() == selfId);
        int bIdFinal = bId;
        int bBaseFinal = bBase;
        // jitter after the first 15 s (a cold JVM compiles the server, the client and the robots meanwhile)
        int skip = Math.min(timing.ticks - 1, 15 * fps);
        long[] gaps = new long[Math.max(0, timing.ticks - 1 - skip)];
        for (int i = skip + 1; i < timing.ticks; i++) {
            gaps[i - 1 - skip] = timing.tickNanos[i] - timing.tickNanos[i - 1];
        }
        double meanMs = Arrays.stream(gaps).average().orElse(0) / 1e6;
        long[] sorted = gaps.clone();
        Arrays.sort(sorted);
        double ideal = 1000.0 / fps;
        double p99Dev = sorted.length == 0 ? 0 : Math.max(Math.abs(sorted[(int) (sorted.length * 0.99)] / 1e6 - ideal),
            Math.abs(sorted[(int) (sorted.length * 0.01)] / 1e6 - ideal));
        float restXf = restX;
        float restYf = restY;
        float tx = thrustX;
        float ty = thrustY;
        double thrustSpeed = Math.hypot(thrustVx, thrustVy);
        float restSpeedF = restSpeed;
        long malformedServer = srv.server.net().malformed();
        System.out.printf("[E2E] ticks=%d gameTicks=%d fps=%d tickMean=%.3fms p99dev=%.3fms min=%.3fms max=%.3fms"
                + " robots=%d kills=%d scoreObjects=%d %s texts=%d rest=%.2f thrust=(%.2f,%.2f) moved=%.1f%n",
            timing.ticks, gameTicks, fps, meanMs, p99Dev, sorted.length == 0 ? 0 : sorted[0] / 1e6,
            sorted.length == 0 ? 0 : sorted[sorted.length - 1] / 1e6, robotJoins, kills, a.scoreObjects.size(),
            a.scoreObjects, a.texts.size(), restSpeedF, thrustVx, thrustVy, Math.hypot(tx - restXf, ty - restYf));
        assertAll(
            () -> assertTrue(spawnSeen(a), "the join completed: PLAYING with an own ship"),
            () -> assertEquals("DoggyStyle", a.game.map() == null ? null : a.game.map().name(), "the map arrived"),
            () -> assertTrue(restSpeedF < 1.5f, "the ship rests at home before thrusting (" + restSpeedF + " px/frame)"),
            () -> assertTrue(thrustSpeed > 3, "thrust makes it fly (" + thrustSpeed + " px/frame)"),
            () -> assertTrue(Math.hypot(tx - restXf, ty - restYf) > 10, "and it moved away from home"),
            () -> assertTrue(gameTicks >= 60L * fps, "60+ s of game time (" + gameTicks + " ticks at " + fps + ")"),
            () -> assertEquals(8, robotJoins, "8 robots appeared as players with the R flag"),
            () -> assertTrue(robotsInModelFinal >= 7, "the client's player list shows them as robots (" + robotsInModelFinal + ")"),
            () -> assertTrue(kills > 0, "kills flowed (" + kills + " KILL events)"),
            () -> assertTrue(a.scores > 20, "scores flowed (" + a.scores + " SCORE messages)"),
            () -> assertTrue(ownScoreTexts > 0, "own score objects arrived with text: " + a.scoreObjects),
            () -> assertTrue(chatBack, "the chat line came back as C formats it: " + a.texts),
            () -> assertTrue(bIdFinal > 0, "the second player joined"),
            () -> assertTrue(a.leaves.contains(bIdFinal), "its leave reached the first player (" + a.leaves + ")"),
            () -> assertTrue(bBaseFinal < 0 || a.baseOwners.contains(new E2eClient.BaseOwner(bBaseFinal, Protocol.NO_ID)),
                "its base was released (" + bBaseFinal + ")"),
            () -> assertEquals(List.of(), humansLeft, "after everyone left only robots remain in the world"),
            () -> assertEquals(0, a.conn.malformed(), "the first client saw no malformed packet"),
            () -> assertEquals(0, malformedServer, "the server saw no malformed packet"),
            () -> assertEquals(ideal, meanMs, ideal * 0.02, "the server ticks at framesPerSecond (mean interval)"),
            () -> assertTrue(p99Dev < 8, "tick jitter is low: p99 deviation " + p99Dev + " ms of " + ideal));
    }

    /** An open 60x60 wrapping map without gravity, one base in the middle, no robots: free flight. */
    static Path openMap() throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("mapWidth: 60\nmapHeight: 60\nmapName: Open\nmapAuthor: e2e\nedgeWrap: yes\ngravity: 0\n")
            .append("maxRobots: 0\nframesPerSecond: 14\nmapData: \\multiline: EndOfMapdata\n");
        for (int r = 0; r < 60; r++) {
            sb.append(r == 30 ? " ".repeat(30) + "_" + " ".repeat(29) : " ".repeat(60)).append('\n');
        }
        sb.append("EndOfMapdata\n");
        Path dir = Path.of("target", "e2e");
        Files.createDirectories(dir);
        Path f = dir.resolve("open.xp");
        Files.writeString(f, sb.toString());
        return f;
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("the client draws its own ship smoothly from the live 14 fps stream: after a burst of thrust the"
        + " coasting ship moves monotonically at 120 Hz with no snapshot-sized steps (no sawtooth)")
    void smoothOwnShip() throws Exception {
        Running srv = new Running(openMap().toString());
        E2eClient a = new E2eClient(srv.port, "Glider", 21);
        double[] ys = new double[1000];
        long[] at = new long[1000];
        int n = 0;
        long t0 = System.nanoTime();
        a.start(t0);
        long spawn = -1;
        long next = t0;
        try {
            while (System.nanoTime() - t0 < 30 * SEC) {
                long now = pace(next);
                next += 1_000_000_000L / 120;
                if (spawn < 0 && a.playing() && a.game.world().hasSelf()) {
                    spawn = now;
                }
                long t = spawn < 0 ? -1 : now - spawn;
                a.step(now, t >= SEC && t < SEC + 300_000_000L ? THRUST : 0);
                if (t >= 2 * SEC && n < ys.length) {
                    at[n] = now;
                    ys[n++] = a.game.world().selfY();
                }
                if (t >= 2 * SEC + 6 * SEC) {
                    break;
                }
            }
            a.conn.disconnect(System.nanoTime());
        } finally {
            a.close();
            srv.close();
        }
        double h = 60 * 35;
        double tick = 1e9 / 14;
        int frames = n;
        boolean monotonic = true;
        double worst = 0;
        double sum = 0;
        double prev = Double.NaN;
        for (int i = 1; i < frames; i++) {
            double dy = com.fulcrologic.jxpilot.common.math.XMath.wrapDelta(ys[i] - ys[i - 1], h, true);
            double v = dy / (at[i] - at[i - 1]) * tick;
            sum += v;
            if (dy <= 0) {
                monotonic = false;
            }
            if (!Double.isNaN(prev)) {
                worst = Math.max(worst, Math.abs(v - prev));
            }
            prev = v;
        }
        double mean = sum / Math.max(1, frames - 1);
        boolean mono = monotonic;
        double worstChange = worst;
        System.out.printf("[E2E smooth] frames=%d drawn speed=%.3f px/tick worst frame-to-frame change=%.3f px/tick"
            + " monotonic=%s%n", frames, mean, worstChange, mono);
        // A sawtooth (extrapolate, then snap back to the next snapshot) changes the drawn speed by about the speed
        // itself (~7 px/tick here) once per snapshot; clock slewing and render timing stay well under 1 px/tick.
        assertAll(
            () -> assertTrue(frames > 500, "rendered about 6 s at 120 Hz (" + frames + ")"),
            () -> assertTrue(mean > 3, "the ship coasts upward (" + mean + " px/tick)"),
            () -> assertTrue(mono, "the drawn ship never stops or moves back"),
            () -> assertTrue(worstChange < 1.0, "the drawn speed changes by less than 1 px/tick between frames ("
                + worstChange + ")"));
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("--local: the embedded server (found through ServiceLoader) serves doggy.xp on loopback; a client"
        + " joins and sees the default 4 robots")
    void localServer() throws Exception {
        com.fulcrologic.jxpilot.client.net.Endpoint ep;
        E2eClient a;
        int robots;
        try (com.fulcrologic.jxpilot.client.app.EmbeddedServer server =
                 com.fulcrologic.jxpilot.client.app.EmbeddedServer.start(Path.of("..", "maps", "doggy.xp"))) {
            ep = server.endpoint();
            a = new E2eClient(ep.host(), ep.port(), "Local", 31);
            long t0 = System.nanoTime();
            a.start(t0);
            long next = t0;
            try {
                while (System.nanoTime() - t0 < 25 * SEC) {
                    long now = pace(next);
                    next += 16_000_000L;
                    a.step(now, 0);
                    if (a.joins.stream().filter(j -> (j.flags() & Msg.PlayerJoin.FLAG_ROBOT) != 0).count() >= 4) {
                        break;
                    }
                }
                a.conn.disconnect(System.nanoTime());
            } finally {
                a.close();
            }
            robots = (int) a.joins.stream().filter(j -> (j.flags() & Msg.PlayerJoin.FLAG_ROBOT) != 0)
                .map(E2eClient.Join::id).distinct().count();
        }
        com.fulcrologic.jxpilot.client.net.Endpoint endpoint = ep;
        int robotCount = robots;
        assertAll(
            () -> assertTrue(InetAddress.getByName(endpoint.host()).isLoopbackAddress(), "bound to loopback: " + endpoint),
            () -> assertTrue(endpoint.port() > 0, "on a real port"),
            () -> assertEquals("DoggyStyle", a.game.map() == null ? null : a.game.map().name(), "the map arrived"),
            () -> assertTrue(spawnSeen(a), "snapshots arrived"),
            () -> assertEquals(4, robotCount, "the default 4 robots joined"),
            () -> assertEquals(0, a.conn.malformed(), "nothing malformed"));
    }

    static boolean spawnSeen(E2eClient c) {
        return c.firstTick >= 0;
    }

    /** The base the client's player list says the player owns (-1 if none). */
    static int baseOf(E2eClient c) {
        for (int i = 0; i < c.game.map().bases().size(); i++) {
            if (c.game.world().baseOwner(i) == c.selfId()) {
                return i;
            }
        }
        return -1;
    }
}
