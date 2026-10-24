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

import com.fulcrologic.jxpilot.server.world.Mods;
import com.fulcrologic.jxpilot.common.sim.Obj;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** C {@code Describe_shot} (shot.c:376-436). */
@DisplayName("ShotNames (Describe_shot)")
class ShotNamesTest {
    static String d(int type, int status, int mods, boolean hit) {
        return ShotNames.append(new StringBuilder(), type, status, mods, hit).toString();
    }

    static final int MINI = Mods.withMini(0, 1);
    static final int NUKE = Mods.withNuclear(0, Mods.NUCLEAR);
    static final int FULL = Mods.withNuclear(0, Mods.NUCLEAR | Mods.FULLNUCLEAR);

    @Nested
    @DisplayName("names")
    class Names {
        @Test
        @DisplayName("each type has its name with the article \"a \"")
        void plain() {
            assertAll(
                () -> assertEquals("a mine", d(Obj.MINE, 0, 0, true), "a mine without GRAVITY"),
                () -> assertEquals("a bomb", d(Obj.MINE, Obj.GRAVITY, 0, true), "a thrown (GRAVITY) mine is a bomb"),
                () -> assertEquals("a smart missile", d(Obj.SMART_SHOT, 0, 0, true), "smart missile"),
                () -> assertEquals("a torpedo", d(Obj.TORPEDO, 0, 0, true), "torpedo"),
                () -> assertEquals("a heatseeker", d(Obj.HEAT_SHOT, 0, 0, true), "heatseeker"),
                () -> assertEquals("a shot", d(Obj.CANNON_SHOT, 0, 0, true), "a plain cannon shot is a shot"),
                () -> assertEquals("a shot", d(Obj.SHOT, 0, 0, true), "a plain shot"),
                () -> assertEquals("a shot", d(Obj.DEBRIS, 0, 0, true), "any other type is a shot"));
        }

        @Test
        @DisplayName("cluster cannon shots are flak and other cluster shots debris, both without an article")
        void cluster() {
            assertAll(
                () -> assertEquals("cluster flak", d(Obj.CANNON_SHOT, 0, Obj.MOD_CLUSTER, true), "cannon cluster: flak"),
                () -> assertEquals("cluster debris", d(Obj.SHOT, 0, Obj.MOD_CLUSTER, true), "shot cluster: debris"),
                () -> assertEquals("a cluster mine", d(Obj.MINE, 0, Obj.MOD_CLUSTER, true), "a cluster mine keeps its article"),
                () -> assertEquals("some mini cluster debriss", d(Obj.SHOT, 0, Obj.MOD_CLUSTER | MINI, false),
                    "a mini launch overrides the empty article (C's plural even for debris)"));
        }
    }

    @Nested
    @DisplayName("mini launches")
    class Minis {
        @Test
        @DisplayName("launch messages of mini weapons say \"some\" and the plural; hits do not")
        void plural() {
            assertAll(
                () -> assertEquals("some mini torpedoes", d(Obj.TORPEDO, 0, MINI, false), "torpedoes take \"es\""),
                () -> assertEquals("some mini smart missiles", d(Obj.SMART_SHOT, 0, MINI, false), "others take \"s\""),
                () -> assertEquals("some mini bombs", d(Obj.MINE, Obj.GRAVITY, MINI, false), "mini bombs"),
                () -> assertEquals("a mini torpedo", d(Obj.TORPEDO, 0, MINI, true), "a hit names one"),
                () -> assertEquals("a torpedo", d(Obj.TORPEDO, 0, 0, false), "a non-mini launch names one"));
        }
    }

    @Nested
    @DisplayName("adjectives")
    class Adjectives {
        @Test
        @DisplayName("modified, mini, full, nuclear, imploding, cluster come in that order")
        void order() {
            int all = Mods.withVelocity(0, 1) | MINI | FULL | Obj.MOD_IMPLOSION | Obj.MOD_CLUSTER;
            assertAll(
                () -> assertEquals("a modified mini full nuclear imploding cluster mine", d(Obj.MINE, 0, all, true),
                    "all adjectives in C's order"),
                () -> assertEquals("a nuclear torpedo", d(Obj.TORPEDO, 0, NUKE, true), "NUCLEAR alone: nuclear"),
                () -> assertEquals("a full torpedo", d(Obj.TORPEDO, 0, Mods.withNuclear(0, Mods.FULLNUCLEAR), true),
                    "FULLNUCLEAR alone: full"),
                () -> assertEquals("a imploding heatseeker", d(Obj.HEAT_SHOT, 0, Obj.MOD_IMPLOSION, true),
                    "imploding (C keeps \"a\")"),
                () -> assertEquals("a modified torpedo", d(Obj.TORPEDO, 0, Mods.withVelocity(0, 2), true), "velocity: modified"),
                () -> assertEquals("a modified torpedo", d(Obj.TORPEDO, 0, Mods.withSpread(0, 1), true), "spread: modified"),
                () -> assertEquals("a modified torpedo", d(Obj.TORPEDO, 0, Mods.withPower(0, 3), true), "power: modified"),
                () -> assertEquals("a torpedo", d(Obj.TORPEDO, 0, Mods.withLaser(0, 3), true), "laser modifiers add nothing"));
        }

        @Test
        @DisplayName("appends to the given buffer and returns it")
        void appends() {
            StringBuilder sb = new StringBuilder("X hit ");
            StringBuilder r = ShotNames.append(sb, Obj.MINE, 0, 0, true);
            assertAll(
                () -> assertSame(sb, r, "returns the same buffer"),
                () -> assertEquals("X hit a mine", sb.toString(), "after the existing text"));
        }

        @Test
        @DisplayName("warm runs without touching any shared state")
        void warm() {
            ShotNames.warm();
            assertEquals("a mine", d(Obj.MINE, 0, 0, true), "names are unchanged after warming");
        }
    }
}
