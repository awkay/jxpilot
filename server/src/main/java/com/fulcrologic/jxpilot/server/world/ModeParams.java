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
package com.fulcrologic.jxpilot.server.world;

import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;

/**
 * Game-mode parameters (balls and treasures, targets, race, asteroids, wreckage) resolved once from
 * {@link Options} at map load: the C globals of {@code cmdline.c} with the limits of {@code Tune_asteroid_prob}
 * (rules.c). Kept apart from {@link Params} so the mode code can grow without touching the core parameters.
 * Plain public fields so tests can tweak them. C {@code DFLOAT} options are {@code float}.
 */
public final class ModeParams {
    // ---- balls and treasures
    public float ballMass;
    public float ballConnectorLength;
    public float ballConnectorSpringConstant;
    public float ballConnectorDamping;
    public float maxBallConnectorRatio;
    public boolean connectorIsString;
    public boolean ballCollisions;
    public boolean ballSparkCollisions;
    public boolean treasureKillTeam;
    public boolean captureTheFlag;
    public boolean treasureCollisionDestroys;
    public boolean treasureCollisionMayKill;
    public boolean treasuresOnRadar;
    public float ballKillScoreMult;

    // ---- race
    /** C {@code timing}: the map is a race. */
    public boolean timing;
    public boolean ballrace;
    /** C {@code ballrace_connect}. */
    public boolean ballraceConnect;
    /** In blocks. */
    public float checkpointRadius;
    public int raceLaps;

    // ---- asteroids and wreckage
    public boolean asteroidCollisionMayKill;
    public boolean asteroidsOnRadar;
    public float asteroidPoints;
    public float asteroidMaxScore;
    public float asteroidItemProb;
    public int asteroidMaxItems;
    public float asteroidProb;
    public float maxAsteroidDensity;
    public int asteroidConcentratorRadius;
    public float asteroidConcentratorProb;
    /** C {@code World.asteroids.chance}: 1/chance per frame to place one (0 = never). */
    public int asteroidChance;
    /** C {@code World.asteroids.max}: maximum asteroid count in size-1 units. */
    public int asteroidMax;

    /** Resolve from the options and the map. */
    public static ModeParams from(Options o, WorldMap map, int fps) {
        ModeParams m = new ModeParams();
        m.ballMass = f(o, Opt.BALL_MASS);
        m.ballConnectorLength = f(o, Opt.BALL_CONNECTOR_LENGTH);
        m.ballConnectorSpringConstant = f(o, Opt.BALL_CONNECTOR_SPRING_CONSTANT);
        m.ballConnectorDamping = f(o, Opt.BALL_CONNECTOR_DAMPING);
        m.maxBallConnectorRatio = f(o, Opt.MAX_BALL_CONNECTOR_RATIO);
        m.connectorIsString = o.getBool(Opt.CONNECTOR_IS_STRING);
        m.ballCollisions = o.getBool(Opt.BALL_COLLISIONS);
        m.ballSparkCollisions = o.getBool(Opt.BALL_SPARK_COLLISIONS);
        m.treasureKillTeam = o.getBool(Opt.TREASURE_KILL_TEAM);
        m.captureTheFlag = o.getBool(Opt.CAPTURE_THE_FLAG);
        m.treasureCollisionDestroys = o.getBool(Opt.TREASURE_COLLISION_DESTROYS);
        m.treasureCollisionMayKill = o.getBool(Opt.TREASURE_COLLISION_MAY_KILL);
        m.treasuresOnRadar = o.getBool(Opt.TREASURES_ON_RADAR);
        m.ballKillScoreMult = f(o, Opt.BALL_KILL_SCORE_MULT);

        m.timing = map.timing();
        m.ballrace = o.getBool(Opt.BALLRACE);
        m.ballraceConnect = o.getBool(Opt.BALLRACE_CONNECTED);
        m.checkpointRadius = f(o, Opt.CHECKPOINT_RADIUS);
        m.raceLaps = o.getInt(Opt.RACE_LAPS);

        m.asteroidCollisionMayKill = o.getBool(Opt.ASTEROID_COLLISION_MAY_KILL);
        m.asteroidsOnRadar = o.getBool(Opt.ASTEROIDS_ON_RADAR);
        m.asteroidPoints = f(o, Opt.ASTEROID_POINTS);
        m.asteroidMaxScore = f(o, Opt.ASTEROID_MAX_SCORE);
        m.asteroidItemProb = f(o, Opt.ASTEROID_ITEM_PROB);
        m.asteroidMaxItems = o.getInt(Opt.ASTEROID_MAX_ITEMS);
        m.asteroidProb = f(o, Opt.ASTEROID_PROB);
        m.maxAsteroidDensity = f(o, Opt.MAX_ASTEROID_DENSITY);
        m.asteroidConcentratorRadius = o.getInt(Opt.ASTEROID_CONCENTRATOR_RADIUS);
        m.asteroidConcentratorProb = f(o, Opt.ASTEROID_CONCENTRATOR_PROB);
        m.tuneAsteroids(map.width(), map.height(), fps);
        return m;
    }

    /**
     * C {@code Tune_asteroid_prob} (rules.c).
     * <p>
     * BEHAVIOR: chance = max(1, (int)(1 / (asteroidProb * blocks * FPS))) when asteroidProb &gt; 0, else 0.
     * BEHAVIOR: max = maxAsteroidDensity * blocks: 0 when not positive, at least 1, else truncated.
     * BEHAVIOR: asteroidConcentratorRadius is limited to [1, (int) diagonal in blocks], asteroidConcentratorProb
     * to [0, 1].
     */
    public void tuneAsteroids(int bw, int bh, int fps) {
        float max = maxAsteroidDensity * bw * bh;
        if (asteroidProb > 0) {
            asteroidChance = (int) (1.0 / (asteroidProb * bw * bh * fps));
            asteroidChance = Math.max(asteroidChance, 1);
        } else {
            asteroidChance = 0;
        }
        if (max > 0) {
            asteroidMax = max < 1 ? 1 : (int) max;
        } else {
            asteroidMax = 0;
        }
        int diagonal = (int) StrictMath.hypot(bw, bh);
        asteroidConcentratorRadius = Math.max(1, Math.min(diagonal, asteroidConcentratorRadius));
        asteroidConcentratorProb = Math.max(0f, Math.min(1f, asteroidConcentratorProb));
    }

    private static float f(Options o, Opt opt) {
        return (float) o.getDouble(opt);
    }
}
