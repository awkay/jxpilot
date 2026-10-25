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
import com.fulcrologic.jxpilot.common.sim.KernelWorld;
import com.fulcrologic.jxpilot.common.sim.MoveState;
import com.fulcrologic.jxpilot.common.sim.ShipBody;
import com.fulcrologic.jxpilot.common.sim.ShipKernel;
import com.fulcrologic.jxpilot.common.sim.ShipParams;
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
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ship kernel run on its own, with client-style no-op effects, against the full server simulation, phase by
 * phase, on every shipped map: four humans pressing random flight, shield, fire and item keys, and four robots, for
 * {@value #TICKS} ticks.
 * <p>
 * The rule: every kernel phase the server runs for a ship (key handling, autofire, the flying part of the player
 * loop) is replayed from the server's exact state at the start of that phase, with the same key mask and the same
 * bounce tie-break draws (recorded from the server's source), twice:
 * <ul>
 *   <li>the client path: the state is encoded with {@link ShipBody#encode} and decoded into a fresh body (only the
 *   hull carried over), and run by a kernel over a {@link KernelWorld} built from {@link ShipParams#of} the same
 *   options, with the dynamic state a snapshot would carry mirrored from the server (frame clock, round delay,
 *   cannon phasing, target teams, the wormhole table); the result must be {@link ShipBody#sameState} with the
 *   server's player (every wire field bit for bit). The map itself is the server's live map (its block changes are
 *   what a client must be sent; 1b);</li>
 *   <li>the server path: a copy of every field run by a kernel over the server's own world; the result must equal
 *   the server's player in every one of its 49 fields.</li>
 * </ul>
 * A phase is excluded iff
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
 * must be checked) and items (every device and weapon, temporary wormholes from hyperjumps; many phases interact,
 * at least 10% of fly phases must still be checked, with wall contact, and match).
 */
@DisplayName("Ship kernel against the server simulation (every shipped map)")
class KernelDifferentialTest {
    static final int TICKS = 1500;

    /** Flight only: the kernel's own keys, no items (robots fly, fight and refuel as usual). */
    static final ControlKey[] FLIGHT_KEYS = {
        ControlKey.THRUST, ControlKey.TURN_LEFT, ControlKey.TURN_RIGHT, ControlKey.FIRE_SHOT, ControlKey.SHIELD
    };
    static final String FLIGHT = "maxRobots: 4\nminRobots: 4\nrobotLeaveLife: 0\nlimitedLives: no\n";
    /** Every device and weapon (the parity test's item world) plus temporary wormholes from hyperjumps. */
    static final String ITEMS = MapDigestParityTest.EXTRA + "wormTime: 3\n";

    /** The outcome for one map. */
    record Result(String map, int[] checked, int[] excluded, int wallTouches, int gravityPhases, int wraps,
                  int tempWormholePhases, java.util.Map<String, Integer> why, List<String> mismatches,
                  long fuelChecks, List<String> fuelErrors) {
        String line(String config) {
            return String.format(java.util.Locale.ROOT, "KERNEL %-6s %-16s keys %6d/%6d autofire %6d/%6d fly %6d/%6d"
                    + " wallTouches %5d gravity %6d wraps %3d tempWormholes %5d", config, map, checked[0],
                checked[0] + excluded[0], checked[1], checked[1] + excluded[1], checked[2], checked[2] + excluded[2],
                wallTouches, gravityPhases, wraps, tempWormholePhases);
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
        Checker c = new Checker(s, in, o);
        Rng keys = new Rng(seed * 31 + 7);
        long fuelChecks = 0;
        List<String> fuelErrors = new ArrayList<>();
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
            for (int k = 0; k < w.numPlayers; k++) {
                Player pl = w.playerAt(k);
                fuelChecks++;
                if (!wireFuel(pl.fuel) || !wireFuel(pl.fuelMax)) {
                    if (fuelErrors.size() < 5) {
                        fuelErrors.add("tick " + t + " slot " + pl.slot + " fuel " + pl.fuel + " fuelMax " + pl.fuelMax);
                    }
                }
            }
        }
        return new Result(map, c.checked, c.excluded, c.wallTouches, c.gravityPhases, c.wraps, c.tempWormholePhases,
            c.why, c.mismatches, fuelChecks, fuelErrors);
    }

    /**
     * Fuel crosses the wire as a u24 integer (PREDICT): exact only while it stays an integer in 0..MAX_FUEL, as C's
     * long fuel (1/256 units) always is.
     */
    static boolean wireFuel(double f) {
        return f == Math.rint(f) && f >= 0 && f <= ShipBody.MAX_FUEL;
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
            () -> assertTrue(r.wallTouches() > 0, map + ": checked phases include wall contact"),
            () -> assertEquals(List.of(), r.fuelErrors(), map + ": fuel and fuelMax stay integers within u24"),
            () -> assertTrue(r.fuelChecks() > 0, map + ": fuel was checked"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("maps")
    @DisplayName("items: with every device and weapon in use, every checked phase is still reproduced bit for bit")
    void items(String map) throws IOException {
        Result r = run(map, 5, ITEMS, MapDigestParityTest.KEYS);
        System.out.println(r.line("items") + " excluded by " + r.why());
        int fly = r.checked()[KernelEvents.PHASE_FLY];
        int flyAll = fly + r.excluded()[KernelEvents.PHASE_FLY];
        assertAll(
            () -> assertEquals(List.of(), r.mismatches(), map + ": no checked phase differs from the server"),
            () -> assertTrue(fly * 10 >= flyAll, map + ": at least 10% of fly phases are checked (" + fly + " of "
                + flyAll + ")"),
            () -> assertTrue(r.wallTouches() > 0, map + ": checked phases include wall contact"),
            () -> assertTrue(r.checked()[KernelEvents.PHASE_KEYS] > 0, map + ": key phases were checked"),
            () -> assertEquals(List.of(), r.fuelErrors(),
                map + ": with tanks, refuelling and item pickups, fuel and fuelMax stay integers within u24"),
            () -> assertTrue(r.fuelChecks() > 0, map + ": fuel was checked"));
    }

    static List<String> maps() {
        return List.of("blood-music2.xp", "CAMD.xp", "cloudscape.xp", "default.xp", "doggy.xp", "fireball.xp",
            "fuzz.xp", "fuzz2.xp", "globe.xp", "grandprix.xp", "newdarkhell.xp", "newdarkhell2.xp", "pad.xp", "pit.xp",
            "planetx.xp", "teamball.xp", "tourmination.xp", "tournament.xp", "war.xp");
    }

    /**
     * Every ShipBody field, classified: on the wire (the prediction state {@link ShipBody#encode} carries and
     * {@link ShipBody#sameState} compares) or off it (derived from the clicks, written but never read by the kernel,
     * or the hull). A new field fails {@link #everyFieldClassified} until it is added here.
     */
    static final java.util.Map<String, Boolean> ON_WIRE = java.util.Map.ofEntries(
        java.util.Map.entry("cx", true), java.util.Map.entry("cy", true), java.util.Map.entry("px", false),
        java.util.Map.entry("py", false), java.util.Map.entry("bx", false), java.util.Map.entry("by", false),
        java.util.Map.entry("prevX", false), java.util.Map.entry("prevY", false), java.util.Map.entry("vx", true),
        java.util.Map.entry("vy", true), java.util.Map.entry("accX", false), java.util.Map.entry("accY", false),
        java.util.Map.entry("velocity", true), java.util.Map.entry("mass", true),
        java.util.Map.entry("emptyMass", true), java.util.Map.entry("status", true), java.util.Map.entry("have", true),
        java.util.Map.entry("used", true), java.util.Map.entry("dir", true), java.util.Map.entry("floatDir", true),
        java.util.Map.entry("turnVel", true), java.util.Map.entry("oldTurnVel", true),
        java.util.Map.entry("turnAcc", true), java.util.Map.entry("turnSpeed", true),
        java.util.Map.entry("turnResistance", true), java.util.Map.entry("power", true),
        java.util.Map.entry("powerS", true), java.util.Map.entry("turnSpeedS", true),
        java.util.Map.entry("turnResistanceS", true), java.util.Map.entry("fuel", true),
        java.util.Map.entry("fuelMax", true), java.util.Map.entry("tankFuel", false),
        java.util.Map.entry("numTanks", false), java.util.Map.entry("currentTank", false),
        java.util.Map.entry("shieldTime", true), java.util.Map.entry("stunned", true), java.util.Map.entry("shots", true),
        java.util.Map.entry("shotMax", true), java.util.Map.entry("shotTime", true), java.util.Map.entry("mods", true),
        java.util.Map.entry("team", true), java.util.Map.entry("items", true), java.util.Map.entry("wormHoleDest", true),
        java.util.Map.entry("lastWallTouch", false), java.util.Map.entry("frameLastBusy", false),
        java.util.Map.entry("prevKeys", true), java.util.Map.entry("lastReceivedKeys", true),
        java.util.Map.entry("keysCleared", true), java.util.Map.entry("ship", false));

    @Test
    @DisplayName("every ShipBody field is classified on or off the wire, and the codec agrees with the classification")
    void everyFieldClassified() throws IllegalAccessException {
        java.util.Set<String> declared = new java.util.TreeSet<>();
        List<String> wrong = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.allocate(ShipBody.WIRE_SIZE);
        for (Field f : ShipBody.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            declared.add(f.getName());
            ShipBody a = new ShipBody();
            setDistinct(f, a);
            a.encode(buf, 0, 1000);
            ShipBody b = new ShipBody();
            b.decode(buf, 0, 1000);
            boolean carried = sameField(f, a, b);
            boolean changed = !sameField(f, a, new ShipBody());
            if (!changed || (ON_WIRE.containsKey(f.getName()) && carried != ON_WIRE.get(f.getName()))) {
                wrong.add(f.getName() + (changed ? " carried=" + carried : " (could not set)"));
            }
        }
        assertAll(
            () -> assertEquals(new java.util.TreeSet<>(ON_WIRE.keySet()), declared,
                "every ShipBody instance field is classified, and only those"),
            () -> assertEquals(List.of(), wrong, "a field crosses the wire iff it is classified on the wire"));
    }

    /** Give field {@code f} of {@code b} a value different from a fresh body's, small enough for any wire width. */
    private static void setDistinct(Field f, ShipBody b) throws IllegalAccessException {
        Class<?> t = f.getType();
        if (t == int.class) {
            f.setInt(b, 77);
        } else if (t == long.class) {
            f.setLong(b, 77L);
        } else if (t == float.class) {
            f.setFloat(b, 1.5f);
        } else if (t == double.class) {
            f.setDouble(b, 2.0); // the double fields are fuel, which is integral (C 1/256 units, u24 on the wire)
        } else if (t == boolean.class) {
            f.setBoolean(b, true);
        } else if (t == int[].class) {
            ((int[]) f.get(b))[0] = 7;
        } else if (t == double[].class) {
            ((double[]) f.get(b))[0] = 7;
        } else if (f.getName().equals("ship")) {
            f.set(b, com.fulcrologic.jxpilot.common.sim.ShipGeom.parse(
                "(SH: 10,0 0,10 -10,0 0,-10)(EN: -10,0)(MG: 10,0)"));
        }
    }

    private static boolean sameField(Field f, ShipBody a, ShipBody b) throws IllegalAccessException {
        Object x = f.get(a);
        Object y = f.get(b);
        if (x instanceof int[] xi) {
            return java.util.Arrays.equals(xi, (int[]) y);
        }
        if (x instanceof double[] xd) {
            return java.util.Arrays.equals(xd, (double[]) y);
        }
        if (x instanceof Float xf) {
            return Float.floatToRawIntBits(xf) == Float.floatToRawIntBits((Float) y);
        }
        if (x instanceof Double xd) {
            return Double.doubleToRawLongBits(xd) == Double.doubleToRawLongBits((Double) y);
        }
        return f.getType().isPrimitive() ? x.equals(y) : x == y;
    }

    /** The first wire field that differs (for a client-path mismatch message). */
    static String wireDiff(ShipBody a, ShipBody b) {
        try {
            for (Field f : ShipBody.class.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && Boolean.TRUE.equals(ON_WIRE.get(f.getName()))
                    && !sameField(f, a, b)) {
                    return f.getName() + " " + (f.getType().isArray() ? "differs" : f.get(a) + " vs " + f.get(b));
                }
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        return "no wire field differs";
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
        /** The client path: a kernel over a KernelWorld from ShipParams.of, fed decoded wire state. */
        final KernelWorld kw;
        final ShipKernel client;
        final ByteBuffer wire = ByteBuffer.allocate(ShipBody.WIRE_SIZE);
        int tempWormholePhases;
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

        Checker(Simulation s, long[] input, Options options) {
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
            this.kw = new KernelWorld(w.map, ShipParams.of(options, w.map));
            this.client = new ShipKernel(kw, () -> nextDraw < nDraws ? draws[nextDraw++] : Double.NaN);
        }

        /** What a snapshot would carry for the client's world view, copied from the server now. */
        private void mirror() {
            kw.frames = w.frames;
            kw.roundDelay = w.roundDelay;
            for (int i = 0; i < kw.cannonPhased.length; i++) {
                kw.cannonPhased[i] = w.cannonPhased(i);
            }
            for (int i = 0; i < kw.targetTeam.length; i++) {
                kw.targetTeam[i] = w.targetTeam(i);
            }
            kw.setWormholeCount(w.worms.count());
            int cap = w.worms.capacity();
            for (int i = 0; i < cap; i++) {
                if (i >= kw.mapWormholes()) {
                    kw.setTempWormhole(i, w.worms.bx(i), w.worms.by(i), w.worms.type(i));
                }
                kw.setWormholeState(i, w.worms.lastDest(i), w.worms.countdown(i));
            }
        }

        /** Run phase {@code ph} of {@code kernel} on {@code body} with the replayed draws and answers. */
        private void replayPhase(ShipKernel kernel, ShipBody body, int ph, int slot) {
            nextDraw = 0;
            replay.rewind();
            switch (ph) {
                case PHASE_KEYS -> kernel.keys.apply(body, input[slot], replay);
                case PHASE_AUTOFIRE -> kernel.shooter.autofire(body, replay);
                default -> kernel.fly(body, replay);
            }
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
            int slot = ((Player) b).slot;
            k.copyFrom(pre);
            replayPhase(copy, k, ph, slot);
            String d = diff(k, b);
            if (d == null && nextDraw != nDraws) {
                d = "tie-break draws " + nextDraw + " of " + nDraws;
            }
            if (d == null) {
                ShipBody fresh = new ShipBody();
                fresh.ship = pre.ship;
                pre.encode(wire, 0, w.frames);
                fresh.decode(wire, 0, w.frames);
                mirror();
                replayPhase(client, fresh, ph, slot);
                if (!fresh.sameState(b, w.frames)) {
                    d = "client path: " + wireDiff(fresh, b);
                } else if (nextDraw != nDraws) {
                    d = "client path: tie-break draws " + nextDraw + " of " + nDraws;
                }
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
            if (w.worms.tempCount() > 0) {
                tempWormholePhases++;
            }
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
