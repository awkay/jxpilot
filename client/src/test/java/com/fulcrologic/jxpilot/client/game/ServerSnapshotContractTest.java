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
package com.fulcrologic.jxpilot.client.game;

import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.Section;
import com.fulcrologic.jxpilot.common.net.SnapshotReader;
import com.fulcrologic.jxpilot.common.net.Wire;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.snapshot.SnapshotBuilder;
import com.fulcrologic.jxpilot.server.world.Click;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-module contract: snapshots built by the real server {@link SnapshotBuilder} over a real {@link World}
 * and {@link Simulation} (team play, three players, shots of every relation to the viewer, two debris bursts,
 * radar, enough shots to need several datagrams) and fed to {@link ClientWorld} produce the server's picture:
 * the own ship, the other ships, every shot with its colour and harmless bit, every burst particle and the
 * radar. Part order must not matter (SELF is only in part 0; later parts repeat sections).
 */
@DisplayName("server SnapshotBuilder -> ClientWorld contract")
class ServerSnapshotContractTest {
    static final String[] ROWS = {
        "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
        "x                                      x",
        "x                                      x",
        "x   1  1  2                            x",
        "x                                      x",
        "x                                      x",
        "x                                      x",
        "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    };
    static final int FILLER = 400;
    /** Rel positions are half pixels from the view centre: decoding is within a quarter pixel. */
    static final double HALF_PIXEL = 0.25;

    Simulation sim;
    World w;
    Player a;
    Player b;
    Player c;
    final ByteBuffer[] parts = new ByteBuffer[8];
    int partCount;
    int particlesMade;
    int ownShot;
    int mateShot;
    int foeShot;

    static String mapText() {
        StringBuilder sb = new StringBuilder();
        sb.append("mapWidth: ").append(ROWS[0].length()).append('\n');
        sb.append("mapHeight: ").append(ROWS.length).append('\n');
        sb.append("framesPerSecond: 14\ngravity: 0\nteamPlay: yes\nteamImmunity: yes\nselfImmunity: no\n");
        sb.append("mapData: \\multiline: EndOfMapdata\n");
        for (String r : ROWS) {
            sb.append(r).append('\n');
        }
        return sb.append("EndOfMapdata\n").toString();
    }

    @BeforeEach
    void setUp() {
        Options o = new Options();
        ConfigParser.parse(mapText(), "contract", OptionOrigin.MAP, o);
        w = new World(o, 1);
        sim = new Simulation(w);
        a = sim.ships.join("A", 1);
        b = sim.ships.join("B", 1);
        c = sim.ships.join("C", 2);
        sim.tick(new long[w.players.length]);
        ownShot = sim.shots.fire(a, a.team, false, 150, 150, Obj.SHOT, 0, 0);
        mateShot = sim.shots.fire(b, b.team, false, 250, 160, Obj.SHOT, 32, 0);
        foeShot = sim.shots.fire(c, c.team, false, 350, 170, Obj.SHOT, 64, 0);
        for (int i = 0; i < FILLER; i++) {
            sim.shots.fire(c, c.team, false, 400 + (i % 20) * 10, 60 + (i / 20) * 5, Obj.SHOT, i % 128, 0);
            c.shots = 0;
        }
        long burstFrom = w.events.burstSeq();
        particlesMade = sim.debris.make(300, 200, 0, 0, c.id, c.team, Obj.DEBRIS, 3.5f, Obj.GRAVITY,
            Records.ObjColor.RED, Records.BurstKind.DEBRIS, 8, 10, 10, 0, 127, 20, 30, 5, 20);
        particlesMade += sim.debris.make(500, 100, 0, 0, Obj.NO_ID, 0, Obj.DEBRIS, 3.5f, Obj.GRAVITY,
            Records.ObjColor.WHITE, Records.BurstKind.DEBRIS, 8, 6, 6, 0, 127, 10, 20, 5, 10);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = ByteBuffer.allocate(1200);
        }
        partCount = new SnapshotBuilder(w).build(a, 1, burstFrom, true, parts);
    }

    /** A client configured like a real one: the map parsed from the server's config text. */
    ClientWorld client() {
        Options co = new Options();
        ConfigParser.parse(w.options.toConfigText(), "welcome", OptionOrigin.MAP, co);
        WorldMap m = WorldMap.fromOptions(co);
        Msg.Welcome wel = new Msg.Welcome();
        wel.simHz = 14;
        wel.snapshotHz = 14;
        wel.gameFps = 14;
        Players p = new Players();
        p.setSelfId(a.id);
        for (Player pl : new Player[] {a, b, c}) {
            Msg.PlayerJoin j = new Msg.PlayerJoin();
            j.id = pl.id;
            j.team = pl.team;
            j.name.append(pl.name);
            j.power = 45;
            p.join(j);
        }
        ClientWorld cw = new ClientWorld(p, 32, 1024, 4096, 64);
        cw.configure(m, co, wel);
        cw.setInterpolationDelayTicks(0);
        return cw;
    }

