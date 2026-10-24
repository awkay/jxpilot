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

import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.common.sim.KernelEvents;
import com.fulcrologic.jxpilot.common.sim.MoveState;
import com.fulcrologic.jxpilot.common.sim.ShipBody;
import com.fulcrologic.jxpilot.common.sim.ShipKernel;
import com.fulcrologic.jxpilot.server.net.DefaultsFile;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ship kernel run on its own ({@link ShipKernel} over the same read-only world, with client-style no-op
 * effects) against the full server simulation, phase by phase, on every shipped map: four humans pressing random
 * flight, shield, fire and item keys, and four robots, for {@value #TICKS} ticks.
 * <p>
 * The rule: every kernel phase the server runs for a ship (key handling, autofire, the flying part of the player
 * loop) is replayed from the server's exact state at the start of that phase by a separate kernel, with the same
 * key mask and the same bounce tie-break draws (recorded from the server's source), and the resulting
 * {@link ShipBody} must equal the server's player in every field, bit for bit. A phase is excluded iff
 * <ul>
 *   <li>one of the server's {@link KernelEvents} callbacks changed that ship during the phase (devices, refuelling,
 *   target repair, warps, items destroyed by a bounce, missiles and mines fired by keys, an autopilot switched off,
 *   an unpause...), or</li>
 *   <li>the kernel reported a crash, or a bounce off a target: the server's handling changes the map the kernel
 *   reads (a cannon dies, a target is damaged), so the replay, which reads the world after the phase, could not
 *   see what the server saw.</li>
 * </ul>
 * These are the interactions the kernel cannot know about. Whatever the server does to a ship between phases
 * (collisions, shoves, ECM, ball connectors, robots' steering, respawns) needs no rule: each phase starts from the
 * server's state. Two configurations per map: flight (the kernel's own keys, no items; at least 80% of fly phases
 * must be checked) and items (every device and weapon, so many phases interact; the checked ones must still
 * match).
 */
@DisplayName("Ship kernel against the server simulation (every shipped map)")
class KernelDifferentialTest {
    static final int TICKS = 1500;

    /** Flight only: the kernel's own keys, no items (robots fly, fight and refuel as usual). */
    static final ControlKey[] FLIGHT_KEYS = {
        ControlKey.THRUST, ControlKey.TURN_LEFT, ControlKey.TURN_RIGHT, ControlKey.FIRE_SHOT, ControlKey.SHIELD
    };
    static final String FLIGHT = "maxRobots: 4\nminRobots: 4\nrobotLeaveLife: 0\nlimitedLives: no\n";

    /** The outcome for one map. */
    record Result(String map, int[] checked, int[] excluded, int wallTouches, int gravityPhases, int wraps,
                  java.util.Map<String, Integer> why, List<String> mismatches) {
        String line(String config) {
            return String.format(java.util.Locale.ROOT, "KERNEL %-6s %-16s keys %6d/%6d autofire %6d/%6d fly %6d/%6d"
                    + " wallTouches %5d gravity %6d wraps %3d", config, map, checked[0], checked[0] + excluded[0],
                checked[1], checked[1] + excluded[1], checked[2], checked[2] + excluded[2], wallTouches, gravityPhases,
                wraps);
        }
    }

    /**
     * Run {@code map} for {@value #TICKS} ticks with the bundled defaults plus {@code extra}, four robots and four
     * humans flipping {@code keys} at random (the first four keys 5% per tick, the rest 1%), checking every phase.
     */
    static Result run(String map, int seed, String extra, ControlKey[] keyset) throws IOException {
        Options o = new Options();
        DefaultsFile.applyBundled(o);
        Path p = Path.of("..", "maps", map);
        if (!p.toFile().exists()) {
            p = Path.of("maps", map);
        }
        ConfigParser.parseFile(p, OptionOrigin.MAP, o);
        ConfigParser.parse(extra, "differential", OptionOrigin.COMMAND, o);
        World w = new World(o, seed);
        w.wallSeconds = () -> 1_000_000L;
        Simulation s = new Simulation(w);
        s.robots.setEnabled(true);
        for (int h = 0; h < 4; h++) {
            if (s.ships.join("human" + h, h % 2) == null) {
                break;
            }
        }
        long[] in = new long[w.players.length];
        Checker c = new Checker(s, in);
        Rng keys = new Rng(seed * 31 + 7);
        for (int t = 0; t < TICKS; t++) {
            for (int k = 0; k < w.numPlayers; k++) {
                Player pl = w.playerAt(k);
                if (!pl.robot && !pl.tank) {
                    for (int i = 0; i < keyset.length; i++) {
                        if (keys.nextFloat() < (i < 4 ? 0.05f : 0.01f)) {
                            in[pl.slot] ^= keyset[i].mask();
                        }
                    }
                }
            }
            c.tick = t;
            s.tick(in);
        }
        return new Result(map, c.checked, c.excluded, c.wallTouches, c.gravityPhases, c.wraps, c.why, c.mismatches);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("maps")
    @DisplayName("flight: every phase is reproduced bit for bit by the standalone kernel, nearly all are checked")
    void flight(String map) throws IOException {
        Result r = run(map, 5, FLIGHT, FLIGHT_KEYS);
        System.out.println(r.line("flight"));
        int fly = r.checked()[KernelEvents.PHASE_FLY];
        int flyAll = fly + r.excluded()[KernelEvents.PHASE_FLY];
        assertAll(
            () -> assertEquals(List.of(), r.mismatches(), map + ": no checked phase differs from the server"),
            () -> assertTrue(fly * 10 >= flyAll * 8, map + ": at least 80% of fly phases are checked (" + fly + " of "
                + flyAll + "; excluded by " + r.why() + ")"),
            () -> assertTrue(r.checked()[KernelEvents.PHASE_KEYS] * 10 >= (r.checked()[0] + r.excluded()[0]) * 9,
                map + ": at least 90% of key phases are checked"),
            () -> assertTrue(r.wallTouches() > 0, map + ": checked phases include wall contact"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("maps")
    @DisplayName("items: with every device and weapon in use, every checked phase is still reproduced bit for bit")
    void items(String map) throws IOException {
        Result r = run(map, 5, MapDigestParityTest.EXTRA, MapDigestParityTest.KEYS);
        System.out.println(r.line("items") + " excluded by " + r.why());
        assertAll(
            () -> assertEquals(List.of(), r.mismatches(), map + ": no checked phase differs from the server"),
            () -> assertTrue(r.checked()[KernelEvents.PHASE_FLY] > 0, map + ": fly phases were checked"),
            () -> assertTrue(r.checked()[KernelEvents.PHASE_KEYS] > 0, map + ": key phases were checked"));
    }

    static List<String> maps() {
        return List.of("blood-music2.xp", "CAMD.xp", "cloudscape.xp", "default.xp", "doggy.xp", "fireball.xp",
            "fuzz.xp", "fuzz2.xp", "globe.xp", "grandprix.xp", "newdarkhell.xp", "newdarkhell2.xp", "pad.xp", "pit.xp",
            "planetx.xp", "teamball.xp", "tourmination.xp", "tournament.xp", "war.xp");
    }

    @Test
    @DisplayName("the field comparison covers every ShipBody field (a new field must be compared too)")
    void comparisonCoversEveryField() {
        int n = 0;
        for (Field f : ShipBody.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                n++;
            }
        }
        assertEquals(Checker.COMPARED_FIELDS, n, "diff() compares each of ShipBody's instance fields");
    }

    /**
     * Wraps the server's {@link KernelEvents}: snapshots the ship at each phase start, detects server callbacks that
     * change the ship, records tie-break draws and boolean answers, and at each phase end replays the phase on a
     * copy with a standalone kernel and compares.
     */
    static final class Checker implements KernelEvents {
        static final int COMPARED_FIELDS = 49;

        final Simulation s;
        final World w;
        final KernelEvents real;
        final long[] input;
        final ShipKernel copy;
        final Replay replay = new Replay();
        final ShipBody pre = new ShipBody();
        final ShipBody k = new ShipBody();
        final ShipBody snap = new ShipBody();
        final double[] draws = new double[1024];
        int nDraws;
        int nextDraw;
        ShipBody current;
        int phase = -1;
        boolean interacted;
        int depth;
        int tick;
        final int[] checked = new int[3];
        final int[] excluded = new int[3];
        int wallTouches;
        int gravityPhases;
        int wraps;
        final List<String> mismatches = new ArrayList<>();

        Checker(Simulation s, long[] input) {
            this.s = s;
            this.w = s.world;
            this.real = s.effects;
            this.input = input;
            s.effects = this;
            s.kernel.tieBreak(() -> {
                double d = w.rfrac();
                if (phase >= 0 && nDraws < draws.length) {
                    draws[nDraws++] = d;
                }
                return d;
            });
            this.copy = new ShipKernel(w, () -> nextDraw < nDraws ? draws[nextDraw++] : Double.NaN);
        }

        @Override
        public void begin(ShipBody b, int ph) {
            current = b;
            phase = ph;
            interacted = false;
            nDraws = 0;
            replay.reset();
            calls.setLength(0);
            pre.copyFrom(b);
        }

        @Override
        public void end(ShipBody b, int ph) {
            phase = -1;
            current = null;
            if (interacted) {
                excluded[ph]++;
                return;
            }
            k.copyFrom(pre);
            nextDraw = 0;
            replay.rewind();
            switch (ph) {
                case PHASE_KEYS -> copy.keys.apply(k, input[((Player) b).slot], replay);
                case PHASE_AUTOFIRE -> copy.shooter.autofire(k, replay);
                default -> copy.fly(k, replay);
            }
            String d = diff(k, b);
            if (d == null && nextDraw != nDraws) {
                d = "tie-break draws " + nextDraw + " of " + nDraws;
            }
            if (d != null) {
                if (mismatches.size() < 5) {
                    mismatches.add("tick " + tick + " slot " + ((Player) b).slot + " phase " + ph + ": " + d
                        + " calls [" + calls + "] pre " + pre.cx + "," + pre.cy + " v " + pre.vx + "," + pre.vy
                        + " draws " + nDraws + " status " + Integer.toHexString(pre.status) + " used "
                        + Integer.toHexString(pre.used));
                }
                return;
            }
            checked[ph]++;
            if (ph == PHASE_FLY) {
                if (b.lastWallTouch == w.frames) {
                    wallTouches++;
                }
                if (w.gravityX(pre.bx, pre.by) != 0 || w.gravityY(pre.bx, pre.by) != 0) {
                    gravityPhases++;
                }
                if (Math.abs(b.cx - pre.cx) > w.clickWidth / 2 || Math.abs(b.cy - pre.cy) > w.clickHeight / 2) {
                    wraps++;
                }
            }
        }

        /** Before a forwarded server callback: snapshot the ship being stepped (outermost call only). */
        final StringBuilder calls = new StringBuilder();

        private boolean enter(ShipBody b) {
            if (phase >= 0 && b == current) {
                calls.append(hook).append(' ');
            }
            if (phase < 0 || b != current || depth++ > 0) {
                return false;
            }
            snap.copyFrom(b);
            return true;
        }

        /** After it: a changed ship means the server interacted. */
        /** Why phases were excluded: phase:callback:first changed field, counted. */
        final java.util.Map<String, Integer> why = new java.util.TreeMap<>();
        String hook;

        private void exit(ShipBody b, boolean outer) {
            if (outer) {
                depth = 0;
                String d = diff(snap, b);
                if (d != null) {
                    interacted = true;
                    String key = phase + ":" + hook + ":" + d.split(" ")[0];
                    why.merge(key, 1, Integer::sum);
                }
            } else if (depth > 0) {
                depth--;
            }
        }

        @Override
        public boolean keyPressed(ShipBody b, ControlKey key) {
            hook = "keyPressed";
            boolean o = enter(b);
            boolean r = real.keyPressed(b, key);
            exit(b, o);
            replay.record(r);
            return r;
        }

        @Override
        public void keyReleased(ShipBody b, ControlKey key) {
            hook = "keyReleased";
            boolean o = enter(b);
            real.keyReleased(b, key);
            exit(b, o);
        }

        @Override
        public void autopilotOff(ShipBody b) {
            hook = "autopilotOff";
            boolean o = enter(b);
            real.autopilotOff(b);
            exit(b, o);
        }

        @Override
        public void powerChanged(ShipBody b) {
            hook = "powerChanged";
            boolean o = enter(b);
            real.powerChanged(b);
            exit(b, o);
        }

        @Override
        public boolean shotRoom() {
            boolean r = real.shotRoom();
            if (phase >= 0) {
                replay.record(r);
            }
            return r;
        }

        @Override
        public int shot(ShipBody b, float x, float y, int dir, float vx, float vy, boolean outside) {
            hook = "shot";
            boolean o = enter(b);
            int h = real.shot(b, x, y, dir, vx, vy, outside);
            exit(b, o);
            return h;
        }

        @Override
        public void devices(ShipBody b) {
            hook = "devices";
            boolean o = enter(b);
            real.devices(b);
            exit(b, o);
        }

        @Override
        public void upkeep(ShipBody b) {
            hook = "upkeep";
            boolean o = enter(b);
            real.upkeep(b);
            exit(b, o);
        }

        @Override
        public boolean warp(ShipBody b) {
            hook = "warp";
            boolean o = enter(b);
            boolean r = real.warp(b);
            exit(b, o);
            if (r) {
                interacted = true;
            }
            return r;
        }

        @Override
        public void thrustSparks(ShipBody b) {
            hook = "thrustSparks";
            boolean o = enter(b);
            real.thrustSparks(b);
            exit(b, o);
        }

        @Override
        public void turnSparks(ShipBody b, int count) {
            hook = "turnSparks";
            boolean o = enter(b);
            real.turnSparks(b, count);
            exit(b, o);
        }

        @Override
        public void sensors(ShipBody b) {
            hook = "sensors";
            boolean o = enter(b);
            real.sensors(b);
            exit(b, o);
        }

        @Override
        public void crashed(ShipBody b, MoveState m, int point) {
            hook = "crashed";
            boolean o = enter(b);
            real.crashed(b, m, point);
            exit(b, o);
            worldChanged(b);
        }

        @Override
        public void wallDamage(ShipBody b) {
            hook = "wallDamage";
            boolean o = enter(b);
            real.wallDamage(b);
            exit(b, o);
        }

        @Override
        public void bounced(ShipBody b, int wallDir, long cost, int target) {
            hook = "bounced";
            boolean o = enter(b);
            real.bounced(b, wallDir, cost, target);
            exit(b, o);
            if (target >= 0) {
                worldChanged(b);
            }
        }

        /** The server's handling may have changed the map the kernel reads (a cannon died, a target was hit). */
        private void worldChanged(ShipBody b) {
            if (phase >= 0 && b == current) {
                interacted = true;
                why.merge(phase + ":" + hook + ":world", 1, Integer::sum);
            }
        }
    }

    /** Client-style effects for the standalone kernel: no-ops, replaying the server's boolean answers in order. */
    static final class Replay implements KernelEvents {
        private final boolean[] answers = new boolean[256];
        private int n;
        private int next;

        void reset() {
            n = 0;
        }

        void rewind() {
            next = 0;
        }

        void record(boolean a) {
            if (n < answers.length) {
                answers[n++] = a;
            }
        }

        private boolean answer() {
            return next < n && answers[next++];
        }

        @Override
        public boolean keyPressed(ShipBody b, ControlKey key) {
            return answer();
        }

        @Override
        public void keyReleased(ShipBody b, ControlKey key) {
        }

        @Override
        public void autopilotOff(ShipBody b) {
        }

        @Override
        public void powerChanged(ShipBody b) {
        }

        @Override
        public boolean shotRoom() {
            return answer();
        }

        @Override
        public int shot(ShipBody b, float x, float y, int dir, float vx, float vy, boolean outside) {
            return -1;
        }

        @Override
        public void devices(ShipBody b) {
        }

        @Override
        public void upkeep(ShipBody b) {
        }

        @Override
        public boolean warp(ShipBody b) {
            return false;
        }

        @Override
        public void thrustSparks(ShipBody b) {
        }

        @Override
        public void turnSparks(ShipBody b, int count) {
        }

        @Override
        public void sensors(ShipBody b) {
        }

        @Override
        public void crashed(ShipBody b, MoveState m, int point) {
        }

        @Override
        public void wallDamage(ShipBody b) {
        }

        @Override
        public void bounced(ShipBody b, int wallDir, long cost, int target) {
        }
    }

    /** The first ShipBody field that differs bit for bit, or null. */
    static String diff(ShipBody a, ShipBody b) {
        if (a.cx != b.cx) return "cx " + a.cx + " vs " + b.cx;
        if (a.cy != b.cy) return "cy " + a.cy + " vs " + b.cy;
        if (a.px != b.px) return "px";
        if (a.py != b.py) return "py";
        if (a.bx != b.bx) return "bx";
        if (a.by != b.by) return "by";
        if (a.prevX != b.prevX) return "prevX";
        if (a.prevY != b.prevY) return "prevY";
        if (f(a.vx) != f(b.vx)) return "vx " + a.vx + " vs " + b.vx;
        if (f(a.vy) != f(b.vy)) return "vy " + a.vy + " vs " + b.vy;
        if (f(a.accX) != f(b.accX)) return "accX";
        if (f(a.accY) != f(b.accY)) return "accY";
        if (f(a.velocity) != f(b.velocity)) return "velocity";
        if (f(a.mass) != f(b.mass)) return "mass " + a.mass + " vs " + b.mass;
        if (f(a.emptyMass) != f(b.emptyMass)) return "emptyMass";
        if (a.status != b.status) return "status " + Integer.toHexString(a.status) + " vs " + Integer.toHexString(b.status);
        if (a.have != b.have) return "have";
        if (a.used != b.used) return "used " + Integer.toHexString(a.used) + " vs " + Integer.toHexString(b.used);
        if (a.dir != b.dir) return "dir " + a.dir + " vs " + b.dir;
        if (f(a.floatDir) != f(b.floatDir)) return "floatDir";
        if (f(a.turnVel) != f(b.turnVel)) return "turnVel";
        if (f(a.oldTurnVel) != f(b.oldTurnVel)) return "oldTurnVel";
        if (f(a.turnAcc) != f(b.turnAcc)) return "turnAcc";
        if (f(a.turnSpeed) != f(b.turnSpeed)) return "turnSpeed";
        if (f(a.turnResistance) != f(b.turnResistance)) return "turnResistance";
        if (f(a.power) != f(b.power)) return "power";
        if (f(a.powerS) != f(b.powerS)) return "powerS";
        if (f(a.turnSpeedS) != f(b.turnSpeedS)) return "turnSpeedS";
        if (f(a.turnResistanceS) != f(b.turnResistanceS)) return "turnResistanceS";
        if (d(a.fuel) != d(b.fuel)) return "fuel " + a.fuel + " vs " + b.fuel;
        if (d(a.fuelMax) != d(b.fuelMax)) return "fuelMax";
        for (int i = 0; i < a.tankFuel.length; i++) {
            if (d(a.tankFuel[i]) != d(b.tankFuel[i])) return "tankFuel[" + i + "]";
        }
        if (a.numTanks != b.numTanks) return "numTanks";
        if (a.currentTank != b.currentTank) return "currentTank";
        if (a.shieldTime != b.shieldTime) return "shieldTime";
        if (a.stunned != b.stunned) return "stunned";
        if (a.shots != b.shots) return "shots";
        if (a.shotMax != b.shotMax) return "shotMax";
        if (a.shotTime != b.shotTime) return "shotTime";
        if (a.mods != b.mods) return "mods";
        if (a.team != b.team) return "team";
        for (int i = 0; i < a.items.length; i++) {
            if (a.items[i] != b.items[i]) return "items[" + i + "]";
        }
        if (a.wormHoleDest != b.wormHoleDest) return "wormHoleDest";
        if (a.lastWallTouch != b.lastWallTouch) return "lastWallTouch";
        if (a.frameLastBusy != b.frameLastBusy) return "frameLastBusy";
        if (a.prevKeys != b.prevKeys) return "prevKeys";
        if (a.lastReceivedKeys != b.lastReceivedKeys) return "lastReceivedKeys";
        if (a.keysCleared != b.keysCleared) return "keysCleared";
        if (a.ship != b.ship) return "ship";
        return null;
    }

    private static int f(float v) {
        return Float.floatToRawIntBits(v);
    }

    private static long d(double v) {
        return Double.doubleToRawLongBits(v);
    }
}
