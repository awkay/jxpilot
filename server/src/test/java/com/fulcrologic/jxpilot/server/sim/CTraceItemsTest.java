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

import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.server.sim.CTraceItems.CEcm;
import com.fulcrologic.jxpilot.server.sim.CTraceItems.CFrame;
import com.fulcrologic.jxpilot.server.sim.CTraceItems.CObj;
import com.fulcrologic.jxpilot.server.sim.CTraceItems.CPulse;
import com.fulcrologic.jxpilot.server.world.Ecms;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Pulses;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden traces of item weapons and devices on the original C server (tools/xpilot-ref patch 0003, see
 * {@code src/test/resources/ctrace/items/README.txt}), replayed in the Java simulation and compared frame by frame
 * with no tolerance: the ship's physics and fuel, its items, abilities and tanks, and the weapon objects it made.
 * <p>
 * Replay rules (as {@link CTraceTest}): the keys of frame f come from C's own trace of frame f (thrust bit, turn
 * sign, SHIELD/LASER/cloak/phasing/emergency use bits, new objects for fire and mine keys, item counts for lost
 * items, the current tank). Values C draws at random (object lives, item pack sizes and drop offsets) are copied
 * from C into the Java object when it is made; everything after that must agree exactly.
 */
@DisplayName("C golden traces of items (default.xp)")
class CTraceItemsTest {
    /** Objects compared: missiles, mines, items, the player's own shots (C balls and cannon fire are not ours). */
    static boolean ours(CObj c) {
        return (c.type() & (Obj.SMART_SHOT | Obj.HEAT_SHOT | Obj.TORPEDO | Obj.MINE)) != 0
            || (c.type() == Obj.SHOT && c.id() == 1);
    }

    static boolean oursJava(Objects o, int h) {
        return (o.type[h] & (Obj.SMART_SHOT | Obj.HEAT_SHOT | Obj.TORPEDO | Obj.MINE)) != 0
            || (o.type[h] == Obj.SHOT && o.owner[h] != Obj.NO_ID);
    }

    /** The state compared of one object (life only when C's life is deterministic). */
    static String key(int type, int cx, int cy, int px, int py, float vx, float vy, float ax, float ay, float mass,
                      int status, long info, long count, int dir) {
        return type + " c" + cx + "," + cy + " p" + px + "," + py + " v" + vx + "," + vy + " a" + ax + "," + ay
            + " m" + mass + " s" + Integer.toHexString(status) + " i" + info + " n" + count + " d" + dir;
    }

    static String key(CObj c) {
        return key(c.type(), c.cx(), c.cy(), c.prevX(), c.prevY(), c.vx(), c.vy(), c.ax(), c.ay(), c.mass(),
            c.status() & 0xFFFFFF, c.info(), c.count(), c.dir());
    }

    static String key(Objects o, int h) {
        return key(o.type[h], o.cx[h], o.cy[h], o.prevX[h], o.prevY[h], o.vx[h], o.vy[h], o.accX[h], o.accY[h],
            o.mass[h], o.status[h] & 0xFFFFFF, o.info[h], o.counter[h], o.missileDir[h]);
    }

    static String items(int[] it, int used, int have, long fuel, long max, int tanks, int cur) {
        return java.util.Arrays.toString(it) + " used " + Integer.toHexString(used) + " have " + Integer.toHexString(have)
            + " fuel " + fuel + "/" + max + " tanks " + tanks + " cur " + cur;
    }

    /** Result of a replay: frames compared, mismatching frames, objects compared. */
    record Result(int frames, List<String> mismatches, int objects) {}

    static Result replay(String trace, String... opts) throws IOException {
        return replay(trace, Long.MAX_VALUE, opts);
    }

