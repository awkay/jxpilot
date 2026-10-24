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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed C golden trace with the game-mode lines of xpilot-ref patch 0005 (XPMOBJ, XPPL2, XPTRS, XPTGT,
 * XPTEAM, and the random generator's XPMTP/XPMTN draw counts and XPMT state) next to the XPTRACE player lines of patch 0002, grouped by C frame ({@code frame_loops}).
 */
final class ModeTrace {
    /** One XPMOBJ line: a ball, asteroid or wreckage after the frame. */
    record Obj(String ptr, int type, int cx, int cy, int prevX, int prevY, float vx, float vy, long life, int id,
               int team, int status, float mass, int a, int b, float c, long d) {}

    /** One XPPL2 line. */
    record Pl2(int id, int used, int have, boolean reaching, int check, int round, int time, int bestLap,
               int lastLap, int lastLapTime, char mychar) {}

    /** Everything C printed after one frame. */
    static final class Frame {
        final long frame;
        CTraceTest.Frame ship;
        final List<Obj> objs = new ArrayList<>();
        final List<Pl2> players = new ArrayList<>();
        final List<int[]> treasures = new ArrayList<>();
        final List<int[]> targets = new ArrayList<>();
        final List<String> teams = new ArrayList<>();
        final List<Double> teamScores = new ArrayList<>();
        /** C's random draw count before (XPMTP) and after (XPMTN) the frame's update, -1 when not traced. */
        long drawsBefore = -1;
        /** The XPTRACE ship's status bits. */
        int shipStatus;
        long drawsAfter = -1;

        Frame(long frame) {
            this.frame = frame;
        }
    }

    final List<Frame> frames = new ArrayList<>();
    /** XPMT: C's random generator after frame {@link #mtFrame}: draws, left, next index, 625 state words. */
    long mtFrame = -1;
    long mtDraws;
    int mtLeft;
    int mtNext;
    final int[] mtState = new int[625];

    static ModeTrace load(String name) throws IOException {
        ModeTrace t = new ModeTrace();
        Map<Long, Frame> by = new LinkedHashMap<>();
        try (InputStream in = ModeTrace.class.getResourceAsStream("/ctrace/" + name)) {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1));
            for (String l = r.readLine(); l != null; l = r.readLine()) {
                String[] c = l.trim().split("\\s+");
                if (c.length < 3 || c[0].endsWith("_HDR")) {
                    continue;
                }
                long frame = Long.parseLong(c[2]);
                Frame f = by.computeIfAbsent(frame, Frame::new);
                switch (c[0]) {
                    case "XPTRACE" -> {
                        f.ship = CTraceTest.Frame.parse(l);
                        f.shipStatus = Integer.decode(c[21]);
                    }
                    case "XPMOBJ" -> f.objs.add(new Obj(c[3], Integer.parseInt(c[4]), Integer.parseInt(c[5]),
                        Integer.parseInt(c[6]), Integer.parseInt(c[7]), Integer.parseInt(c[8]),
                        Float.parseFloat(c[9]), Float.parseFloat(c[10]), Long.parseLong(c[11]),
                        Integer.parseInt(c[12]), Integer.parseInt(c[13]), Integer.decode(c[14]),
                        Float.parseFloat(c[15]), Integer.parseInt(c[16]), Integer.parseInt(c[17]),
                        Float.parseFloat(c[18]), c.length > 19 ? Long.parseLong(c[19]) : 0));
                    case "XPPL2" -> f.players.add(new Pl2(Integer.parseInt(c[3]), Integer.decode(c[4]),
                        Integer.decode(c[5]), !"(nil)".equals(c[6]), Integer.parseInt(c[7]),
                        Integer.parseInt(c[8]), Integer.parseInt(c[9]), Integer.parseInt(c[10]),
                        Integer.parseInt(c[11]), Integer.parseInt(c[12]), c[13].charAt(0)));
                    case "XPTRS" -> f.treasures.add(new int[] {Integer.parseInt(c[3]), Integer.parseInt(c[4]),
                        Integer.parseInt(c[5])});
                    case "XPTGT" -> f.targets.add(new int[] {Integer.parseInt(c[3]), Integer.parseInt(c[4]),
                        Integer.parseInt(c[5])});
                    case "XPTEAM" -> {
                        f.teams.add(c[3] + " " + c[4] + " " + c[5]);
                        f.teamScores.add(Double.parseDouble(c[6]));
                    }
                    case "XPMTP" -> f.drawsBefore = Long.parseLong(c[3]);
                    case "XPMTN" -> f.drawsAfter = Long.parseLong(c[3]);
                    case "XPMT" -> {
                        t.mtFrame = frame;
                        t.mtDraws = Long.parseLong(c[3]);
                        t.mtLeft = Integer.parseInt(c[4]);
                        t.mtNext = Integer.parseInt(c[5]);
                        for (int i = 0; i < 625; i++) {
                            t.mtState[i] = (int) Long.parseLong(c[6 + i]);
                        }
                    }
                    default -> { }
                }
            }
        }
        t.frames.addAll(by.values());
        return t;
    }
}
