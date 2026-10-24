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

import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.math.Rng;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.server.net.DefaultsFile;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Parity gate for refactors of the simulation (docs/latency-fixes.md §7.5): every shipped map, run with the
 * bundled defaults, four robots and four humans pressing random flight, weapon and item keys, must reach the
 * same per-tick digest chain as before the refactor. The chain folds {@link Digest#of} after every tick, so a
 * divergence that later heals still fails. A legitimate behaviour change regenerates the golden values: run
 * with {@code -Djxpilot.printDigests=true} and paste the printed rows.
 */
@DisplayName("Map digest parity (all shipped maps)")
class MapDigestParityTest {
    static final int TICKS = 1500;

    /** Keys the humans press: flight, shots, items and devices (so mass, fuel, armor and phasing vary). */
    static final ControlKey[] KEYS = {
        ControlKey.THRUST, ControlKey.THRUST, ControlKey.TURN_LEFT, ControlKey.TURN_RIGHT, ControlKey.FIRE_SHOT,
        ControlKey.SHIELD, ControlKey.REFUEL, ControlKey.FIRE_MISSILE, ControlKey.DROP_MINE, ControlKey.ECM,
        ControlKey.CLOAK, ControlKey.TANK_DETACH, ControlKey.TANK_NEXT, ControlKey.LOSE_ITEM,
        ControlKey.EMERGENCY_SHIELD, ControlKey.EMERGENCY_THRUST, ControlKey.PHASING, ControlKey.HYPERJUMP,
        ControlKey.DEFLECTOR, ControlKey.TRACTOR_BEAM, ControlKey.TOGGLE_AUTOPILOT, ControlKey.CONNECTOR,
        ControlKey.FIRE_LASER
    };

    static final String EXTRA = "maxRobots: 4\nminRobots: 4\nrobotLeaveLife: 0\nlimitedLives: no\n"
        + "itemProbMult: 4\ninitialArmor: 2\ninitialAfterburners: 2\ninitialTanks: 2\ninitialPhasings: 1\n"
        + "initialEmergencyShields: 1\ninitialEmergencyThrusts: 1\ninitialAutopilots: 1\ninitialMissiles: 2\n"
        + "initialMines: 2\ninitialECMs: 1\ninitialCloaks: 1\ninitialDeflectors: 1\ninitialHyperJumps: 1\n"
        + "initialTractorBeams: 1\ninitialLasers: 1\n";

    static long chain(String map, int seed) throws IOException {
        Options o = new Options();
        DefaultsFile.applyBundled(o);
        Path p = Path.of("..", "maps", map);
        if (!p.toFile().exists()) {
            p = Path.of("maps", map);
        }
        ConfigParser.parseFile(p, OptionOrigin.MAP, o);
        ConfigParser.parse(EXTRA, "parity", OptionOrigin.COMMAND, o);
        World w = new World(o, seed);
        w.wallSeconds = () -> 1_000_000L;
        Simulation s = new Simulation(w);
        s.robots.setEnabled(true);
        for (int h = 0; h < 4; h++) {
            if (s.ships.join("human" + h, h % 2) == null) {
                break;
            }
        }
        Rng keys = new Rng(seed * 31 + 7);
        long[] in = new long[w.players.length];
        long h = 0xcbf29ce484222325L;
        for (int t = 0; t < TICKS; t++) {
            for (int k = 0; k < w.numPlayers; k++) {
                Player pl = w.playerAt(k);
                if (!pl.robot && !pl.tank) {
                    for (int i = 0; i < KEYS.length; i++) {
                        if (keys.nextFloat() < (i < 4 ? 0.05f : 0.01f)) {
                            in[pl.slot] ^= KEYS[i].mask();
                        }
                    }
                }
            }
            s.tick(in);
            h = (h ^ Digest.of(w)) * 0x100000001b3L;
        }
        return h;
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "blood-music2.xp, 7b9ffcd7dcf8752e",
        "CAMD.xp, 0574a26f23384298",
        "cloudscape.xp, 56f65a577e39d662",
        "default.xp, a9c4f1cc33517f17",
        "doggy.xp, ede43d03b1eafb06",
        "fireball.xp, cf6eca9e75fde581",
        "fuzz.xp, df84c49670b5de1e",
        "fuzz2.xp, e581dc7ed0f94d5e",
        "globe.xp, 9eb6dc8b2cd2aa44",
        "grandprix.xp, 46f9fa9d0f39ea90",
        "newdarkhell.xp, e0613e4eef6d1319",
        "newdarkhell2.xp, 3a5ec1c4cdee0fb7",
        "pad.xp, d2525fc07603d5a8",
        "pit.xp, 83b18ec264916617",
        "planetx.xp, 6f8e7abac6c7f678",
        "teamball.xp, 54afe972613d28b6",
        "tourmination.xp, 4bacd1360d8173a1",
        "tournament.xp, faaf0f31c7b90f41",
        "war.xp, 5730579508f3d8d4"
    })
    @DisplayName("the per-tick digest chain matches the golden value")
    void digestChainIsUnchanged(String map, String golden) throws IOException {
        long got = chain(map, 3);
        if (Boolean.getBoolean("jxpilot.printDigests")) {
            System.out.printf("DIGEST \"%s, %016x\",%n", map, got);
            return;
        }
        assertEquals(Long.parseUnsignedLong(golden, 16), got, map + ": the per-tick digest chain is unchanged");
    }
}