    static final long NOW = 5_000_000_000L;

    /** Feed the parts in the given order and render at their arrival time. */
    ClientWorld feed(int... order) {
        ClientWorld cw = client();
        for (int p : order) {
            ByteBuffer body = parts[p].duplicate().flip();
            int r = cw.ingest(body, NOW);
            assertTrue(r == ClientWorld.NEW_TICK || r == ClientWorld.PART_ADDED, "part " + p + " accepted: " + r);
        }
        cw.update(NOW);
        return cw;
    }

    int[] inOrder() {
        int[] o = new int[partCount];
        for (int i = 0; i < partCount; i++) {
            o[i] = i;
        }
        return o;
    }

    int[] reversed() {
        int[] o = new int[partCount];
        for (int i = 0; i < partCount; i++) {
            o[i] = partCount - 1 - i;
        }
        return o;
    }

    static int shield(Player p) {
        return (p.used & Has.SHIELD) != 0 ? Records.ShipFlags.SHIELD : 0;
    }

    static double px(int click) {
        return click / (double) Click.CLICK;
    }

    /** Client shots by "kind colour harmless". */
    static Map<String, Integer> shotGroups(ClientWorld cw) {
        Map<String, Integer> m = new TreeMap<>();
        for (int i = 0; i < cw.shotCount(); i++) {
            m.merge(cw.shotKind(i) + " " + cw.shotColor(i) + " " + cw.shotHarmless(i), 1, Integer::sum);
        }
        return m;
    }

    /** The client shot nearest to the server shot {@code h}: "colour harmless dx dy". */
    String nearestShot(ClientWorld cw, int h) {
        double x = px(w.objects.cx[h]);
        double y = px(w.objects.cy[h]);
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < cw.shotCount(); i++) {
            double d = Math.hypot(cw.shotX(i) - x, cw.shotY(i) - y);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return cw.shotColor(best) + " " + cw.shotHarmless(best) + " " + (bd <= HALF_PIXEL);
    }

    static List<String> particles(ClientWorld cw) {
        List<String> l = new ArrayList<>();
        ParticlePool pp = cw.particles();
        for (int i = 0; i < pp.count(); i++) {
            l.add(pp.originX(i) + "," + pp.originY(i) + " v" + pp.vx(i) + "," + pp.vy(i) + " c" + pp.color(i));
        }
        Collections.sort(l);
        return l;
    }

    /** Server debris objects in the {@link #particles} format (origin, velocity, colour), sorted. */
    List<String> serverDebris() {
        List<String> l = new ArrayList<>();
        Objects o = w.objects;
        for (int k = 0; k < o.count(); k++) {
            int h = o.active[k];
            if (o.type[h] == Obj.DEBRIS) {
                l.add((float) px(o.cx[h]) + "," + (float) px(o.cy[h]) + " v" + o.vx[h] + "," + o.vy[h] + " c"
                    + o.color[h]);
            }
        }
        Collections.sort(l);
        return l;
    }