    /** Replay the trace's frames up to and including C frame {@code last}. */
    static Result replay(String trace, long last, String... opts) throws IOException {
        List<CFrame> c = CTraceItems.load(trace);
        c.removeIf(f -> f.frame > last);
        Simulation s = CTraceItems.world(opts);
        World w = s.world;
        Player p = CTraceItems.join(s, c.get(0));
        long[] in = new long[w.players.length];
        List<String> bad = new ArrayList<>();
        int objects = 0;
        CFrame prev = null;
        int lostItem = -1;
        int lostPack = 0;
        for (CFrame f : c) {
            long keys = f.ship.thrusting() ? ControlKey.THRUST.mask() : 0;
            keys |= f.ship.turnAcc() > 0 ? ControlKey.TURN_LEFT.mask()
                : (f.ship.turnAcc() < 0 ? ControlKey.TURN_RIGHT.mask() : 0);
            int used = f.items.used();
            int pused = prev == null ? used : prev.items.used();
            if ((used & Has.SHIELD) != 0) {
                keys |= ControlKey.SHIELD.mask();
            }
            if ((used & Has.LASER) != 0) {
                keys |= ControlKey.FIRE_LASER.mask();
            }
            if (((used ^ pused) & Has.CLOAKING_DEVICE) != 0) {
                keys |= ControlKey.CLOAK.mask();
            }
            if ((used & ~pused & Has.PHASING_DEVICE) != 0) {
                keys |= ControlKey.PHASING.mask();
            }
            if ((used & ~pused & Has.EMERGENCY_THRUST) != 0) {
                keys |= ControlKey.EMERGENCY_THRUST.mask();
            }
            if (prev != null && f.items.current() != prev.items.current()) {
                keys |= f.items.current() == (prev.items.current() + 1) % (f.items.numTanks() + 1)
                    ? ControlKey.TANK_NEXT.mask() : ControlKey.TANK_PREV.mask();
            }
            if (f.ecms.size() > (prev == null ? 0 : prev.ecms.size())) {
                keys |= ControlKey.ECM.mask();
            }
            int newTorps = 0;
            for (CObj o : f.objs) {
                if (o.type() == Obj.TORPEDO && o.info() == 0) {
                    keys |= ControlKey.FIRE_TORPEDO.mask();
                }
                if (o.type() == Obj.HEAT_SHOT && prev != null && countType(prev, Obj.HEAT_SHOT) < countType(f, Obj.HEAT_SHOT)) {
                    keys |= ControlKey.FIRE_HEAT.mask();
                }
            }
            if (prev != null && countType(f, Obj.MINE) > countType(prev, Obj.MINE)) {
                CObj m = newest(f, prev, Obj.MINE);
                keys |= (m.status() & Obj.GRAVITY) != 0 ? ControlKey.DETACH_MINE.mask() : ControlKey.DROP_MINE.mask();
            }
            if ((used & Has.SHOT) != 0) {
                keys |= ControlKey.FIRE_SHOT.mask();
            }
            if (prev != null) {
                for (int i = 0; i < Item.COUNT; i++) {
                    if (i != Item.MINE.ordinal() && i != Item.MISSILE.ordinal() && i != Item.TANK.ordinal()
                        && i != Item.FUEL.ordinal()) {
                        continue;
                    }
                    if (f.items.items()[i] < prev.items.items()[i] && (keys & (ControlKey.DROP_MINE.mask()
                        | ControlKey.DETACH_MINE.mask() | ControlKey.FIRE_TORPEDO.mask()
                        | ControlKey.FIRE_HEAT.mask())) == 0 && i != Item.TANK.ordinal()) {
                        p.loseItem = i;
                        keys |= ControlKey.LOSE_ITEM.mask();
                        // C drew the pack size at random (item.c:198): use the amount C lost
                        lostPack = prev.items.items()[i] - f.items.items()[i];
                        lostItem = i;
                    }
                }
            }
            in[p.slot] = keys;
            int before = w.objects.count();
            int min = 0;
            int max = 0;
            if (lostItem >= 0) {
                min = w.items.minPerPack[lostItem];
                max = w.items.maxPerPack[lostItem];
                w.items.minPerPack[lostItem] = lostPack;
                w.items.maxPerPack[lostItem] = lostPack;
            }
            s.tick(in);
            if (lostItem >= 0) {
                w.items.minPerPack[lostItem] = min;
                w.items.maxPerPack[lostItem] = max;
                lostItem = -1;
            }
            objects += syncNew(w, f, before);
            CTraceTest.Frame j = CTraceTest.Frame.of(f.ship.frame(), p);
            List<String> d = new ArrayList<>();
            if (!j.equals(f.ship)) {
                d.add("ship\n    C    " + f.ship + "\n    Java " + j);
            }
            String ci = items(f.items.items(), f.items.used(), f.items.have(), f.items.fuel(), f.items.fuelMax(),
                f.items.numTanks(), f.items.current());
            String ji = items(p.items, p.used, p.have, (long) p.fuel, (long) p.fuelMax, p.numTanks, p.currentTank);
            if (!ci.equals(ji)) {
                d.add("items\n    C    " + ci + "\n    Java " + ji);
            }
            List<String> co = new ArrayList<>();
            for (CObj o : f.objs) {
                if (ours(o)) {
                    co.add(key(o));
                }
            }
            List<String> jo = new ArrayList<>();
            Objects o = w.objects;
            for (int k = 0; k < o.count(); k++) {
                int h = o.active[k];
                if (oursJava(o, h) && o.life[h] > 0) {
                    jo.add(key(o, h));
                }
            }
            co.sort(null);
            jo.sort(null);
            if (!co.equals(jo)) {
                d.add("objects\n    C    " + co + "\n    Java " + jo);
            }
            List<String> cp = new ArrayList<>();
            for (CPulse q : f.pulses) {
                cp.add(q.x() + "," + q.y() + " d" + q.dir() + " l" + q.len() + " t" + q.life() + " r" + q.refl());
            }
            List<String> jp = new ArrayList<>();
            Pulses ps = w.pulses;
            for (int k = 0; k < ps.count(); k++) {
                jp.add(ps.x[k] + "," + ps.y[k] + " d" + ps.dir[k] + " l" + ps.len[k] + " t" + ps.life[k] + " r"
                    + ps.refl[k]);
            }
            cp.sort(null);
            jp.sort(null);
            if (!cp.equals(jp)) {
                d.add("pulses\n    C    " + cp + "\n    Java " + jp);
            }
            List<String> ce = new ArrayList<>();
            for (CEcm q : f.ecms) {
                ce.add(q.x() + "," + q.y() + " s" + q.size());
            }
            List<String> je = new ArrayList<>();
            Ecms es = w.ecms;
            for (int k = 0; k < es.count(); k++) {
                je.add(es.x[k] + "," + es.y[k] + " s" + es.size[k]);
            }
            ce.sort(null);
            je.sort(null);
            if (!ce.equals(je)) {
                d.add("ecms\n    C    " + ce + "\n    Java " + je);
            }
            if (!d.isEmpty()) {
                bad.add("frame " + f.frame + ": " + String.join("\n  ", d));
            }
            prev = f;
        }
        return new Result(c.size(), bad, objects);
    }

