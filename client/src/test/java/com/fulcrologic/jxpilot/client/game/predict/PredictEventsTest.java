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
package com.fulcrologic.jxpilot.client.game.predict;

import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.common.sim.KernelEvents;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.common.sim.SeededTieBreak;
import com.fulcrologic.jxpilot.common.sim.ShipBody;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link PredictEvents}: what the own-ship prediction honours and records of the kernel's events. */
class PredictEventsTest {
    /** Records the bullets. */
    static final class Shots implements PredictEvents.ShotSink {
        final StringBuilder log = new StringBuilder();

        @Override
        public void fired(int tick, int ordinal, float x, float y, float vx, float vy, boolean speculative) {
            log.append(tick).append('/').append(ordinal).append('@').append(x).append(',').append(y).append(' ')
                .append(vx).append(',').append(vy).append(speculative ? " spec" : "").append(';');
        }
    }

    @Nested
    @DisplayName("bullets")
    class Bullets {
        @Test
        @DisplayName("positioned bullets go to the sink numbered within the step; every bullet counts")
        void numbered() {
            PredictEvents ev = new PredictEvents(new SeededTieBreak());
            Shots sink = new Shots();
            ev.setShotSink(sink);
            ShipBody b = new ShipBody();
            ev.beginStep(7, false);
            int h0 = ev.shot(b, 1, 2, 0, 3, 4, false);
            ev.shot(b, 5, 6, 0, 7, 8, false);
            ev.shot(b, 0, 0, 0, 0, 0, true);
            int fired = ev.fired();
            ev.beginStep(8, true);
            ev.shot(b, 9, 9, 0, 1, 1, false);
            assertAll(
                () -> assertEquals("7/0@1.0,2.0 3.0,4.0;7/1@5.0,6.0 7.0,8.0;8/0@9.0,9.0 1.0,1.0 spec;",
                    sink.log.toString(), "tick, ordinal, start, velocity and the speculative mark; the outside one"
                        + " is not drawn; the next step numbers from 0"),
                () -> assertEquals(-1, h0, "no object handle"),
                () -> assertEquals(3, fired, "the outside bullet counts (the server's expires and gives it back)"),
                () -> assertEquals(1, ev.fired(), "beginStep resets the count"),
                () -> assertTrue(ev.shotRoom(), "always room"));
        }

        @Test
        @DisplayName("each step is announced to the sink (tick, speculative); an outside bullet has no handle either")
        void announced() {
            PredictEvents ev = new PredictEvents(new SeededTieBreak());
            StringBuilder log = new StringBuilder();
            ev.setShotSink(new PredictEvents.ShotSink() {
                @Override
                public void fired(int tick, int ordinal, float x, float y, float vx, float vy, boolean speculative) {
                }

                @Override
                public void beginStep(int tick, boolean speculative) {
                    log.append(tick).append(speculative ? "s" : "").append(';');
                }
            });
            ev.beginStep(4, false);
            ev.beginStep(5, true);
            int outside = ev.shot(new ShipBody(), 0, 0, 0, 0, 0, true);
            PredictEvents bare = new PredictEvents(new SeededTieBreak());
            bare.beginStep(1, false);
            assertAll(
                () -> assertEquals("4;5s;", log.toString(), "both steps announced"),
                () -> assertEquals(-1, outside, "no handle for an outside bullet"),
                () -> assertEquals(0, bare.fired(), "a step without a sink is fine"));
        }

        @Test
        @DisplayName("without a sink bullets are only counted")
        void noSink() {
            PredictEvents ev = new PredictEvents(new SeededTieBreak());
            ev.beginStep(1, false);
            ev.shot(new ShipBody(), 1, 1, 0, 1, 1, false);
            assertEquals(1, ev.fired(), "counted");
        }
    }

