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

import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.server.net.GameHost;
import com.fulcrologic.jxpilot.server.net.GameHostFactory;
import com.fulcrologic.jxpilot.server.robot.RobotRoster;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.World;

import java.nio.file.Path;
import java.util.function.IntSupplier;

/**
 * Builds the real game for {@code ServerMain}: a {@link World} from the server's options (map included), its
 * {@link Simulation}, the robots of {@code robotFile} if one is given, and a {@link SimGameHost} over them.
 */
public final class SimGameHostFactory implements GameHostFactory {
    private final IntSupplier seeds;

    /** World RNG seeds from the clock (every game differs). */
    public SimGameHostFactory() {
        this(() -> (int) System.nanoTime());
    }

    /** @param seeds world RNG seed per created game (tests fix it) */
    public SimGameHostFactory(IntSupplier seeds) {
        this.seeds = seeds;
    }

    /**
     * BEHAVIOR: the world uses {@code options} (the map was parsed into them); {@code robotFile}, when set,
     * replaces the default robot roster (an unreadable file keeps the defaults, as C).
     */
    @Override
    public GameHost create(Options options, WorldMap map) {
        Simulation sim = new Simulation(new World(options, seeds.getAsInt()));
        String robotFile = options.getString(Opt.ROBOT_FILE);
        if (robotFile != null && !robotFile.isEmpty()) {
            sim.robots.setRoster(RobotRoster.load(Path.of(robotFile)));
        }
        return new SimGameHost(sim);
    }
}
