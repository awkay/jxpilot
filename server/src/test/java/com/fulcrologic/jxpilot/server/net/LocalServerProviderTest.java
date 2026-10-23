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

import com.fulcrologic.jxpilot.common.net.LocalServer;
import com.fulcrologic.jxpilot.server.host.LocalServerProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link LocalServerProvider}: the embedded server behind the client's {@code --local}, over real loopback UDP. */
@DisplayName("LocalServerProvider")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class LocalServerProviderTest {
    @Test
    @DisplayName("is registered for ServiceLoader; serves a map on IPv4 loopback on a free port; a client is accepted")
    void serves() throws Exception {
        List<String> found = new ArrayList<>();
        for (LocalServer.Provider p : ServiceLoader.load(LocalServer.Provider.class)) {
            found.add(p.getClass().getName());
        }
        boolean accepted;
        InetSocketAddress address;
        try (LocalServer s = new LocalServerProvider().start(Path.of("..", "maps", "doggy.xp"), "-maxRobots", "0")) {
            address = s.address();
            try (LoopbackIntegrationTest.UdpClient u = new LoopbackIntegrationTest.UdpClient(41, address)) {
                u.connect("local");
                accepted = u.c.accepted;
            }
        }
        InetSocketAddress a = address;
        boolean ok = accepted;
        assertAll(
            () -> assertEquals(List.of(LocalServerProvider.class.getName()), found, "the one registered provider"),
            () -> assertTrue(a.getAddress().isLoopbackAddress(), "bound to loopback: " + a),
            () -> assertTrue(a.getAddress() instanceof Inet4Address, "the IPv4 loopback is preferred: " + a),
            () -> assertTrue(a.getPort() > 0, "on the port the system picked"),
            () -> assertTrue(ok, "a client connecting there is accepted"));
    }

    @Test
    @DisplayName("a map that cannot be read fails the start")
    void missingMap() {
        assertThrows(Exception.class, () -> new LocalServerProvider().start(Path.of("no-such-map.xp")),
            "a missing map is an error, not a running server");
    }
}