    static List<String> radar(ClientWorld cw) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < cw.radarCount(); i++) {
            l.add(cw.radarX(i) + "," + cw.radarY(i) + ":" + cw.radarFlags(i));
        }
        Collections.sort(l);
        return l;
    }

    String blip(Player p, int flags) {
        return Wire.fromRadar8(Wire.toRadar8(px(p.cx), w.pixelWidth), w.pixelWidth) + ","
            + Wire.fromRadar8(Wire.toRadar8(px(p.cy), w.pixelHeight), w.pixelHeight) + ":" + flags;
    }

    /** Section types per part, in order, read with the common reader. */
    List<List<Integer>> sections() {
        List<List<Integer>> l = new ArrayList<>();
        SnapshotReader r = new SnapshotReader();
        r.setWorld(w.pixelWidth, w.pixelHeight, w.wrap);
        for (int p = 0; p < partCount; p++) {
            List<Integer> types = new ArrayList<>();
            r.begin(parts[p].duplicate().flip());
            while (r.nextSection()) {
                types.add(r.type());
            }
            l.add(types);
        }
        return l;
    }

    @Nested
    @DisplayName("ingest of a multi-part server snapshot")
    class Ingest {
        @Test
        @DisplayName("the client sees the server's own ship, ships, shots (colour and harmless bit), bursts and radar")
        void picture() {
            ClientWorld cw = feed(inOrder());
            List<List<Integer>> sec = sections();
            String ownVariant = Records.ShotKind.SHOT + " " + Records.ObjColor.OWN;
            Map<String, Integer> groups = shotGroups(cw);
            List<String> ships = new ArrayList<>();
            for (int i = 0; i < cw.shipCount(); i++) {
                ships.add(cw.shipId(i) + " " + cw.shipFlags(i) + " "
                    + (Math.abs(cw.shipX(i) - px(i == 0 ? b.cx : c.cx)) <= HALF_PIXEL));
            }
            assertAll(
                () -> assertTrue(partCount >= 2, FILLER + " shots need several datagrams (" + partCount + ")"),
                () -> assertEquals(Section.SELF, sec.get(0).get(0), "part 0 starts with SELF"),
                () -> assertTrue(sec.stream().skip(1).noneMatch(t -> t.contains(Section.SELF)), "SELF only in part 0"),
                () -> assertTrue(sec.get(partCount - 1).contains(Section.SHOT), "SHOT sections repeat in later parts"),
                () -> assertTrue(cw.hasSelf(), "own ship present"),
                () -> assertEquals(a.id, cw.selfId(), "own id"),
                () -> assertEquals((float) px(a.cx), cw.selfX(), "own x at full precision"),
                () -> assertEquals((float) px(a.cy), cw.selfY(), "own y at full precision"),
                () -> assertEquals((float) (a.fuel / 256), cw.selfFuel(), "own fuel"),
                () -> assertEquals(List.of(b.id + " " + (shield(b) | Records.ShipFlags.FRIENDLY) + " true",
                        c.id + " " + shield(c) + " true"), ships,
                    "the team mate (friendly) and the opponent, in server order, within a quarter pixel, with the "
                        + "spawn shield the server reports"),
                () -> assertEquals(Map.of(
                        ownVariant + " false", 1,
                        Records.ShotKind.SHOT + " " + Records.ObjColor.TEAM + " true", 1,
                        Records.ShotKind.SHOT + " " + Records.ObjColor.ENEMY + " false", FILLER + 1), groups,
                    "every shot arrives once: own (OWN, can hurt without selfImmunity), team mate's (TEAM, harmless "
                        + "with teamImmunity), the opponent's (ENEMY, can hurt)"),
                () -> assertEquals(Records.ObjColor.OWN + " false true", nearestShot(cw, ownShot), "own shot in place"),
                () -> assertEquals(Records.ObjColor.TEAM + " true true", nearestShot(cw, mateShot),
                    "team mate's shot in place and harmless"),
                () -> assertEquals(Records.ObjColor.ENEMY + " false true", nearestShot(cw, foeShot),
                    "opponent's shot in place and harmful"),
                () -> assertEquals(particlesMade, cw.particles().count(), "every burst particle the server made"),
                () -> assertEquals(serverDebris(), particles(cw),
                    "the client expands exactly the server's particles: origin, velocity and colour"),
                () -> assertEquals(sorted(blip(a, Records.Radar.FLAG_SELF), blip(b, Records.Radar.FLAG_FRIENDLY),
                    blip(c, 0)), radar(cw), "radar: own blip SELF, team mate FRIENDLY, opponent plain"));
        }

        @Test
        @DisplayName("parts in reverse order (SELF last) give exactly the same picture")
        void partOrder() {
            ClientWorld forward = feed(inOrder());
            ClientWorld backward = feed(reversed());
            assertAll(
                () -> assertEquals(dump(forward), dump(backward), "own ship, ships and shots (as sets)"),
                () -> assertEquals(particles(forward), particles(backward), "burst particles"),
                () -> assertEquals(radar(forward), radar(backward), "radar"));
        }
    }

    static List<String> sorted(String... s) {
        List<String> l = new ArrayList<>(List.of(s));
        Collections.sort(l);
        return l;
    }

    static List<String> dump(ClientWorld cw) {
        List<String> l = new ArrayList<>();
        l.add("self " + cw.selfId() + " " + cw.selfX() + "," + cw.selfY() + " " + cw.selfFlags());
        for (int i = 0; i < cw.shipCount(); i++) {
            l.add("ship " + cw.shipId(i) + " " + cw.shipX(i) + "," + cw.shipY(i) + " " + cw.shipFlags(i));
        }
        List<String> shots = new ArrayList<>();
        for (int i = 0; i < cw.shotCount(); i++) {
            shots.add("shot " + cw.shotX(i) + "," + cw.shotY(i) + " " + cw.shotKind(i) + " " + cw.shotColor(i) + " "
                + cw.shotHarmless(i));
        }
        Collections.sort(shots);
        l.addAll(shots);
        return l;
    }
}
