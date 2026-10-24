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

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.ModeState;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

import java.util.ArrayList;
import java.util.List;

/** Fixtures for the ball, treasure, target and race tests. */
final class ModeFx {
    private ModeFx() {}

    /** Team play at 14 fps without gravity. */
    static final String TEAM = SimFixture.FPS14 + "teamPlay: yes\n";

    /**
     * 20 x 8 blocks, team 1 bases at x 1, 3, 5 and team 2 bases at x 14, 16, 18 on the bottom row, a treasure above each base
     * (treasure 0 at (1, 1): team 1; treasure 2 at (18, 1): team 2) and an empty treasure '^' (treasure 1 at
     * (10, 4), closest to the team 2 base). Map objects are numbered column by column, as in C.
     */
    static String[] teamRows() {
        String[] rows = SimFixture.open(20, 8, "");
        rows[7] = " 1 1 1        2 2 2";
        rows[6] = " *                * ";
        rows[3] = "          ^         ";
        return rows;
    }

    /**
     * Like {@link #teamRows} without treasures, with targets at (2, 3) (target 0, team 1), (15, 3) and (17, 3)
     * (targets 1 and 2, team 2).
     */
    static String[] targetRows() {
        String[] rows = SimFixture.open(20, 8, "");
        rows[7] = " 1 1 1        2 2 2";
        rows[4] = "  !            ! ! ";
        return rows;
    }

    static Simulation targetSim(String extra) {
        return TestWorlds.sim(TEAM + extra, targetRows());
    }

    /** Race at 14 fps without gravity: 3 bases, checkpoint A at (10, 5), B at (10, 1). */
    static Simulation raceSim(String extra) {
        String[] rows = SimFixture.open(20, 10, "___");
        rows[4] = "          A         ";
        rows[8] = "          B         ";
        return TestWorlds.sim(SimFixture.FPS14 + "timing: yes\n" + extra, rows);
    }

    static Simulation teamSim(String extra) {
        return TestWorlds.sim(TEAM + extra, teamRows());
    }

    /** The live ball handles in pool order. */
    static List<Integer> balls(World w) {
        List<Integer> l = new ArrayList<>();
        Objects o = w.objects;
        for (int k = 0; k < o.count(); k++) {
            if (o.type[o.active[k]] == Obj.BALL) {
                l.add(o.active[k]);
            }
        }
        return l;
    }

    /** The ball of a treasure, or -1. */
    static int ballOf(World w, int treasure) {
        for (int h : balls(w)) {
            if (w.modes.ballTreasure[h] == treasure) {
                return h;
            }
        }
        return -1;
    }

    /** Connect ball h to pl as C's connector would (owner, id, team, gravity, HAS_BALL). */
    static void tow(World w, Player pl, int h) {
        ModeState m = w.modes;
        w.objects.owner[h] = pl.id;
        w.objects.team[h] = pl.team;
        m.ballOwner[h] = pl.id;
        w.objects.status[h] |= Obj.GRAVITY;
        m.treasureHave[m.ballTreasure[h]] = false;
        pl.have |= Has.BALL;
    }

    /** A player with the given team at (x, y), shields down, connector available. */
    static Player ship(Simulation s, String name, int team, double x, double y) {
        Player p = SimFixture.ready(s, name, team, x, y);
        p.have |= Has.CONNECTOR;
        return p;
    }

    /** Messages since {@code from} (game kind or any). */
    static List<String> msgs(World w, long from) {
        return SimFixture.messages(w, from);
    }

    /** Ball centre of treasure (bx, by): x centre, 10 px above the block bottom. */
    static double[] ballPos(int bx, int by) {
        return new double[] {(bx + 0.5) * Const.BLOCK_SZ, by * Const.BLOCK_SZ + 10};
    }

    static int kindGame() {
        return Msg.Text.KIND_GAME;
    }
}
