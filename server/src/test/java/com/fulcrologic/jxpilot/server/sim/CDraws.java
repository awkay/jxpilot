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

import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.server.sim.CTraceItems.CDraw;
import com.fulcrologic.jxpilot.server.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Replays the C server's random draws (patch 0006 XPRAND lines) into a Java {@link World} ({@link World#replay}):
 * every Java draw is matched, by its Java call site, to the next unused C draw of the same tick made by the C
 * function that the Java method ports. Java draws whose site C does not have (robots, the burst seeds) come from a
 * private RNG. Differences in the draw structure are recorded as problems: a Java draw C did not make, a C draw of
 * a ported function that Java did not make, or an rfrac() met by a randomMT() (and the reverse).
 */
final class CDraws implements World.Draws {
    /** Java method (simple class name and method) to the C draws it ports. */
    static final Map<String, Predicate<CDraw>> SITES = Map.ofEntries(
        Map.entry("Items.spawn", at("Update_objects", "update.c:520")),
        Map.entry("Items.chooseRandom", func("Choose_random_item")),
        Map.entry("Items.place", func("Place_item")),
        Map.entry("Items.packSize", func("Place_item")),
        Map.entry("Items.make", func("Make_item")),
        Map.entry("Items.detonate", func("Detonate_items")),
        Map.entry("Items.damage", func("Item_damage")),
        Map.entry("Items.delete", func("Delete_shot")),
        Map.entry("Items.pickup", func("Player_collides_with_item")),
        Map.entry("Debris.make", d -> "Make_debris".equals(d.func()) && d.first()),
        // the particle draws of the Make_debris call whose count Debris.make took last (see take)
        Map.entry("Debris.cParticles", func("Make_debris")),
        Map.entry("Debris.cThrust", func("Thrust")),
        Map.entry("Wreckage.make", func("xpref_wreck_rfrac")),
        Map.entry("Missiles.fire", func("Fire_general_shot")),
        Map.entry("Missiles.move", func("Move_smart_shot")),
        Map.entry("Mines.placeGeneral", func("Place_general_mine")),
        Map.entry("Shots.fire", func("Fire_general_shot")),
        Map.entry("Ecm.fireGeneral", func("Fire_general_ecm")),
        Map.entry("Lasers.hitPlayer", func("Laser_pulse_hits_player")),
        Map.entry("Locks.updateVisibility", at("Update_objects", "update.c:948", "update.c:953", "update.c:954")),
        Map.entry("ShipMover.traceShip", func("Move_player")),
        Map.entry("Segment.resolveBounce", func("Move_segment")),
        Map.entry("Crashes.playerCrashed", func("Player_crash")),
        Map.entry("Collisions.killingShot", func("Player_collides_with_killing_shot")),
        Map.entry("Ships.pickStartPos", func("Pick_startpos")),
        Map.entry("Ships.warp", at("Update_objects", "update.c:1080", "update.c:1082", "update.c:1125",
            "update.c:1140", "update.c:1141")),
        Map.entry("Ships.nearestAhead", at("Update_objects", "update.c:1080", "update.c:1082", "update.c:1125")),
        Map.entry("Ships.goHome", func("Go_home")),
        Map.entry("Balls.ecmDetach", func("Fire_general_ecm")),
        Map.entry("Ships.deathReset", func("Player_death_reset")),
        Map.entry("Cannons.update", at("Update_objects", "update.c:636")),
        Map.entry("CannonWeapons.defend", at("Update_objects", "update.c:629")),
        Map.entry("CannonWeapons.pickItem", at("Update_objects", "update.c:642", "update.c:646", "update.c:649")),
        Map.entry("CannonWeapons.selectWeapon", func("Cannon_select_weapon")),
        Map.entry("CannonWeapons.aimRange", func("Cannon_aim")),
        Map.entry("CannonWeapons.hidden", func("Cannon_aim")),
        Map.entry("Cannons.aim", func("Cannon_aim")),
        Map.entry("CannonWeapons.fire", func("Cannon_fire")),
        Map.entry("CannonWeapons.throwItems", func("Cannon_throw_items")),
        Map.entry("Devices.generalTransporter", func("Do_general_transporter")),
        Map.entry("Asteroids.make", func("Make_asteroid")),
        Map.entry("Asteroids.place", func("Place_asteroid")),
        Map.entry("Asteroids.update", func("Asteroid_update")),
        Map.entry("Asteroids.broken", func("Break_asteroid")));

