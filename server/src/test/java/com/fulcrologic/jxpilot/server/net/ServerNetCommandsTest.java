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
package com.fulcrologic.jxpilot.server.net;

import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.net.Msg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The network layer's part of operator commands: options changed at run time, the locked game's REJECT text. */
@DisplayName("ServerNet: run-time options and the game lock")
class ServerNetCommandsTest {
    @Test
    @DisplayName("denyHosts (C Set_deny_hosts) and maxClientsPerIP change for the next CONNECTs; other options are ignored")
    void optionChanged() {
        Harness h = new Harness();
        Options o = new Options();
        o.force(Opt.DENY_HOSTS, "10.0.0.0/8, 192.168.1.1");
        o.force(Opt.MAX_CLIENTS_PER_IP, "7");
        h.host.sessions.optionChanged(Opt.SHOT_SPEED, o);
        int before = h.cfg.denyHosts.size();
        int perIpBefore = h.cfg.maxPerIp;
        h.host.sessions.optionChanged(Opt.DENY_HOSTS, o);
        int perIpAfterDeny = h.cfg.maxPerIp;
        h.host.sessions.optionChanged(Opt.MAX_CLIENTS_PER_IP, o);
        assertAll(
            () -> assertEquals(0, before, "another option changes nothing here"),
            () -> assertEquals(2, perIpBefore, "the default limit (C's 2)"),
            () -> assertEquals(2, h.cfg.denyHosts.size(), "two networks denied"),
            () -> assertTrue(h.cfg.denyHosts.denied(Harness.addr("10.1.2.3", 9)), "10/8 is denied"),
            () -> assertEquals(2, perIpAfterDeny, "denyHosts does not touch the limit"),
            () -> assertEquals(7, h.cfg.maxPerIp, "the new limit"));
    }

    @Test
    @DisplayName("a REJECT for a locked game says \"game locked\" (C E_GAME_LOCKED)")
    void lockedText() {
        assertEquals("game locked", ServerNet.rejectText(Msg.Reject.REASON_LOCKED), "the text");
    }
}
