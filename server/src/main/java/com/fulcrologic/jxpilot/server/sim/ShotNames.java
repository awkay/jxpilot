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
import com.fulcrologic.jxpilot.server.world.Mods;
import com.fulcrologic.jxpilot.server.world.Obj;

/** C {@code Describe_shot} ({@code src/server/shot.c}), appending to a message buffer without allocating. */
public final class ShotNames {
    private ShotNames() {}

    /**
     * Append the description of a shot of {@code type} with {@code status} and {@code mods}.
     * <p>
     * BEHAVIOR: names: mine ("bomb" with GRAVITY), smart missile, torpedo, heatseeker; cannon shots "shot" or
     * "flak" (cluster); other shots "shot" or "debris" (cluster). Cluster flak/debris has no article, others "a ".
     * BEHAVIOR: a launch message ({@code hit} false) of mini weapons says "some " and the plural ("es" for
     * torpedoes, else "s").
     * BEHAVIOR: then "modified " (velocity, spread or power), "mini ", "full ", "nuclear ", "imploding ",
     * "cluster " in that order before the name.
     */
    public static StringBuilder append(StringBuilder sb, int type, int status, int mods, boolean hit) {
        String name;
        String howmany = "a ";
        String plural = "";
        switch (type) {
            case Obj.MINE -> name = (status & Obj.GRAVITY) != 0 ? "bomb" : "mine";
            case Obj.SMART_SHOT -> name = "smart missile";
            case Obj.TORPEDO -> name = "torpedo";
            case Obj.HEAT_SHOT -> name = "heatseeker";
            case Obj.CANNON_SHOT -> {
                if (Mods.isCluster(mods)) {
                    howmany = "";
                    name = "flak";
                } else {
                    name = "shot";
                }
            }
            default -> {
                if (Mods.isCluster(mods)) {
                    howmany = "";
                    name = "debris";
                } else {
                    name = "shot";
                }
            }
        }
        if (Mods.mini(mods) != 0 && !hit) {
            howmany = "some ";
            plural = type == Obj.TORPEDO ? "es" : "s";
        }
        sb.append(howmany);
        if (Mods.velocity(mods) != 0 || Mods.spread(mods) != 0 || Mods.power(mods) != 0) {
            sb.append("modified ");
        }
        if (Mods.mini(mods) != 0) {
            sb.append("mini ");
        }
        if (Mods.isFullNuclear(mods)) {
            sb.append("full ");
        }
        if (Mods.isNuclear(mods)) {
            sb.append("nuclear ");
        }
        if (Mods.isImplosion(mods)) {
            sb.append("imploding ");
        }
        if (Mods.isCluster(mods)) {
            sb.append("cluster ");
        }
        return sb.append(name).append(plural);
    }

    /**
     * Run every branch once (string literals are created the first time each one runs), so a first kill message
     * in a tick does not allocate.
     */
    static void warm() {
        StringBuilder sb = new StringBuilder(Const.MSG_LEN);
        int[] types = {Obj.MINE, Obj.SMART_SHOT, Obj.TORPEDO, Obj.HEAT_SHOT, Obj.CANNON_SHOT, Obj.SHOT};
        int all = Obj.MOD_CLUSTER | Obj.MOD_IMPLOSION | Mods.withNuclear(0, Mods.NUCLEAR | Mods.FULLNUCLEAR)
            | Mods.withVelocity(0, 1) | Mods.withMini(0, 1);
        for (int t : types) {
            for (int m = 0; m < 2; m++) {
                for (int s = 0; s < 2; s++) {
                    sb.setLength(0);
                    append(sb, t, s == 0 ? 0 : Obj.GRAVITY, m == 0 ? 0 : all, s == 0);
                }
            }
        }
    }
}