    private static final StackWalker WALKER = StackWalker.getInstance();

    private final Rng fallback = new Rng(12345);
    private List<CDraw> draws = List.of();
    private boolean[] used = new boolean[0];
    /** Index of the count draw of the Make_debris call Java is making particles for, or -1. */
    private int call = -1;
    /** Draws of that call taken so far (the count included). */
    private int callTaken;
    private long frame;
    /** Draws matched to C so far. */
    int matched;
    final List<String> problems = new ArrayList<>();

    static Predicate<CDraw> func(String f) {
        return d -> f.equals(d.func());
    }

    static Predicate<CDraw> at(String f, String... sites) {
        List<String> s = List.of(sites);
        return d -> f.equals(d.func()) && s.contains(d.site());
    }

    /** Start the tick of C frame {@code f}: its draws are the ones Java may take. */
    void begin(long f, List<CDraw> d) {
        frame = f;
        draws = d;
        used = new boolean[d.size()];
        call = -1;
    }

    /** End the tick: a C draw of a ported function that Java did not take is a problem. */
    List<String> end() {
        List<String> left = new ArrayList<>();
        for (int i = 0; i < draws.size(); i++) {
            CDraw d = draws.get(i);
            if (!used[i] && ported(d)) {
                left.add(d.func() + " " + d.site() + (d.caller() != null ? " <" + d.caller() : ""));
            }
        }
        if (!left.isEmpty()) {
            problems.add("frame " + frame + ": C drew, Java did not: " + left);
        }
        draws = List.of();
        return left;
    }

    private static boolean ported(CDraw d) {
        for (Predicate<CDraw> p : SITES.values()) {
            if (p.test(d)) {
                return true;
            }
        }
        return false;
    }

    /** The Java method that drew: the first server or kernel frame outside World whose method is ported. */
    private static String site() {
        Optional<String> s = WALKER.walk(st -> st
            .filter(f -> (f.getClassName().startsWith("com.fulcrologic.jxpilot.server.")
                || f.getClassName().startsWith("com.fulcrologic.jxpilot.common.sim."))
                && !f.getClassName().endsWith(".World"))
            .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1) + "." + f.getMethodName())
            .filter(SITES::containsKey)
            .findFirst());
        return s.orElse(null);
    }

    private CDraw take(boolean real) {
        String s = site();
        if (s == null) {
            return null;
        }
        if (s.equals("Debris.cParticles")) {
            return particle(real);
        }
        Predicate<CDraw> p = SITES.get(s);
        for (int i = 0; i < draws.size(); i++) {
            if (!used[i] && p.test(draws.get(i))) {
                used[i] = true;
                CDraw d = draws.get(i);
                if (d.real() != real) {
                    problems.add("frame " + frame + ": " + s + " drew " + (real ? "rfrac()" : "randomMT()")
                        + " where C drew " + (d.real() ? "rfrac()" : "randomMT()") + " at " + d.site());
                }
                matched++;
                if (s.equals("Debris.make")) {
                    call = i;
                    callTaken = 1;
                }
                return d;
            }
        }
        problems.add("frame " + frame + ": Java drew in " + s + ", C did not");
        return null;
    }

    /**
     * A particle draw of C's Make_debris (Debris.cParticles): the next draw of the call whose count Debris.make
     * took. A thrust exhaust's particle draws may have been left out of the trace (compact-rand.sh): then the
     * draw is the fallback's, as Java's own exhaust would be, without a problem.
     */
    private CDraw particle(boolean real) {
        int i = call + callTaken;
        if (call >= 0 && i < draws.size() && !used[i] && !draws.get(i).first()
            && "Make_debris".equals(draws.get(i).func())
            && java.util.Objects.equals(draws.get(i).caller(), draws.get(call).caller())) {
            used[i] = true;
            callTaken++;
            matched++;
            return draws.get(i);
        }
        if (call < 0 || !"Thrust".equals(draws.get(call).caller())) {
            problems.add("frame " + frame + ": Java drew a particle in Debris.cParticles, C did not");
        }
        return null;
    }

    @Override
    public double rfrac() {
        CDraw d = take(true);
        return d == null ? fallback.nextFloat() : d.rfrac();
    }

    @Override
    public int randomMT() {
        CDraw d = take(false);
        return d == null ? fallback.nextInt() : (int) d.value();
    }
}
