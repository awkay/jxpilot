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
package com.fulcrologic.jxpilot.server.sim;

import com.fulcrologic.jxpilot.common.map.Base;
import com.fulcrologic.jxpilot.common.math.DebrisBurst;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.server.sim.CTraceItems.CCannon;
import com.fulcrologic.jxpilot.server.sim.CTraceItems.CDebris;
import com.fulcrologic.jxpilot.server.sim.CTraceItems.CDraw;
import com.fulcrologic.jxpilot.server.sim.CTraceItems.CEcm;
import com.fulcrologic.jxpilot.server.sim.CTraceItems.CFrame;
import com.fulcrologic.jxpilot.server.sim.CTraceItems.CItems;
import com.fulcrologic.jxpilot.server.sim.CTraceItems.CObj;
import com.fulcrologic.jxpilot.server.sim.CTraceItems.CPulse;
import com.fulcrologic.jxpilot.server.world.CannonItems;
import com.fulcrologic.jxpilot.server.world.Ecms;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Mods;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Params;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.Pulses;
import com.fulcrologic.jxpilot.server.world.Trig;
import com.fulcrologic.jxpilot.server.world.World;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Full replay of a patch 0006 C trace: the Java world is configured like the C server ({@link CTraceItems#cworld}),
 * the players join when they joined in C and hold the keys C received (XPKEYS), and every random draw is C's own
 * ({@link CDraws}). Nothing is copied from C after the start, so every frame is compared exactly: each player's
 * physics, status and items, every object C traced (missiles, mines, items, shots, cannon shots, wreckage...), laser
 * pulses, ECMs, busy cannons and the explosion debris made (count, lives, directions, speeds, owner, colour).
 */
final class CReplay {
    private CReplay() {}

    /** What one replay compared and the frames that differed. */
    record Result(int frames, List<String> mismatches, int objects, Map<Integer, Integer> objectsByType, int draws,
                  int bursts, int particles, int particleFrames, int cannonFrames, int pulses, int ecms) {
        /** What was compared, without the mismatches. */
        String summary() {
            return frames + " frames, " + objects + " object-frames " + objectsByType + ", " + draws + " C draws, "
                + bursts + " bursts (" + particles + " particles, traced in " + particleFrames + " frames), " + cannonFrames + " busy-cannon frames, " + pulses
                + " pulse-frames, " + ecms + " ECM-frames";
        }

        /** Object-frames of the given C type compared. */
        int of(int type) {
            return objectsByType.getOrDefault(type, 0);
        }
    }

    /** Replay {@code trace} on default.xp. */
    static Result run(String trace, String... opts) throws IOException {
        return run(trace, "default.xp", Long.MAX_VALUE, opts);
    }

    /** Replay {@code trace} on {@code map} up to and including C frame {@code last}. */
    static Result run(String trace, String map, long last, String... opts) throws IOException {
        List<CFrame> c = CTraceItems.load(trace);
        c.removeIf(f -> f.frame > last);
        Simulation s = CTraceItems.cworld(map, opts);
        World w = s.world;
        CDraws draws = new CDraws();
        w.replay = draws;
        Map<Integer, Player> players = new LinkedHashMap<>();
        Map<Integer, Long> keys = new LinkedHashMap<>();
        long[] in = new long[w.players.length];
        boolean[] before = new boolean[w.objects.capacity];
        List<String> bad = new ArrayList<>();
        int objects = 0;
        Map<Integer, Integer> byType = new java.util.TreeMap<>();
        int bursts = 0;
        int particles = 0;
        int cannonFrames = 0;
        int pulses = 0;
        int ecms = 0;
        int debFrames = 0;
        for (CFrame f : c) {
            List<String> d = new ArrayList<>();
            int problems = draws.problems.size();
            draws.begin(f.frame, f.draws);
            for (Map.Entry<Integer, CTraceTest.Frame> e : f.ships.entrySet()) {
                if (!players.containsKey(e.getKey())) {
                    players.put(e.getKey(), join(s, f.names.get(e.getKey()), e.getValue(), d));
                }
            }
            keys.putAll(f.keys);
            for (int id : f.keysTwice) {
                d.add("keys: player " + id + " sent two keyboard updates in one C tick (a Java tick takes one)");
            }
            Arrays.fill(in, 0);
            for (Map.Entry<Integer, Player> e : players.entrySet()) {
                in[e.getValue().slot] = keys.getOrDefault(e.getKey(), 0L);
            }
            long seq = w.events.burstSeq();
            Objects ob = w.objects;
            Arrays.fill(before, false);
            for (int k = 0; k < ob.count(); k++) {
                before[ob.active[k]] = true;
            }
            s.tick(in);
            draws.end();
            for (int i = problems; i < draws.problems.size(); i++) {
                d.add("draws: " + draws.problems.get(i));
            }
            // C's own particles first: cluster fragments are shots, compared with the other objects below
            List<CBurst> cb = cBursts(w, f);
            bursts += cb.size();
            List<String> jb = jBursts(w.events, seq);
            List<String> ck = keys(cb);
            if (ck.equals(jb)) {
                d.addAll(syncParticles(w, cb, w.events, seq, before, f.debSlot));
                for (CBurst b : cb) {
                    particles += b.count();
                }
            }
            for (Map.Entry<Integer, CTraceTest.Frame> e : f.ships.entrySet()) {
                Player p = players.get(e.getKey());
                comparePlayer(f, e.getKey(), p, d);
            }
            List<String> co = new ArrayList<>();
            for (CObj o : f.objs) {
                co.add(key(o));
                byType.merge(o.type(), 1, Integer::sum);
            }
            List<String> jo = new ArrayList<>();
            Objects o = w.objects;
            for (int k = 0; k < o.count(); k++) {
                int h = o.active[k];
                if ((o.type[h] & (Obj.SPARK | Obj.DEBRIS)) == 0 && o.life[h] > 0) {
                    jo.add(key(o, h));
                }
            }
            objects += co.size();
            diff("objects", co, jo, d);
            pulses += f.pulses.size();
            ecms += f.ecms.size();
            diff("pulses", cPulses(f), jPulses(w.pulses), d);
            diff("ecms", cEcms(f), jEcms(w.ecms), d);
            diff("debris", ck, jb, d);
            if (!f.debs.isEmpty()) { // traced around explosions (tools/xpilot-ref/compact-rand.sh)
                List<String> jd = new ArrayList<>();
                for (int k = 0; k < o.count(); k++) {
                    int h = o.active[k];
                    if ((o.type[h] & (Obj.SPARK | Obj.DEBRIS)) != 0 && o.life[h] > 0
                        && ((o.status[h] & Obj.OWNERIMMUNE) == 0 || (o.status[h] & Obj.FROMBOUNCE) != 0)) {
                        jd.add(particle(o.type[h], o.owner[h], o.cx[h], o.cy[h], o.vx[h], o.vy[h], o.mass[h],
                            (long) o.life[h], o.status[h]));
                    }
                }
                debFrames += f.debs.isEmpty() ? 0 : 1;
                diff("explosion particles", new ArrayList<>(f.debs), jd, d);
            }
            List<String> cc = new ArrayList<>();
            for (CCannon q : f.cannons) {
                cc.add(q.index() + " dead" + q.deadTime() + " dmg" + q.damaged() + " tr" + q.tractorCount() + " u"
                    + Integer.toHexString(q.used()) + " es" + q.shieldLeft() + " ph" + q.phasingLeft());
            }
            cannonFrames += cc.isEmpty() ? 0 : 1;
            diff("cannons", cc, jCannons(w), d);
            if (!d.isEmpty()) {
                bad.add("frame " + f.frame + ": " + String.join("\n  ", d));
            }
            // C's Frame_update runs after the trace lines: the HUD lock of each client (frame.c:395-422)
            for (Player p : players.values()) {
                s.locks.hudLock(p);
            }
        }
        return new Result(c.size(), bad, objects, byType, draws.matched, bursts, particles, debFrames, cannonFrames,
            pulses, ecms);
    }

    /** Join a C player in Java (its base by C's draw, checked against C's first position) with C's settings. */
    private static Player join(Simulation s, String name, CTraceTest.Frame first, List<String> d) {
        World w = s.world;
        Player p = s.ships.join(name, 0);
        int bx = (first.cx() >> 6) / 35;
        int by = (first.cy() >> 6) / 35;
        for (Base b : w.map.bases()) {
            if (b.bx() == bx && b.by() == by && p.homeBase != b.index()) {
                d.add("join: " + name + " got base " + p.homeBase + ", C's is " + b.index());
                p.homeBase = b.index();
                s.ships.goHome(p);
            }
        }
        s.ships.applySettings(p, 45f, 35f, 30 / 256f, 35f, 25f, 30 / 256f);
        return p;
    }

    private static void comparePlayer(CFrame f, int id, Player p, List<String> d) {
        CTraceTest.Frame cs = f.ships.get(id);
        CTraceTest.Frame js = CTraceTest.Frame.of(cs.frame(), p);
        if (!js.equals(cs)) {
            d.add("ship " + id + "\n    C    " + cs + "\n    Java " + js);
        }
        int cst = f.status.get(id);
        if (cst != p.status) {
            d.add("status " + id + " C " + Integer.toHexString(cst) + " Java " + Integer.toHexString(p.status));
        }
        CItems ci = f.itemsOf.get(id);
        if (ci != null) {
            String c = CTraceItemsTest.items(ci.items(), ci.used(), ci.have(), ci.fuel(), ci.fuelMax(), ci.numTanks(),
                ci.current()) + " tanks " + Arrays.toString(ci.tanks()) + " left " + ci.thrustLeft() + "/"
                + ci.shieldLeft() + "/" + ci.phasingLeft() + " lock " + ci.lockTagged() + "," + ci.lockId()
                + " damaged " + ci.damaged() + " stunned " + ci.stunned() + " pulses " + ci.pulses() + " ecms "
                + ci.ecms();
            long[] tanks = new long[ci.tanks().length];
            for (int i = 0; i <= p.numTanks && i < tanks.length; i++) {
                tanks[i] = (long) p.tankFuel[i];
            }
            String j = CTraceItemsTest.items(p.items, p.used, p.have, (long) p.fuel, (long) p.fuelMax, p.numTanks,
                p.currentTank) + " tanks " + Arrays.toString(tanks) + " left " + p.emergencyThrustLeft + "/"
                + p.emergencyShieldLeft + "/" + p.phasingLeft + " lock " + p.lockTagged + "," + p.lockPlId
                + " damaged " + p.damaged + " stunned " + p.stunned + " pulses " + p.numPulses + " ecms "
                + p.ecmCount;
            if (!c.equals(j)) {
                d.add("items " + id + "\n    C    " + c + "\n    Java " + j);
            }
        }
    }

    static void diff(String what, List<String> c, List<String> j, List<String> d) {
        c.sort(null);
        j.sort(null);
        if (!c.equals(j)) {
            List<String> onlyC = new ArrayList<>(c);
            for (String x : j) {
                onlyC.remove(x);
            }
            List<String> onlyJ = new ArrayList<>(j);
            for (String x : c) {
                onlyJ.remove(x);
            }
            d.add(what + "\n    C only    " + onlyC + "\n    Java only " + onlyJ);
        }
    }

    /** The compared state of a C object: everything the trace prints but its Obj[] slot. */
    static String key(CObj c) {
        return key(c.type(), c.id(), c.cx(), c.cy(), c.prevX(), c.prevY(), c.vx(), c.vy(), c.ax(), c.ay(), c.mass(),
            c.life(), c.status() & 0xFFFFFF, c.info(), c.count(), dir(c.type(), c.dir()), c.plRange(), c.plRadius(),
            c.type() == Obj.ITEM ? "-" : Arrays.toString(c.mods())) + " " + norm(c.extra());
    }

    static String key(Objects o, int h) {
        int m = o.mods[h];
        int[] mods = {Mods.nuclear(m), Mods.warhead(m), Mods.velocity(m), Mods.mini(m), Mods.spread(m), Mods.power(m),
            Mods.laser(m)};
        String extra = switch (o.type[h]) {
            case Obj.MINE -> o.mineOwner[h] + "," + o.spreadLeft[h] + "," + g(o.ecmRange[h]);
            case Obj.TORPEDO -> g(o.maxSpeed[h]) + "," + g(o.turnSpeed[h]) + "," + o.spreadLeft[h];
            case Obj.HEAT_SHOT -> g(o.maxSpeed[h]) + "," + g(o.turnSpeed[h]);
            case Obj.SMART_SHOT -> g(o.maxSpeed[h]) + "," + g(o.turnSpeed[h]) + "," + o.newInfo[h] + ","
                + g(o.ecmRange[h]);
            default -> "";
        };
        return key(o.type[h], o.owner[h], o.cx[h], o.cy[h], o.prevX[h], o.prevY[h], o.vx[h], o.vy[h], o.accX[h],
            o.accY[h], o.mass[h], (long) o.life[h], o.status[h] & 0xFFFFFF, o.info[h], o.counter[h], dir(o.type[h], o.missileDir[h]),
            o.plRange[h], o.plRadius[h], o.type[h] == Obj.ITEM ? "-" : Arrays.toString(mods)) + " " + (extra.isEmpty() ? "" : norm(extra.split(",")));
    }

    /**
     * missile_dir only where C sets it (missiles): Object_allocate does not clear it, so other C objects show
     * whatever an earlier object in their Obj[] slot left there. The same goes for an item's mods (Make_item,
     * item.c:350-376, does not set them), which are not compared.
     */
    static int dir(int type, int dir) {
        return (type & (Obj.SMART_SHOT | Obj.HEAT_SHOT | Obj.TORPEDO)) != 0 ? dir : 0;
    }

    /** A float of the extra columns, as {@link #norm} writes C's. */
    static String g(float v) {
        return Float.toString(v);
    }

    /** Extra columns with each value (C %d or %.9g, or Java's) written as Java writes a float. */
    static String norm(String[] extra) {
        StringBuilder b = new StringBuilder();
        for (String t : extra) {
            if (!b.isEmpty()) {
                b.append(',');
            }
            b.append(Float.toString(Float.parseFloat(t)));
        }
        return b.toString();
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    static String key(int type, int id, int cx, int cy, int px, int py, float vx, float vy, float ax, float ay,
                      float mass, long life, int status, long info, long count, int dir, int plRange, int plRadius,
                      String mods) {
        String l = life > 1L << 40 ? "inf" : Long.toString(life); // balls never expire (C LONG_MAX, Java huge)
        return type + " #" + id + " c" + cx + "," + cy + " p" + px + "," + py + " v" + vx + "," + vy + " a" + ax + ","
            + ay + " m" + mass + " l" + l + " s" + Integer.toHexString(status) + " i" + info + " n" + count + " d"
            + dir + " r" + plRange + "/" + plRadius + " mods" + mods;
    }

    static List<String> cPulses(CFrame f) {
        List<String> out = new ArrayList<>();
        for (CPulse q : f.pulses) {
            out.add(q.id() + " " + q.x() + "," + q.y() + " d" + q.dir() + " l" + q.len() + " t" + q.life() + " r"
                + q.refl());
        }
        return out;
    }

    static List<String> jPulses(Pulses ps) {
        List<String> out = new ArrayList<>();
        for (int k = 0; k < ps.count(); k++) {
            out.add(ps.owner[k] + " " + ps.x[k] + "," + ps.y[k] + " d" + ps.dir[k] + " l" + ps.len[k] + " t" + ps.life[k]
                + " r" + ps.refl[k]);
        }
        return out;
    }

    static List<String> cEcms(CFrame f) {
        List<String> out = new ArrayList<>();
        for (CEcm q : f.ecms) {
            out.add(q.id() + " " + q.x() + "," + q.y() + " s" + q.size());
        }
        return out;
    }

    static List<String> jEcms(Ecms es) {
        List<String> out = new ArrayList<>();
        for (int k = 0; k < es.count(); k++) {
            out.add(es.owner[k] + " " + es.x[k] + "," + es.y[k] + " s" + es.size[k]);
        }
        return out;
    }

    /** One C Make_debris call that made particles, as Java would record and expand it. */
    record CBurst(String key, CDebris q, int type, float x, float y, int count, int minDir, int maxDir,
                  float minSpeed, float maxSpeed, int minLife, int maxLife, List<CDraw> draws) {
        /** Cluster fragments (cannon flak, cluster warheads): shots that Java does not record as a burst. */
        boolean flak() {
            return type == Obj.SHOT;
        }
    }

    /**
     * The bursts C's Make_debris calls of this frame made (thrust exhaust and cluster fragments excepted: Java
     * seeds the exhaust from the tick and makes fragments as shots, unrecorded), with C's arguments clamped as
     * Make_debris clamps them (play.c:162-197) and C's count draw; {@link CBurst#key} is the burst as Java records
     * it, after the wire quantization.
     */
    static List<CBurst> cBursts(World w, CFrame f) {
        Params p = w.params;
        List<CBurst> out = new ArrayList<>();
        for (int i = 0; i < f.debris.size(); i++) {
            CDebris q = f.debris.get(i);
            List<CDraw> r = f.debrisDraws.get(i);
            if ("Thrust".equals(q.caller()) || r.isEmpty()) {
                continue;
            }
            float x = q.x();
            float y = q.y();
            if (w.wrap) {
                if (x < 0) {
                    x += w.pixelWidth;
                } else if (x >= w.pixelWidth) {
                    x -= w.pixelWidth;
                }
                if (y < 0) {
                    y += w.pixelHeight;
                } else if (y >= w.pixelHeight) {
                    y -= w.pixelHeight;
                }
            }
            int minLife = q.minLife();
            int maxLife = q.maxLife();
            if (maxLife < minLife) {
                maxLife = minLife;
            }
            if (p.shotLife >= p.fps) {
                if (minLife > p.shotLife) {
                    minLife = p.shotLife;
                    maxLife = p.shotLife;
                } else if (maxLife > p.shotLife) {
                    maxLife = p.shotLife;
                }
            }
            float minSp = q.minSpeed();
            float maxSp = q.maxSpeed();
            // C World.hypotenuse is an int (map.h:219): integer divisions
            if (minSp * maxLife > p.hypotenuse) {
                minSp = (int) p.hypotenuse / maxLife;
            }
            if (maxSp * minLife > p.hypotenuse) {
                maxSp = (int) p.hypotenuse / minLife;
            }
            if (maxSp < minSp) {
                maxSp = minSp;
            }
            int count = q.minDebris() + (int) (r.get(0).rfrac() * (q.maxDebris() - q.minDebris()));
            if (count <= 0) {
                continue;
            }
            DebrisBurst b = new DebrisBurst();
            b.count = count;
            b.minDir = q.minDir();
            b.dirSpan = q.maxDir() - q.minDir();
            b.minSpeed = minSp;
            b.maxSpeed = maxSp;
            b.minLife = minLife;
            b.maxLife = maxLife;
            Records.Burst.quantize(b);
            out.add(new CBurst(burst(q.id(), color(q.color()), b), q, q.type(), x, y, count, q.minDir(), q.maxDir(),
                minSp, maxSp, minLife, maxLife, r));
        }
        return out;
    }

    static List<String> keys(List<CBurst> bs) {
        List<String> out = new ArrayList<>();
        for (CBurst b : bs) {
            if (!b.flak()) {
                out.add(b.key());
            }
        }
        return out;
    }

    /**
     * Put the particles Java made for each burst of this tick in C's object slots. Replaying, Java already makes
     * C's own particles (Debris.cParticles with C's draws); but a trace compacted without the thrust exhaust's
     * particle draws (compact-rand.sh) gives Java's exhaust other lives, its sparks die at other times and the pool
     * order (C's Obj[] swaps) drifts from C's, which changes the order in which particles later hit a ship. The
     * particles' C states (play.c:204-240 from the trace's draws) are given to Java's particles in C's slot order;
     * with the exhaust's draws in the trace this changes nothing. Java's particles of a burst are the new objects
     * of its type and owner at its origin, in allocation order (the last one may have been moved into the slot
     * of the object whose death made the burst, as C moves it too).
     *
     * @return problems found (Java particles not found, C draws missing)
     */
    static List<String> syncParticles(World w, List<CBurst> cb, Events e, long from, boolean[] before,
                                      Map<String, Integer> cSlot) {
        List<String> bad = new ArrayList<>();
        Objects o = w.objects;
        boolean[] claimed = new boolean[o.capacity];
        List<Integer> madeAt = new ArrayList<>();
        List<float[]> states = new ArrayList<>();
        long seq = from;
        for (CBurst c : cb) {
            int type = c.type();
            int owner;
            int ocx;
            int ocy;
            // replaying, Java makes C's own particles at C's origin (Debris.cParticles)
            ocx = com.fulcrologic.jxpilot.server.world.Click.fromPixels(c.x());
            ocy = com.fulcrologic.jxpilot.server.world.Click.fromPixels(c.y());
            if (c.flak()) {
                // flak fragments are shots, not a recorded burst
                owner = c.q().id();
            } else {
                if (seq >= e.burstSeq()) {
                    bad.add("particles: no Java burst for " + c.key());
                    break;
                }
                owner = e.burstOwner[Events.burstIndex(seq++)];
            }
            List<Integer> idx = new ArrayList<>();
            for (int k = 0; k < o.count(); k++) {
                int h = o.active[k];
                boolean kind = c.flak() ? (o.mods[h] & Obj.MOD_CLUSTER) != 0
                    : o.mass[h] == c.q().mass() && o.status[h] == (int) c.q().status();
                if (!claimed[h] && o.type[h] == type && o.owner[h] == owner && o.cx[h] == ocx && o.cy[h] == ocy
                    && kind && (!before[h] || o.fuseLife[h] - o.life[h] <= 1)) {
                    idx.add(k);
                }
            }
            if (idx.size() < c.count()) {
                bad.add("particles: burst " + c.key() + " has " + idx.size() + " Java particles");
                continue;
            }
            idx = idx.subList(0, c.count());
            if (c.draws().size() < 1 + 4 * c.count()) {
                bad.add("particles: burst " + c.key() + " has " + (c.draws().size() - 1) / 4 + " C particle draws");
                continue;
            }
            CDebris q = c.q();
            for (int i = 0; i < idx.size(); i++) {
                int h = o.active[idx.get(i)];
                claimed[h] = true;
                madeAt.add(idx.get(i));
                double r1 = c.draws().get(1 + 4 * i).rfrac();
                float diroff = (float) c.draws().get(2 + 4 * i).rfrac();
                double r3 = c.draws().get(3 + 4 * i).rfrac();
                double r4 = c.draws().get(4 + 4 * i).rfrac();
                int dir = (c.minDir() + (int) (r1 * (c.maxDir() - c.minDir()))) & (com.fulcrologic.jxpilot.common.Const.RES - 1);
                int dirPlus = (dir + 1) & (com.fulcrologic.jxpilot.common.Const.RES - 1);
                float dx = Trig.tcos(dir) + (Trig.tcos(dirPlus) - Trig.tcos(dir)) * diroff;
                float dy = Trig.tsin(dir) + (Trig.tsin(dirPlus) - Trig.tsin(dir)) * diroff;
                float speed = (float) (c.minSpeed() + r3 * (c.maxSpeed() - c.minSpeed()));
                int life = (int) (c.minLife() + r4 * (c.maxLife() - c.minLife()) + 1);
                if (life * speed > (int) w.params.hypotenuse) {
                    life = (int) ((int) w.params.hypotenuse / speed);
                }
                double aged = o.fuseLife[h] - o.life[h];
                o.initPixels(h, c.x(), c.y());
                o.vx[h] = q.vx() + dx * speed;
                o.vy[h] = q.vy() + dy * speed;
                if (c.flak() && w.params.shotHitFuelDrainUsesKineticEnergy) {
                    float r = speed / w.params.shotSpeed; // play.c:226-230
                    o.mass[h] = q.mass() / (r * r);
                }
                o.life[h] = life - aged;
                o.fuseLife[h] = life;
                states.add(new float[] {o.cx[h], o.cy[h], o.vx[h], o.vy[h], (float) o.life[h], (float) o.fuseLife[h],
                    o.mass[h]});
            }
        }
        if (seq != e.burstSeq() && bad.isEmpty()) {
            bad.add("particles: Java made " + (e.burstSeq() - seq) + " bursts more than C");
        }
        // C's Obj[] order of the new particles (XPDEB / XPOBJ slots): Java's particles are given C's states in that
        // order. When an explosion makes particles while older objects die (Delete_shot, update.c:1322-1324), several
        // new particles are moved into freed slots (object.c:97), and the order in which they later push a ship
        // (Cell lists, cell.c:229-233) changes the result.
        if (bad.isEmpty() && !states.isEmpty()) {
            Integer[] order = new Integer[states.size()];
            int[] slot = new int[states.size()];
            for (int i = 0; i < order.length; i++) {
                float[] st = states.get(i);
                Integer sl = cSlot.get((int) st[0] + "," + (int) st[1] + " " + st[2] + "," + st[3]);
                if (sl == null) {
                    bad.add("particles: C did not trace the particle at " + (int) st[0] + "," + (int) st[1] + " v"
                        + st[2] + "," + st[3]);
                    return bad;
                }
                slot[i] = sl;
                order[i] = i;
            }
            java.util.Arrays.sort(order, (a, b) -> Integer.compare(slot[a], slot[b]));
            List<Integer> js = new ArrayList<>(madeAt);
            js.sort(null);
            for (int i = 0; i < order.length; i++) {
                float[] st = states.get(order[i]);
                int h = o.active[js.get(i)];
                o.setClicks(h, (int) st[0], (int) st[1]);
                o.vx[h] = st[2];
                o.vy[h] = st[3];
                o.life[h] = st[4];
                o.fuseLife[h] = st[5];
                o.mass[h] = st[6];
            }
        }
        return bad;
    }

    /** The compared state of an explosion particle (XPDEB line or Java object). */
    static String particle(int type, int id, int cx, int cy, float vx, float vy, float mass, long life, int status) {
        return type + " #" + id + " c" + cx + "," + cy + " v" + vx + "," + vy + " m" + mass + " l" + life + " s"
            + Integer.toHexString(status & 0xFFFFFF);
    }

    /** A C colour (draw.h WHITE 1, BLUE 2, RED 3) as Java's {@link Records.ObjColor}. */
    static int color(int c) {
        return switch (c) {
            case 1 -> Records.ObjColor.WHITE;
            case 2 -> Records.ObjColor.BLUE;
            case 3 -> Records.ObjColor.RED;
            default -> -c;
        };
    }

    static List<String> jBursts(Events e, long from) {
        List<String> out = new ArrayList<>();
        for (long q = from; q < e.burstSeq(); q++) {
            int i = Events.burstIndex(q);
            out.add(burst(e.burstOwner[i], e.burstColor[i], e.burst[i]));
        }
        return out;
    }

    private static String burst(int owner, int color, DebrisBurst b) {
        return "#" + owner + " c" + color + " n" + b.count + " dir" + b.minDir + "+" + b.dirSpan + " sp" + b.minSpeed
            + ".." + b.maxSpeed + " life" + b.minLife + ".." + b.maxLife;
    }

    static List<String> jCannons(World w) {
        CannonItems ci = w.cannonItems;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < w.cannonDeadTime.length; i++) {
            if (w.cannonDeadTime[i] != 0 || ci.damaged[i] != 0 || ci.tractorCount[i] != 0 || ci.used[i] != 0
                || ci.emergencyShieldLeft[i] != 0 || ci.phasingLeft[i] != 0) {
                out.add(i + " dead" + w.cannonDeadTime[i] + " dmg" + ci.damaged[i] + " tr" + ci.tractorCount[i] + " u"
                    + Integer.toHexString(ci.used[i]) + " es" + ci.emergencyShieldLeft[i] + " ph" + ci.phasingLeft[i]);
            }
        }
        return out;
    }
}
