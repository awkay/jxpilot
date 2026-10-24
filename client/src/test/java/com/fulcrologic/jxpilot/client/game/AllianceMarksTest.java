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
package com.fulcrologic.jxpilot.client.game;

import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.ReliableType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** server-commands: alliance marks on the client (C Handle_score's alliance character, paint.c's team column). */
@DisplayName("Alliance marks")
class AllianceMarksTest {
    static Msg.PlayerJoin join(int id, int team, String name) {
        Msg.PlayerJoin j = new Msg.PlayerJoin();
        j.id = id;
        j.team = team;
        j.name.append(name);
        return j;
    }

    static ByteBuffer alliance(int id, char mark) {
        Msg.PlayerAlliance m = new Msg.PlayerAlliance();
        m.id = id;
        m.mark = mark;
        ByteBuffer b = ByteBuffer.allocate(8);
        m.write(b);
        return b.flip();
    }

    @Test
    @DisplayName("PLAYER_ALLIANCE sets a known player's mark (version bump); unknown ids are ignored; a join is blank")
    void players() {
        ClientGame g = new ClientGame();
        Players p = g.players();
        p.join(join(3, 255, "Ann"));
        int v0 = p.version();
        g.onReliable(ReliableType.PLAYER_ALLIANCE, alliance(3, '+'));
        char set = p.alliance(p.slotOf(3));
        int v1 = p.version();
        g.onReliable(ReliableType.PLAYER_ALLIANCE, alliance(9, '1'));
        p.join(join(3, 255, "Ann"));
        assertAll(
            () -> assertEquals('+', set, "the mark"),
            () -> assertEquals(v0 + 1, v1, "the score list is rebuilt"),
            () -> assertEquals(' ', p.alliance(p.slotOf(3)), "a (re)join starts blank"),
            () -> assertEquals(-1, p.slotOf(9), "unknown id ignored"));
    }

    @Test
    @DisplayName("the score list's team column shows the mark outside team play, the team in team play")
    void scoreboard() {
        Players p = new Players();
        p.join(join(1, 255, "Ann"));
        p.join(join(2, 255, "Bob"));
        p.setAlliance(1, '+');
        p.setAlliance(2, '4');
        Scoreboard free = new Scoreboard();
        free.configure(false, false, false, 0);
        free.update(p);
        Players q = new Players();
        q.join(join(1, 3, "Ann"));
        q.setAlliance(1, '+');
        Scoreboard team = new Scoreboard();
        team.configure(true, false, false, 0);
        team.update(q);
        assertAll(
            () -> assertEquals("  +    0  Ann", free.label(0).toString(), "'+' in the column"),
            () -> assertEquals("  4    0  Bob", free.label(1).toString(), "an alliance number"),
            () -> assertEquals("  3     0  Ann", team.label(0).toString(), "team play: the team digit"));
    }
}
