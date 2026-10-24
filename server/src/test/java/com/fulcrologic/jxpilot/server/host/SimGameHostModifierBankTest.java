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
package com.fulcrologic.jxpilot.server.host;

import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.ReliableType;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.ModifierBanks;
import com.fulcrologic.jxpilot.server.world.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static com.fulcrologic.jxpilot.server.host.HostFx.THREE;
import static com.fulcrologic.jxpilot.server.host.HostFx.empty;
import static com.fulcrologic.jxpilot.server.host.HostFx.sim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** SimGameHost and MODIFIER_BANK (C {@code Receive_modifier_bank}, login and playing states). */
@DisplayName("SimGameHost: MODIFIER_BANK")
class SimGameHostModifierBankTest {
    static final String RULES = "allowModifiers: true\nallowNukes: true\nallowClusters: true\nallowLaserModifiers: true\n";

    static ByteBuffer bank(int bank, String text) {
        Msg.ModifierBank m = new Msg.ModifierBank();
        m.bank = bank;
        m.text.append(text);
        ByteBuffer b = ByteBuffer.allocate(300);
        m.write(b);
        return b.flip();
    }

    static int parsed(String text) {
        return ModifierBanks.parse(text, true, true, true, true);
    }

    @Test
    @DisplayName("banks sent before READY are in place after the spawn; in play they replace the bank at once")
    void beforeAndAfterReady() {
        Simulation s = sim(RULES, THREE);
        SimGameHost h = new SimGameHost(s);
        int ann = h.join("Ann", 0, "");
        Player p = s.world.byId(ann);
        h.clientMessage(ann, ReliableType.MODIFIER_BANK, bank(0, "FN C"));
        h.clientMessage(ann, ReliableType.MODIFIER_BANK, bank(3, "X4 LS"));
        String reserved = Arrays.toString(p.modBank);
        h.clientMessage(ann, ReliableType.READY, empty());
        String spawned = Arrays.toString(p.modBank);
        h.clientMessage(ann, ReliableType.MODIFIER_BANK, bank(1, "V2"));
        h.clientMessage(ann, ReliableType.MODIFIER_BANK, bank(0, ""));
        String playing = Arrays.toString(p.modBank);
        int fn = parsed("FN C");
        int x4 = parsed("X4 LS");
        int v2 = parsed("V2");
        assertAll(
            () -> assertEquals("[0, 0, 0, 0]", reserved, "before READY nothing is set on the reserved player"),
            () -> assertEquals("[" + fn + ", 0, 0, " + x4 + "]", spawned,
                "after the spawn (whose Init_player clears the banks) the early banks are in place"),
            () -> assertEquals("[0, " + v2 + ", 0, " + x4 + "]", playing,
                "in play a bank is replaced at once; an empty text clears it"));
    }

    @Test
    @DisplayName("parsed with the rules: allowModifiers off gives empty banks")
    void rules() {
        Simulation off = sim("allowModifiers: false\nallowNukes: true\n", THREE);
        SimGameHost h = new SimGameHost(off);
        int ann = h.join("Ann", 0, "");
        h.clientMessage(ann, ReliableType.READY, empty());
        h.clientMessage(ann, ReliableType.MODIFIER_BANK, bank(2, "N I"));
        int offBank = off.world.byId(ann).modBank[2];
        Simulation on = sim("allowModifiers: true\nallowNukes: false\n", THREE);
        SimGameHost h2 = new SimGameHost(on);
        int bob = h2.join("Bob", 0, "");
        h2.clientMessage(bob, ReliableType.READY, empty());
        h2.clientMessage(bob, ReliableType.MODIFIER_BANK, bank(2, "N I"));
        int onBank = on.world.byId(bob).modBank[2];
        assertAll(
            () -> assertEquals(0, offBank, "without allowModifiers the bank stays empty"),
            () -> assertEquals(ModifierBanks.parse("I", true, false, false, false), onBank,
                "with allowModifiers but no nukes only I is kept"));
    }

    @Test
    @DisplayName("bank 4+ and 80+ char texts are ignored; a new login starts with empty banks")
    void ignoredAndReset() {
        Simulation s = sim(RULES, THREE);
        SimGameHost h = new SimGameHost(s);
        int ann = h.join("Ann", 0, "");
        h.clientMessage(ann, ReliableType.MODIFIER_BANK, bank(1, "N"));
        h.clientMessage(ann, ReliableType.MODIFIER_BANK, bank(4, "C"));
        h.clientMessage(ann, ReliableType.MODIFIER_BANK, bank(1, "C" + " ".repeat(79)));
        h.clientMessage(ann, ReliableType.READY, empty());
        String kept = Arrays.toString(s.world.byId(ann).modBank);
        h.clientMessage(ann, ReliableType.MODIFIER_BANK, bank(1, "C" + " ".repeat(79)));
        String keptInPlay = Arrays.toString(s.world.byId(ann).modBank);
        h.leave(ann);
        int bob = h.join("Bob", 0, "");
        h.clientMessage(bob, ReliableType.READY, empty());
        String fresh = Arrays.toString(s.world.byId(bob).modBank);
        int n = parsed("N");
        assertAll(
            () -> assertEquals("[0, " + n + ", 0, 0]", kept,
                "bank 4 and the over-long text change nothing; the earlier bank 1 is kept"),
            () -> assertEquals("[0, " + n + ", 0, 0]", keptInPlay, "an over-long text in play changes nothing"),
            () -> assertEquals(ann, bob, "Bob reuses Ann's id"),
            () -> assertEquals("[0, 0, 0, 0]", fresh, "Bob does not inherit Ann's early banks"));
    }
}
