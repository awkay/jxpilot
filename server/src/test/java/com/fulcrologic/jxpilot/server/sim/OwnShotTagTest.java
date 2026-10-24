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
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.TestWorlds;
import com.fulcrologic.jxpilot.server.world.Objects;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BEHAVIOR (OWN_SHOTS tags, Shots.playerShot): each bullet a ship fires is tagged with the net tick and its
 * ordinal in that tick's fire (0, 1, 2 ... in ShipShooter order), restarting at 0 each tick; a cannon's shot is
 * untagged (-1).
 */
@DisplayName("own-shot tags")
class OwnShotTagTest {
    static final String[] MAP = {"xxxxxxxxxxxxx", "x           x", "x _       _ x", "x           x", "xxxxxxxxxxxxx"};

    static List<String> tags(World w, int owner) {
        Objects o = w.objects;
        List<String> t = new ArrayList<>();
        for (int k = 0; k < o.count(); k++) {
            int h = o.active[k];
            if (o.type[h] == Obj.SHOT && o.owner[h] == owner) {
                t.add(o.spawnTick[h] + ":" + o.shotOrdinal[h]);
            }
        }
        return t;
    }

    @Test
    @DisplayName("a wide-angle ship's three bullets of a tick get ordinals 0, 1, 2 and that tick; the next fire restarts")
    void ordinals() {
        Simulation s = TestWorlds.sim("framesPerSecond: 14\ngravity: 0\nmaxRobots: 0\nfireRepeatRate: 2\nshotLife: 50\n", MAP);
        World w = s.world;
        Player p = TestWorlds.join(s, "Ann", 0);
        p.used &= ~Has.SHIELD;
        p.items[Item.WIDEANGLE.ordinal()] = 1;
        TestWorlds.run(s, 2, 0L);
        long fire = ControlKey.FIRE_SHOT.mask();
        TestWorlds.run(s, 1, fire);
        int first = w.tick;
        List<String> once = tags(w, p.id);
        TestWorlds.run(s, 2, fire);
        int second = w.tick;
        List<String> twice = tags(w, p.id);
        int cannon = s.shots.fire(null, 0, true, 100, 100, Obj.SHOT, 0, 0);
        assertAll(
            () -> assertEquals(List.of(first + ":0", first + ":1", first + ":2"), once,
                "the press fires three bullets, tagged with the tick and 0, 1, 2"),
            () -> assertEquals(first + 2, second, "two ticks later (fireRepeatRate 2)"),
            () -> assertEquals(List.of(second + ":0", second + ":1", second + ":2"), twice,
                "autofire fires three more, ordinals again from 0 (the first ones hit the wall)"),
            () -> assertTrue(cannon >= 0 && w.objects.shotOrdinal[cannon] == -1, "a cannon's shot is untagged"));
    }
}
