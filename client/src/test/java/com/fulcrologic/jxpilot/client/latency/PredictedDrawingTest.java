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
package com.fulcrologic.jxpilot.client.latency;

import com.fulcrologic.jxpilot.client.game.ClientWorld;
import com.fulcrologic.jxpilot.client.game.predict.OwnShipPredictor;
import com.fulcrologic.jxpilot.client.game.predict.ProvisionalShots;
import com.fulcrologic.jxpilot.common.net.Records;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The own ship's consumers read the predicted state (latency-fixes.md §4.1, phase 1c): against the real server, an
 * active pilot (turning, thrusting, firing) is drawn from the {@link OwnShipPredictor} whenever it is active (the
 * own ship, the camera that follows it, its flags and fuel), the own bullets from the {@link ProvisionalShots} with
 * the snapshots' own bullets left out, and the own exhaust from the predicted ticks; with prediction off, the snapshot
 * timeline (F1) draws them as before.
 */
class PredictedDrawingTest {
    /** Per-frame checks of a predicted game. */
    static final class Check {
        long predicted;
        long mismatched;
        long ownSnapshotShots;
        long provisionalFrames;
        long provisionalMismatch;
        long ownParticles;
        String first = "";

        void frame(ClientWorld w, OwnShipPredictor p) {
            if (!w.selfPredicted()) {
                return;
            }
            predicted++;
            boolean same = w.selfX() == (float) p.x() && w.selfY() == (float) p.y()
                && w.selfHeading() == (float) p.heading() && w.selfVx() == p.vx() && w.selfVy() == p.vy()
                && w.selfFuel() == (float) (p.fuel() / 256) && w.selfDrawnTick() == p.drawnTick()
                && w.selfFlags() == p.flags(w.selfFlags());
            if (!same) {
                mismatched++;
                if (first.isEmpty()) {
                    first = "x " + w.selfX() + "/" + p.x() + " heading " + w.selfHeading() + "/" + p.heading();
                }
            }
            ProvisionalShots ps = p.shots();
            int n = w.shotCount();
            int own = w.provisionalShotCount();
            for (int i = 0; i < n - own; i++) {
                if (w.shotColor(i) == Records.ObjColor.OWN && w.shotKind(i) == Records.ShotKind.SHOT) {
                    ownSnapshotShots++;
                }
            }
            if (own > 0) {
                provisionalFrames++;
                for (int i = 0; i < own; i++) {
                    if (w.shotX(n - own + i) != ps.x(i) || w.shotY(n - own + i) != ps.y(i)
                        || w.shotColor(n - own + i) != Records.ObjColor.OWN) {
                        provisionalMismatch++;
                    }
                }
            }
            for (int i = 0; i < w.particles().count(); i++) {
                if (w.particles().own(i)) {
                    ownParticles++;
                    break;
                }
            }
        }
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    @DisplayName("predicted: the own ship, its shots and exhaust come from the predictor; off: from the snapshots")
    void consumers() {
        Scenario s = new Scenario(100, Scenario.Jitter.NONE, Scenario.Loss.NONE);
        LatencyRig rig = new LatencyRig(Course.FREE_FLIGHT.serverArgs(0), s.direction(), s.direction(), 2,
            InputToPhoton.tickPhase(2));
        long spawn = rig.runUntilPlaying(LatencyRig.START + InputToPhoton.SPAWN_LIMIT);
        rig.setKeys(t -> Course.pilot(t - spawn));
        OwnShipPredictor p = rig.client.game().predictor();
        Check c = new Check();
        long[] frames = {0};
        rig.onFrame = (r, t) -> {
            frames[0]++;
            c.frame(r.world(), p);
        };
        rig.runUntil(spawn + 20 * LatencyRig.SEC);
        p.enabled = false;
        long[] offPredicted = {0};
        long[] offOwnShots = {0};
        rig.onFrame = (r, t) -> {
            ClientWorld w = r.world();
            if (w.selfPredicted()) {
                offPredicted[0]++;
            }
            for (int i = 0; i < w.shotCount(); i++) {
                if (w.shotColor(i) == Records.ObjColor.OWN) {
                    offOwnShots[0]++;
                }
            }
        };
        rig.runUntil(spawn + 30 * LatencyRig.SEC);
        assertAll(
            () -> assertTrue(c.predicted * 10 > frames[0] * 8, "predicted in most frames: " + c.predicted + " of "
                + frames[0]),
            () -> assertEquals(0, c.mismatched, "the drawn own ship is the predictor's: " + c.first),
            () -> assertEquals(0, c.ownSnapshotShots, "no snapshot own bullet drawn while predicting"),
            () -> assertTrue(c.provisionalFrames > 100, "provisional shots drawn: " + c.provisionalFrames),
            () -> assertEquals(0, c.provisionalMismatch, "drawn where the provisional shots are, in the own colour"),
            () -> assertTrue(c.ownParticles > 100, "own exhaust from the predicted ticks: " + c.ownParticles),
            () -> assertEquals(0, offPredicted[0], "prediction off: never predicted"),
            () -> assertTrue(offOwnShots[0] > 0, "prediction off: the snapshots' own bullets are drawn"));
    }
}
