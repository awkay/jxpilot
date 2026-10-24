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
import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.map.Cannon;
import com.fulcrologic.jxpilot.server.net.DefaultsFile;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden trace of C wreckage (resources/ctrace/wreckage-default.txt, patch 0003): every {@code Make_wreckage}
 * of a 180 s C game on default.xp (4 robots, lib/defaults) is replayed in Java with C's own {@code rfrac()}
 * draws, then the pieces fly frame by frame and every C frame is compared: click position, velocity, life,
 * rotation (exact), plus mass, size, shape and turn speed at creation. The RNGs differ by design (see
 * DEVIATIONS-server.md), so the draws are taken from the trace; everything else is computed by Java.
 * <p>
 * C pieces that disappear while they still had life left were hit by a ship in the C game (Java replays
 * the wreckage alone); they are counted and removed from the Java world at the same frame.
 */
class WreckageGoldenTest {

    /** One Make_wreckage call. */
    record Make(int frame, String[] args, double[] draws) {}

    /** One wreckage object in one C frame. */
    record Piece(String ptr, int cx, int cy, float vx, float vy, long life, float mass, int size, int info,
                 int rotation, float turnSpeed) {}

    static float f(String hex) {
        return (float) Double.parseDouble(hex);
    }

    @Test
    @DisplayName("every C wreckage piece is made and flies bit for bit like C (creation values and every frame)")
    void golden() throws IOException {
        List<Make> makes = new ArrayList<>();
        Map<Integer, List<Piece>> frames = new LinkedHashMap<>();
        try (InputStream in = getClass().getResourceAsStream("/ctrace/wreckage-default.txt")) {
            String text = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            List<Double> draws = null;
            String[] args = null;
            int makeFrame = 0;
            for (String line : text.split("\n")) {
                String[] t = line.trim().split(" ");
                switch (t[0]) {
                    case "XPWMAKE" -> {
                        if (args != null) {
                            makes.add(new Make(makeFrame, args, draws.stream().mapToDouble(Double::doubleValue).toArray()));
                        }
                        makeFrame = Integer.parseInt(t[1]);
                        args = t;
                        draws = new ArrayList<>();
                    }
                    case "XPWRAND" -> draws.add(Double.parseDouble(t[1]));
                    case "XPWRECK" -> frames.computeIfAbsent(Integer.parseInt(t[2]), k -> new ArrayList<>())
                        .add(new Piece(t[3], Integer.parseInt(t[4]), Integer.parseInt(t[5]), f(t[6]), f(t[7]),
                            Long.parseLong(t[8]), f(t[9]), Integer.parseInt(t[10]), Integer.parseInt(t[11]),
                            Integer.parseInt(t[12]), f(t[13])));
                    default -> throw new AssertionError("unexpected line " + line);
                }
            }
            makes.add(new Make(makeFrame, args, draws.stream().mapToDouble(Double::doubleValue).toArray()));
        }

        Options o = new Options();
        DefaultsFile.applyBundled(o);
        ConfigParser.parseFile(Path.of("..", "maps", "default.xp"), OptionOrigin.MAP, o);
        World w = new World(o, 1);
        Simulation s = new Simulation(w);
        Objects obj = w.objects;
        Deque<Double> rand = new ArrayDeque<>();
        s.wreckage.rfrac = () -> {
            assertTrue(!rand.isEmpty(), "Java draws no more than C did");
            return rand.poll();
        };

        Map<String, Integer> handleOf = new HashMap<>();
        Map<String, Piece> lastC = new HashMap<>();
        int[] cannonBack = new int[w.map.cannons().size()];
        List<String> problems = new ArrayList<>();
        int compared = 0;
        int created = 0;
        int hitInC = 0;
        int endedTogether = 0;
        int first = makes.get(0).frame();
        int last = frames.keySet().stream().mapToInt(Integer::intValue).max().orElseThrow();
        for (int fr = first; fr <= last; fr++) {
            for (int i = 0; i < cannonBack.length; i++) {
                if (cannonBack[i] == fr) {
                    Cannon c = w.map.cannons().get(i);
                    w.map.setBlock(c.bx(), c.by(), BlockType.CANNON);
                }
            }
            List<Piece> now = frames.getOrDefault(fr, List.of());
            List<Make> here = new ArrayList<>();
            for (Make m : makes) {
                if (m.frame() == fr) {
                    here.add(m);
                }
            }
            // pieces made inside C's object loop (a shot killed a cannon) are also moved in that frame
            List<int[]> made = new ArrayList<>();
            for (Make m : here) {
                if (movedInCreationFrame(m, now, lastC)) {
                    made.add(make(s, w, m, rand, cannonBack, fr));
                }
            }
            s.moveObjects();
            for (Make m : here) {
                if (!movedInCreationFrame(m, now, lastC)) {
                    made.add(make(s, w, m, rand, cannonBack, fr));
                }
            }
            s.expireObjects();
            // match new C pieces to new Java pieces by their (exact) mass
            for (Piece p : now) {
                if (lastC.containsKey(p.ptr())) {
                    continue;
                }
                Integer h = null;
                for (int[] hs : made) {
                    for (int x : hs) {
                        if (x >= 0 && obj.mass[x] == p.mass() && !handleOf.containsValue(x)) {
                            h = x;
                        }
                    }
                }
                if (h == null) {
                    problems.add("frame " + fr + ": no Java piece of mass " + p.mass());
                    continue;
                }
                created++;
                handleOf.put(p.ptr(), h);
                if (obj.size[h] != p.size() || obj.info[h] != p.info() || obj.turnSpeed[h] != p.turnSpeed()) {
                    problems.add("frame " + fr + " " + p.ptr() + ": size/info/turnspeed Java " + obj.size[h] + "/"
                        + obj.info[h] + "/" + obj.turnSpeed[h] + " C " + p.size() + "/" + p.info() + "/" + p.turnSpeed());
                }
            }
            // C pieces gone this frame
            for (String ptr : new ArrayList<>(handleOf.keySet())) {
                if (now.stream().anyMatch(p -> p.ptr().equals(ptr))) {
                    continue;
                }
                int h = handleOf.remove(ptr);
                Piece was = lastC.remove(ptr);
                if (live(obj, h)) {
                    if (was != null && was.life() > 1) {
                        hitInC++;
                        s.shots.delete(h);
                    } else {
                        problems.add("frame " + fr + " " + ptr + ": gone in C (life " + (was == null ? "?" : was.life())
                            + ") but alive in Java");
                    }
                } else {
                    endedTogether++;
                }
            }
            for (Piece p : now) {
                Integer h = handleOf.get(p.ptr());
                if (h == null) {
                    continue;
                }
                if (!live(obj, h)) {
                    problems.add("frame " + fr + " " + p.ptr() + ": alive in C, gone in Java");
                    handleOf.remove(p.ptr());
                    continue;
                }
                compared++;
                String j = obj.cx[h] + " " + obj.cy[h] + " " + obj.vx[h] + " " + obj.vy[h] + " " + (long) obj.life[h]
                    + " " + obj.rotation[h];
                String c = p.cx() + " " + p.cy() + " " + p.vx() + " " + p.vy() + " " + p.life() + " " + p.rotation();
                if (!j.equals(c)) {
                    problems.add("frame " + fr + " " + p.ptr() + ": Java " + j + " C " + c);
                }
                lastC.put(p.ptr(), p);
            }
            for (int[] hs : made) {
                for (int x : hs) {
                    if (x >= 0 && live(obj, x) && !handleOf.containsValue(x)) {
                        problems.add("frame " + fr + ": Java made a piece (mass " + obj.mass[x] + ") C did not show");
                        s.shots.delete(x);
                    }
                }
            }
        }
        int c1 = compared;
        int c2 = created;
        int hit = hitInC;
        int ended = endedTogether;
        System.out.printf("[WreckageGoldenTest] %d makes, %d pieces, %d piece-frames compared, %d ended together, "
            + "%d hit by ships in C, %d problems%n", makes.size(), c2, c1, ended, hit, problems.size());
        assertAll(
            () -> assertEquals(List.of(), problems.subList(0, Math.min(10, problems.size())), "no differences"),
            () -> assertEquals(11, makes.size(), "the trace has 7 cannon and 4 ship explosions"),
            () -> assertEquals(30, c2, "every C piece has its Java twin"),
            () -> assertEquals(659, c1, "every C piece-frame is compared"),
            () -> assertEquals(27, ended, "27 pieces end in the same frame (life or wall) in both"),
            () -> assertEquals(2, hit, "2 were hit by ships in the C game"),
            () -> assertTrue(rand.isEmpty(), "Java used every C draw"));
    }