    static int countType(CFrame f, int type) {
        int n = 0;
        for (CObj o : f.objs) {
            if (o.type() == type) {
                n++;
            }
        }
        return n;
    }

    /** The object of the type in {@code f} that is at no position an object of that type had in {@code prev}. */
    static CObj newest(CFrame f, CFrame prev, int type) {
        CObj best = null;
        for (CObj o : f.objs) {
            boolean old = false;
            for (CObj q : prev.objs) {
                old |= q.type() == type && q.cx() == o.cx() && q.cy() == o.cy();
            }
            if (o.type() == type && !old) {
                best = o;
            }
        }
        return best;
    }

    /**
     * Copy what C drew at random into the objects Java made this tick: the life (and fuse life offset) of each new
     * object from the C object of the same type at the same click position.
     */
    static int syncNew(World w, CFrame f, int before) {
        Objects o = w.objects;
        int n = 0;
        for (int k = before; k < o.count(); k++) {
            int h = o.active[k];
            for (CObj c : f.objs) {
                if (c.type() == o.type[h] && c.cx() == o.cx[h] && c.cy() == o.cy[h]) {
                    double fuse = o.life[h] - o.fuseLife[h];
                    o.life[h] = c.life();
                    o.fuseLife[h] = c.life() - fuse;
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    static void check(Result r, int frames) {
        assertAll(
            () -> assertEquals(frames, r.frames(), "C frames compared"),
            () -> assertEquals(List.of(), r.mismatches(), "no frame differs from C"));
    }

    @Test
    @DisplayName("torpedoes at rest and while thrusting: ship, items and torpedoes bit-identical")
    void torpedo() throws IOException {
        Result r = replay("torpedo.txt", "initialMissiles", "4");
        check(r, 85);
        assertTrue(r.objects() >= 2, "both torpedoes were made");
    }

    @Test
    @DisplayName("heat-seeker locking on its own thrusting ship")
    void heatSeeker() throws IOException {
        // C frame 36: a (random, silenced in Java) cannon shot hits the ship; the missile flies from frame 24
        check(replay("heat-seeker.txt", 35, "initialMissiles", "4"), 35);
    }

    @Test
    @DisplayName("a dropped and a thrown mine")
    void mines() throws IOException {
        check(replay("mines.txt", "initialMines", "4", "mineFuseTime", "0"), 78);
    }

    @Test
    @DisplayName("laser pulses cut short by a wall")
    void laser() throws IOException {
        check(replay("laser.txt", "initialLasers", "2"), 66);
    }

    @Test
    @DisplayName("wideangle volleys (rear shots into the ceiling)")
    void wideangle() throws IOException {
        check(replay("wideangle-rearshot.txt", "initialWideangles", "2", "initialRearshots", "3"), 60);
    }

    @Test
    @DisplayName("rear shots into open space")
    void rearshot() throws IOException {
        check(replay("rearshot.txt", "initialRearshots", "3"), 69);
    }

    @Test
    @DisplayName("afterburners")
    void afterburner() throws IOException {
        check(replay("afterburner.txt", "initialAfterburners", "5"), 62);
    }

    @Test
    @DisplayName("emergency thrust")
    void emergencyThrust() throws IOException {
        check(replay("emergency-thrust.txt", "initialEmergencyThrusts", "1"), 66);
    }

    @Test
    @DisplayName("cloak, uncloak, phasing")
    void cloakPhasing() throws IOException {
        check(replay("cloak-phasing.txt", "initialCloaks", "1", "initialPhasings", "1"), 108);
    }

    @Test
    @DisplayName("ECM blast")
    void ecm() throws IOException {
        check(replay("ecm.txt", "initialECMs", "2"), 53);
    }

    @Test
    @DisplayName("lost mine and missile packs")
    void loseItem() throws IOException {
        // C frame 65: a (random, silenced in Java) cannon shot hits the ship after the last pack is lost
        check(replay("lose-item.txt", 64, "initialMines", "3", "initialMissiles", "2"), 64);
    }

    @Test
    @DisplayName("extra tanks: switching and fuel flow")
    void tanks() throws IOException {
        check(replay("tanks.txt", "initialTanks", "2"), 74);
    }
}
