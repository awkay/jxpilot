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

import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.KernelConfig;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.net.ConfigTransfer;
import com.fulcrologic.jxpilot.common.sim.KernelFingerprint;
import com.fulcrologic.jxpilot.common.sim.KernelWorld;
import com.fulcrologic.jxpilot.server.net.ClientMap;
import com.fulcrologic.jxpilot.server.net.DefaultsFile;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client's kernel world is the server's: on every shipped map, with the bundled defaults, a client that has the
 * config blob (the map as clients may see it) and the kernel config blob builds a {@link KernelWorld} and
 * ShipParams with the same {@link KernelFingerprint} as the server's {@link World}; and again after an operator
 * /sets kernel options (gravity, shipMass, friction, wormTime) through the tuner.
 */
@DisplayName("Kernel config parity (all shipped maps)")
class KernelConfigParityTest {
    static Options serverOptions(String map) throws IOException {
        Options o = new Options();
        DefaultsFile.applyBundled(o);
        ConfigParser.parseFile(Path.of("..", "maps", map), OptionOrigin.MAP, o);
        return o;
    }

    /** What a client builds: its options from the config blob, its kernel world from the kernel blob over them. */
    static KernelWorld client(Options server, String clientMap) {
        ConfigTransfer.Blob config = ConfigTransfer.encode(server.toConfigText(clientMap));
        com.fulcrologic.jxpilot.common.net.Msg.Welcome w = new com.fulcrologic.jxpilot.common.net.Msg.Welcome();
        config.fill(w);
        ConfigTransfer.Receiver rx = new ConfigTransfer.Receiver(w);
        java.nio.ByteBuffer chunk = java.nio.ByteBuffer.allocate(config.compressed.length + 4);
        chunk.putInt(0).put(config.compressed).flip();
        rx.onChunk(chunk);
        Options clientOptions = rx.options();
        ConfigTransfer.Blob kernel = ConfigTransfer.encode(KernelConfig.encode(server, clientMap));
        ConfigTransfer.Receiver krx = new ConfigTransfer.Receiver(kernel.length, kernel.compressed.length, kernel.crc32);
        java.nio.ByteBuffer kc = java.nio.ByteBuffer.allocate(kernel.compressed.length + 4);
        kc.putInt(0).put(kernel.compressed).flip();
        krx.onChunk(kc);
        return KernelWorld.of(KernelConfig.decode(krx.bytes(), clientOptions.mapData()));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"blood-music2.xp", "CAMD.xp", "cloudscape.xp", "default.xp", "doggy.xp", "fireball.xp",
        "fuzz.xp", "fuzz2.xp", "globe.xp", "grandprix.xp", "newdarkhell.xp", "newdarkhell2.xp", "pad.xp", "pit.xp",
        "planetx.xp", "teamball.xp", "tourmination.xp", "tournament.xp", "war.xp"})
    @DisplayName("the client-built kernel world and parameters equal the server's, before and after a /set")
    void parity(String map) throws IOException {
        Options o = serverOptions(map);
        World w = new World(o, 5);
        Simulation s = new Simulation(w);
        String clientMap = ClientMap.of(o.mapData(), o);
        String before = KernelFingerprint.of(client(o, clientMap));
        String serverBefore = KernelFingerprint.of(w);
        Tuning t = new Tuning(s, opt -> { });
        int g = t.tune("gravity", "-0.47");
        int m = t.tune("shipMass", "13.5");
        int f = t.tune("friction", "0.015");
        int wt = t.tune("wormTime", "2.5");
        String after = KernelFingerprint.of(client(w.options, clientMap));
        String serverAfter = KernelFingerprint.of(w);
        assertAll(
            () -> assertEquals(serverBefore, before, map + ": the client's kernel world is the server's"),
            () -> assertEquals(Tuning.OK, g + m + f + wt - 3 * Tuning.OK, "the four /sets were accepted"),
            () -> assertTrue(!serverAfter.equals(serverBefore), "the /sets changed the server's kernel world"),
            () -> assertEquals(serverAfter, after, map + ": and still after the /sets"));
    }
}