    private static boolean live(Objects o, int h) {
        for (int k = 0; k < o.count(); k++) {
            if (o.active[k] == h) {
                return o.type[h] == Obj.WRECKAGE;
            }
        }
        return false;
    }

    /** True when the make's new pieces already moved in their creation frame (C made them in the object loop). */
    private static boolean movedInCreationFrame(Make m, List<Piece> now, Map<String, Piece> lastC) {
        int cx = (int) (f(m.args()[2]) * 64);
        int cy = (int) (f(m.args()[3]) * 64);
        for (Piece p : now) {
            if (!lastC.containsKey(p.ptr()) && (p.cx() != cx || p.cy() != cy)
                && Math.abs(p.cx() - cx) < 64 * 64 && Math.abs(p.cy() - cy) < 64 * 64) {
                return true;
            }
        }
        return false;
    }

    /** Replay one Make_wreckage with C's arguments and draws; a cannon's block becomes SPACE while it is dead. */
    private static int[] make(Simulation s, World w, Make m, Deque<Double> rand, int[] cannonBack, int frame) {
        String[] a = m.args();
        for (double d : m.draws()) {
            rand.add(d);
        }
        float x = f(a[2]);
        float y = f(a[3]);
        int owner = Integer.parseInt(a[6]);
        if (owner == Obj.NO_ID) {
            for (int i = 0; i < w.map.cannons().size(); i++) {
                Cannon c = w.map.cannons().get(i);
                if ((int) c.x() == (int) x && (int) c.y() == (int) y) {
                    w.map.setBlock(c.bx(), c.by(), BlockType.SPACE);
                    cannonBack[i] = frame + w.params.cannonDeadTime;
                }
            }
        }
        int before = w.objects.count();
        s.wreckage.make(x, y, f(a[4]), f(a[5]), owner, Integer.parseInt(a[7]), f(a[8]), f(a[9]), f(a[10]),
            Integer.parseInt(a[11]), Integer.parseInt(a[12]), Integer.parseInt(a[13]), Integer.parseInt(a[14]),
            Integer.parseInt(a[15]), f(a[16]), f(a[17]), Integer.parseInt(a[18]), Integer.parseInt(a[19]));
        assertEquals(0, rand.size(), "frame " + m.frame() + ": Java used exactly C's " + m.draws().length + " draws");
        int[] hs = new int[w.objects.count() - before];
        for (int i = 0; i < hs.length; i++) {
            hs[i] = w.objects.active[before + i];
        }
        return hs;
    }
}