    @Nested
    @DisplayName("records")
    class Records {
        @Test
        @DisplayName("a crash sets KILLED and is remembered; warp, PAUSE and exhaust are remembered; beginStep clears")
        void remembered() {
            PredictEvents ev = new PredictEvents(new SeededTieBreak());
            ShipBody b = new ShipBody();
            b.status = Obj.PLAYING;
            ev.beginStep(1, false);
            ev.crashed(b, null, 0);
            boolean warpBreaks = ev.warp(b);
            boolean pauseStops = ev.keyPressed(b, ControlKey.PAUSE);
            ev.thrustSparks(b);
            boolean crashed = ev.crashed();
            boolean warped = ev.warped();
            boolean pause = ev.pauseKey();
            boolean thrust = ev.thrust();
            ev.beginStep(2, false);
            assertAll(
                () -> assertEquals(Obj.PLAYING | Obj.KILLED, b.status, "KILLED is set, nothing else changes"),
                () -> assertTrue(crashed, "crash remembered"),
                () -> assertTrue(warped, "warp remembered"),
                () -> assertFalse(warpBreaks, "the warp does not break the kernel's move"),
                () -> assertTrue(pause, "PAUSE remembered"),
                () -> assertFalse(pauseStops, "no key stops the key handling"),
                () -> assertTrue(thrust, "exhaust remembered"),
                () -> assertFalse(ev.crashed(), "cleared: crash"),
                () -> assertFalse(ev.warped(), "cleared: warp"),
                () -> assertFalse(ev.pauseKey(), "cleared: pause"),
                () -> assertFalse(ev.thrust(), "cleared: exhaust"));
        }

        @Test
        @DisplayName("other keys are not remembered as PAUSE; the no-op events change nothing")
        void others() {
            PredictEvents ev = new PredictEvents(new SeededTieBreak());
            ShipBody b = new ShipBody();
            b.status = Obj.PLAYING;
            b.fuel = 100;
            ev.beginStep(1, false);
            boolean stops = ev.keyPressed(b, ControlKey.SHIELD);
            ev.keyReleased(b, ControlKey.PAUSE);
            ev.autopilotOff(b);
            ev.powerChanged(b);
            ev.devices(b);
            ev.upkeep(b);
            ev.turnSparks(b, 3);
            ev.sensors(b);
            ev.wallDamage(b);
            ev.bounced(b, 0, 10, -1);
            assertAll(
                () -> assertFalse(stops, "SHIELD does not stop"),
                () -> assertFalse(ev.pauseKey(), "SHIELD and a PAUSE release are not a PAUSE press"),
                () -> assertEquals(Obj.PLAYING, b.status, "status untouched"),
                () -> assertEquals(100, b.fuel, "fuel untouched"));
        }
    }

    @Nested
    @DisplayName("bounce tie-break")
    class TieBreak {
        @Test
        @DisplayName("with predictableBounces the fly phase reseeds from (tick, own id); otherwise never")
        void reseed() {
            SeededTieBreak t = new SeededTieBreak();
            PredictEvents ev = new PredictEvents(t);
            SeededTieBreak ref = new SeededTieBreak();
            ref.reseed(42, 3);
            double want = ref.getAsDouble();
            ev.configure(true, 3);
            ev.beginStep(42, false);
            t.reseed(1, 1);
            ev.begin(new ShipBody(), KernelEvents.PHASE_FLY);
            double got = t.getAsDouble();
            ev.beginStep(42, false);
            t.reseed(1, 1);
            ev.begin(new ShipBody(), KernelEvents.PHASE_KEYS);
            double otherPhase = t.getAsDouble();
            ev.configure(false, 3);
            t.reseed(1, 1);
            ev.begin(new ShipBody(), KernelEvents.PHASE_FLY);
            double off = t.getAsDouble();
            SeededTieBreak ref11 = new SeededTieBreak();
            ref11.reseed(1, 1);
            double untouched = ref11.getAsDouble();
            assertAll(
                () -> assertEquals(want, got, "reseeded from (42, 3) at the fly phase"),
                () -> assertEquals(untouched, otherPhase, "another phase does not reseed"),
                () -> assertEquals(untouched, off, "without predictableBounces no reseed"));
        }
    }
}
