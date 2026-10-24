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
package com.fulcrologic.jxpilot.client.app;

import com.fulcrologic.jxpilot.client.game.ClientWorld;
import com.fulcrologic.jxpilot.client.game.ParticlePool;
import com.fulcrologic.jxpilot.client.game.Players;
import com.fulcrologic.jxpilot.client.paint.Particles;
import com.fulcrologic.jxpilot.client.paint.RadarPainter;
import com.fulcrologic.jxpilot.client.paint.SceneData;
import com.fulcrologic.jxpilot.client.paint.ShipPainter;
import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.common.math.ShipShape;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.Wire;
import com.fulcrologic.jxpilot.common.net.Section;
import com.fulcrologic.jxpilot.common.net.SnapshotWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.fulcrologic.jxpilot.client.app.AppFixture.*;
import static com.fulcrologic.jxpilot.common.net.Records.ShipFlags.*;
import static org.junit.jupiter.api.Assertions.*;

class SceneFillerTest {
    final SceneFiller filler = new SceneFiller();
    final SceneData scene = new SceneData(8, 16, 256, 8);
    final RadarBlips radar = new RadarBlips(8);

    void fill(AppFixture f) {
        filler.fill(scene, radar, f.game);
    }

    @Nested
    @DisplayName("fill: ships")
    class Ships {
        @Test
        @DisplayName("own ship first, then other ships with shapes, painter flags, team colours, lock and names")
        void teamPlay() {
            AppFixture f = new AppFixture(true).players().twice(x -> x
                .self(SHIELD | THRUSTING, 100, 100, 0, FOE)
                .ship(MATE, CLOAKED, 200, 150, Math.PI / 2)
                .ship(FOE, DEFLECTOR | AUTOPILOT | REFUELING, 300, 200, 0));
            scene.selfId = 99;
            fill(f);
            Players p = f.game.players();
            assertAll(
                () -> assertEquals(3, scene.shipCount, "own ship and two others"),
                () -> assertEquals(100f, scene.shipX[0], "own ship x"),
                () -> assertEquals(100f, scene.shipY[0], "own ship y"),
                () -> assertEquals(0.0, scene.shipHeading[0], "own heading"),
                () -> assertSame(p.shape(p.slotOf(SELF)), scene.shipShape[0], "own shape from Players"),
                () -> assertEquals(SELF, scene.shipId[0], "own id"),
                () -> assertEquals(ShipPainter.SHIELD, scene.shipFlags[0], "only painter bits: THRUSTING dropped"),
                () -> assertEquals(Palette.WHITE, scene.shipColor[0], "own ship never blue, even on the own team"),
                () -> assertFalse(scene.shipLocked[0], "own ship not locked"),
                () -> assertEquals("Me", scene.shipName[0].toString(), "own name (the painter skips it)"),
                () -> assertEquals(200f, scene.shipX[1], "mate x"),
                () -> assertEquals(150f, scene.shipY[1], "mate y"),
                () -> assertEquals(Math.PI / 2, scene.shipHeading[1], 1e-6, "mate heading (u16 angle quantization)"),
                () -> assertEquals(ShipPainter.CLOAKED, scene.shipFlags[1], "cloak bit kept"),
                () -> assertEquals(Palette.BLUE, scene.shipColor[1], "same team in team play: blue"),
                () -> assertFalse(scene.shipLocked[1], "mate not locked"),
                () -> assertEquals("Mate", scene.shipName[1].toString(), "mate name"),
                () -> assertSame(ShipShape.defaultShip(), scene.shipShape[2], "foe without shape: default ship"),
                () -> assertEquals(ShipPainter.DEFLECTOR, scene.shipFlags[2], "deflector kept, others dropped"),
                () -> assertEquals(Palette.WHITE, scene.shipColor[2], "other team: white"),
                () -> assertTrue(scene.shipLocked[2], "the own lock target is marked locked"),
                () -> assertEquals("Foe", scene.shipName[2].toString(), "foe name"),
                () -> assertEquals(SELF, scene.selfId, "selfId from the own ship"),
                () -> assertEquals(0, scene.pausedCount, "no paused markers"));
        }

        @Test
        @DisplayName("without team play a team mate is white unless the server marks it FRIENDLY")
        void noTeamPlay() {
            AppFixture f = new AppFixture(false).players().twice(x -> x
                .self(0, 100, 100, 0, 0xFFFF)
                .ship(MATE, 0, 200, 150, 0)
                .ship(FOE, FRIENDLY, 300, 200, 0));
            fill(f);
            assertAll(
                () -> assertEquals(Palette.WHITE, scene.shipColor[1], "same team but no team play: white"),
                () -> assertEquals(Palette.BLUE, scene.shipColor[2], "FRIENDLY flag: blue"),
                () -> assertEquals(0, scene.shipFlags[2], "FRIENDLY is not a painter bit"),
                () -> assertFalse(scene.shipLocked[2], "no lock (NO_ID)"));
        }

        @Test
        @DisplayName("dead ships are skipped, paused ones become steady paused markers, unknown players get defaults")
        void deadPausedUnknown() {
            AppFixture f = new AppFixture(true).players().twice(x -> x
                .self(DEAD, 100, 100, 0, 0xFFFF)
                .ship(MATE, DEAD, 200, 150, 0)
                .ship(FOE, PAUSED | SHIELD, 300, 200, 0)
                .ship(30, SHIELD, 400, 250, 0));
            fill(f);
            assertAll(
                () -> assertEquals(1, scene.shipCount, "only the unknown player's ship: dead ones skipped"),
                () -> assertEquals(30, scene.shipId[0], "the unknown ship"),
                () -> assertSame(ShipShape.defaultShip(), scene.shipShape[0], "unknown player: default ship"),
                () -> assertNull(scene.shipName[0], "unknown player: no name"),
                () -> assertEquals(Palette.WHITE, scene.shipColor[0], "unknown player: not friendly"),
                () -> assertEquals(1, scene.pausedCount, "paused foe is a marker"),
                () -> assertEquals(300f, scene.pausedX[0], "marker x"),
                () -> assertEquals(200f, scene.pausedY[0], "marker y"),
                () -> assertEquals(0, scene.pausedCountdown[0], "countdown 0: shown steadily"),
                () -> assertEquals(SELF, scene.selfId, "dead own ship still sets selfId"),
                () -> assertFalse(radar.selfVisible, "dead own ship not on the radar"));
        }

        @Test
        @DisplayName("a paused own ship is a paused marker too")
        void pausedSelf() {
            AppFixture f = new AppFixture(true).players().twice(x -> x.self(PAUSED, 120, 110, 0, 0xFFFF));
            fill(f);
            assertAll(
                () -> assertEquals(0, scene.shipCount, "no ship"),
                () -> assertEquals(1, scene.pausedCount, "one marker"),
                () -> assertEquals(120f, scene.pausedX[0], "at the own ship"),
                () -> assertTrue(radar.selfVisible, "paused (not dead) own ship stays on the radar"));
        }

        @Test
        @DisplayName("before any snapshot: empty scene, loops 0, selfId from the player id, frame scalars set")
        void empty() {
            AppFixture f = new AppFixture(true).players();
            f.game.frame(T0, 0);
            scene.addShot(1, 1, 1, false);
            scene.damaged = 5;
            scene.roundDelay = 9;
            radar.add(1, 1, 3);
            fill(f);
            assertAll(
                () -> assertEquals(0, scene.shotCount, "cleared"),
                () -> assertEquals(0, radar.count(), "radar cleared"),
                () -> assertEquals(0L, scene.loops, "loops 0"),
                () -> assertEquals(14, scene.fps, "fps from the game"),
                () -> assertEquals(SELF, scene.selfId, "player id"),
                () -> assertEquals(0, scene.damaged, "not modelled: 0"),
                () -> assertEquals(0, scene.roundDelay, "not modelled: 0"));
        }

        @Test
        @DisplayName("without any player id selfId is -1")
        void noSelf() {
            AppFixture f = new AppFixture(true);
            f.game.onAccepted(0xFFFF);
            f.game.frame(T0, 0);
            fill(f);
            assertEquals(-1, scene.selfId, "NO_ID becomes -1");
        }
    }

    @Nested
    @DisplayName("fill: shots, particles, radar, loops")
    class Objects {
        @Test
        @DisplayName("shots keep snapshot order; the harmless bit, not the colour, makes C's blue teamshot")
        void shots() {
            AppFixture f = new AppFixture(true).players().twice(x -> x
                .self(0, 100, 100, 0, 0xFFFF)
                .shot(Records.ShotKind.SHOT, Records.ObjColor.ENEMY, 10, 20)
                .shot(Records.ShotKind.SHOT, Records.ObjColor.TEAM, true, 30, 40)
                .shot(Records.ShotKind.SHOT, Records.ObjColor.TEAM, 35, 45)
                .shot(Records.ShotKind.CANNON_SHOT, Records.ObjColor.RED, 50, 60)
                .shot(Records.ShotKind.SHOT, Records.ObjColor.OWN, 70, 80)
                .shot(Records.ShotKind.SHOT, Records.ObjColor.OWN, true, 75, 85)
                .shot(Records.ShotKind.CLUSTER, Records.ObjColor.ENEMY, true, 90, 95));
            fill(f);
            List<String> drawn = new ArrayList<>();
            for (int i = 0; i < scene.shotCount; i++) {
                drawn.add(scene.shotX[i] + "," + scene.shotY[i] + " c" + scene.shotColor[i] + (scene.shotTeam[i] ? " X" : " sq"));
            }
            int blue = Palette.BLUE;
            int white = Palette.WHITE;
            assertEquals(List.of("10.0,20.0 c" + white + " sq", "30.0,40.0 c" + blue + " X", "35.0,45.0 c" + white + " sq",
                    "50.0,60.0 c" + Palette.RED + " X", "70.0,80.0 c" + white + " sq", "75.0,85.0 c" + blue + " X",
                    "90.0,95.0 c" + blue + " X"), drawn,
                "every shot in order: enemy white square; harmless team mate blue X/+; a team mate's shot that can "
                    + "hurt (no teamImmunity) white square; nuclear blink red X/+; own white square; own harmless "
                    + "(selfImmunity) blue X/+; any harmless shot (paused owner's cluster) blue X/+");
        }

        @Test
        @DisplayName("every visible particle with its pool position and temperature colour; invisible ones skipped")
        void particles() {
            AppFixture f = new AppFixture(true).players();
            f.begin(T0_TICK).self(0, 100, 100, 0, 0xFFFF).burst(Records.ObjColor.WHITE).send(T0_TICK);
            f.begin(T0_TICK + 1).self(0, 100, 100, 0, 0xFFFF).send(T0_TICK + 1);
            f.update();
            fill(f);
            ClientWorld w = f.game.world();
            ParticlePool pp = w.particles();
            List<String> expected = new ArrayList<>();
            for (int i = 0; i < pp.count(); i++) {
                if (pp.visible(i)) {
                    expected.add(pp.x(i) + "," + pp.y(i) + ":" + SceneFiller.particleColor(pp.life(i), pp.birth(i),
                        w.renderTick(), w.frameFraction(), pp.color(i)));
                }
            }
            List<String> actual = new ArrayList<>();
            for (int i = 0; i < scene.particleCount; i++) {
                actual.add(scene.particleX[i] + "," + scene.particleY[i] + ":" + scene.particleColor[i]);
            }
            assertAll(
                () -> assertEquals(12, pp.count(), "the burst's 12 particles are alive"),
                () -> assertFalse(expected.isEmpty(), "some are visible"),
                () -> assertEquals(expected, actual, "exactly the visible particles, in pool order, with their colours"));
        }

        @Test
        @DisplayName("radar: blips in order with C size bytes, SELF records left out, own ship from its position")
        void radar() {
            AppFixture f = new AppFixture(true).players().twice(x -> x
                .self(0, 100f, 100f, 0.5f, 0xFFFF)
                .radar(100, 100, Records.Radar.FLAG_SELF)
                .radar(350, 175, Records.Radar.FLAG_FRIENDLY)
                .radar(600, 300, 0)
                .radar(10, 10, Records.Radar.FLAG_BALL));
            fill(f);
            assertAll(
                () -> assertEquals(3, radar.count(), "SELF record dropped"),
                () -> assertEquals(Wire.fromRadar8(Wire.toRadar8(350, 700), 700), radar.x(0), "cell centre x"),
                () -> assertEquals(Wire.fromRadar8(Wire.toRadar8(175, 350), 350), radar.y(0), "cell centre y"),
                () -> assertEquals(3 | 0x80, radar.sizeByte(0), "friend: 3 with the friend bit"),
                () -> assertEquals(3, radar.sizeByte(1), "enemy player: 3"),
                () -> assertEquals(2, radar.sizeByte(2), "ball: 2"),
                () -> assertTrue(radar.selfVisible, "own ship on the radar"),
                () -> assertEquals(100f, radar.selfX, "own x"),
                () -> assertEquals(100f, radar.selfY, "own y"),
                () -> assertEquals(0.5f, radar.selfHeading, 1e-6f, "own heading"));
        }

        @Test
        @DisplayName("loops is the newer whole tick around the render tick, in original frames")
        void loops() {
            AppFixture f = new AppFixture(true).players().twice(x -> x.self(0, 100, 100, 0, 0xFFFF));
            fill(f);
            assertAll(
                () -> assertEquals(101.12, f.game.world().renderTick(), 1e-6, "render tick 101.12 (fixture timing)"),
                () -> assertEquals(102L, scene.loops, "ceil(101.12) * 1 frame per tick"),
                () -> assertEquals(102L, SceneFiller.loops(f.game.world()), "same from the helper"));
        }
    }

    @Nested
    @DisplayName("helpers")
    class Helpers {
        @Test
        @DisplayName("shotColor and teamShot follow C's Frame_shots rules")
        void shotRules() {
            assertAll(
                () -> assertEquals(Palette.BLUE, SceneFiller.shotColor(Records.ObjColor.TEAM, true), "harmless: blue"),
                () -> assertEquals(Palette.BLUE, SceneFiller.shotColor(Records.ObjColor.ENEMY, true), "harmless enemy: blue"),
                () -> assertEquals(Palette.BLUE, SceneFiller.shotColor(Records.ObjColor.RED, true), "harmless red: blue"),
                () -> assertEquals(Palette.WHITE, SceneFiller.shotColor(Records.ObjColor.TEAM, false), "harmful team: white"),
                () -> assertEquals(Palette.WHITE, SceneFiller.shotColor(Records.ObjColor.OWN, false), "own: white"),
                () -> assertEquals(Palette.WHITE, SceneFiller.shotColor(Records.ObjColor.NEUTRAL, false), "neutral: white"),
                () -> assertEquals(Palette.WHITE, SceneFiller.shotColor(Records.ObjColor.WHITE, false), "white: white"),
                () -> assertEquals(Palette.BLUE, SceneFiller.shotColor(Records.ObjColor.BLUE, false),
                    "a harmful BLUE fast shot stays blue (C Paint_fastshots)"),
                () -> assertEquals(Palette.RED, SceneFiller.shotColor(Records.ObjColor.RED, false), "nuclear blink: red"),
                () -> assertTrue(SceneFiller.teamShot(Records.ObjColor.ENEMY, true), "harmless: X/+"),
                () -> assertTrue(SceneFiller.teamShot(Records.ObjColor.RED, false), "nuclear blink: X/+"),
                () -> assertFalse(SceneFiller.teamShot(Records.ObjColor.TEAM, false), "harmful team: square"),
                () -> assertFalse(SceneFiller.teamShot(Records.ObjColor.BLUE, false), "harmful blue fast shot: square"),
                () -> assertFalse(SceneFiller.teamShot(Records.ObjColor.OWN, false), "own: square"));
        }

        @Test
        @DisplayName("particleColor: whole frames of life left, blue-owned for afterburner sparks")
        void particleColor() {
            assertAll(
                () -> assertEquals(Particles.colorIndex(15, false),
                    SceneFiller.particleColor(20, 100, 110, 0.5f, Records.ObjColor.WHITE), "20 - 10*0.5 = 15 left"),
                () -> assertEquals(Particles.colorIndex(14, false),
                    SceneFiller.particleColor(20, 100, 111, 0.5f, Records.ObjColor.WHITE), "20 - 5.5 = 14.5 -> 14"),
                () -> assertEquals(Particles.colorIndex(15, true),
                    SceneFiller.particleColor(20, 100, 110, 0.5f, Records.ObjColor.BLUE), "BLUE: blue-owned"),
                () -> assertEquals(3, SceneFiller.particleColor(31, 0, 0, 1, Records.ObjColor.WHITE),
                    "31 left, white: level 31 >> 3 = 3 -> sparkColors[3] = 3"),
                () -> assertEquals(5, SceneFiller.particleColor(5, 0, 0, 1, Records.ObjColor.WHITE),
                    "5 left: level 0 -> colour 5"));
        }

        @Test
        @DisplayName("radarSize: C Frame_radar sizes and per-frame blinks")
        void radarSize() {
            int nuke = Records.Radar.FLAG_NUKE;
            assertAll(
                () -> assertEquals(SceneFiller.HIDDEN, SceneFiller.radarSize(Records.Radar.FLAG_SELF, 0), "self hidden"),
                () -> assertEquals(0, SceneFiller.radarSize(Records.Radar.FLAG_MISSILE, 0), "missile on even loops: 0"),
                () -> assertEquals(SceneFiller.HIDDEN, SceneFiller.radarSize(Records.Radar.FLAG_MISSILE, 1), "odd: hidden"),
                () -> assertEquals(3, SceneFiller.radarSize(Records.Radar.FLAG_MISSILE | nuke, 2), "nuclear missile, loops&2: 3"),
                () -> assertEquals(0, SceneFiller.radarSize(Records.Radar.FLAG_MISSILE | nuke, 4), "nuclear missile, !loops&2: 0"),
                () -> assertEquals(0, SceneFiller.radarSize(Records.Radar.FLAG_MINE, 5), "mine at loops%8 = 5: 0"),
                () -> assertEquals(SceneFiller.HIDDEN, SceneFiller.radarSize(Records.Radar.FLAG_MINE, 6), "mine at 6: hidden"),
                () -> assertEquals(SceneFiller.HIDDEN, SceneFiller.radarSize(Records.Radar.FLAG_MINE, 15), "mine at 7: hidden"),
                () -> assertEquals(3, SceneFiller.radarSize(Records.Radar.FLAG_MINE | nuke, 3), "nuclear mine, loops&2: 3"),
                () -> assertEquals(2, SceneFiller.radarSize(Records.Radar.FLAG_BALL, 1), "ball 2"),
                () -> assertEquals(RadarPainter.asteroidSizeByte(1), SceneFiller.radarSize(Records.Radar.FLAG_ASTEROID, 1),
                    "asteroid: size 1 byte"),
                () -> assertEquals(3, SceneFiller.radarSize(0, 0), "player 3"),
                () -> assertEquals(0x83, SceneFiller.radarSize(Records.Radar.FLAG_FRIENDLY, 0), "friend 3 | 0x80"),
                () -> assertEquals(3, SceneFiller.radarSize(Records.Radar.FLAG_LOCKED, 2), "locked at loops%5 = 2: shown"),
                () -> assertEquals(SceneFiller.HIDDEN, SceneFiller.radarSize(Records.Radar.FLAG_LOCKED, 3),
                    "locked at loops%5 = 3: hidden"),
                () -> assertEquals(SceneFiller.HIDDEN, SceneFiller.radarSize(Records.Radar.FLAG_LOCKED, 4),
                    "locked at loops%5 = 4: hidden"));
        }

        @Test
        @DisplayName("friendly: FRIENDLY flag, or team play with both teams known and equal")
        void friendly() {
            AppFixture f = new AppFixture(true).players().join(20, 255, 0, "NoTeam", "");
            Players p = f.game.players();
            assertAll(
                () -> assertTrue(SceneFiller.friendly(FRIENDLY, p, -1, false), "flag wins without anything else"),
                () -> assertTrue(SceneFiller.friendly(0, p, p.slotOf(MATE), true), "same team in team play"),
                () -> assertFalse(SceneFiller.friendly(0, p, p.slotOf(MATE), false), "same team, no team play"),
                () -> assertFalse(SceneFiller.friendly(0, p, p.slotOf(FOE), true), "other team"),
                () -> assertFalse(SceneFiller.friendly(0, p, -1, true), "unknown player"),
                () -> assertFalse(SceneFiller.friendly(0, p, p.slotOf(20), true), "NO_TEAM player"));
        }

        @Test
        @DisplayName("friendly needs the own player to be known")
        void friendlyWithoutSelf() {
            AppFixture f = new AppFixture(true).join(MATE, 1, 0, "Mate", "");
            f.game.players().setSelfId(50);
            Players p = f.game.players();
            assertFalse(SceneFiller.friendly(0, p, p.slotOf(MATE), true), "own player unknown: not friendly");
        }

        @Test
        @DisplayName("loops is never negative")
        void loopsClamp() {
            AppFixture f = new AppFixture(true).players();
            f.begin(0).self(0, 100, 100, 0, 0xFFFF).send(T0_TICK);
            f.game.frame(T0, 0);
            assertAll(
                () -> assertTrue(f.game.world().renderTick() < 0, "render tick before 0"),
                () -> assertEquals(0L, SceneFiller.loops(f.game.world()), "clamped to 0"));
        }
    }

    @Nested
    @DisplayName("fill: M2 objects, beams, timers")
    class ObjectsAndBeams {
        @Test
        @DisplayName("every object kind is mapped to its scene list with C's units")
        void objects() {
            AppFixture f = new AppFixture(true).players().twice(x -> {
                SnapshotWriter w = x.w;
                w.beginSection(Section.ITEM);
                Records.ItemObj.put(w, 100, 50, 7);
                w.endSection();
                w.beginSection(Section.MINE);
                Records.Mine.put(w, 110, 50, Records.Mine.FLAG_FRIENDLY, 3);
                Records.Mine.put(w, 112, 50, 0, 3);
                w.endSection();
                w.beginSection(Section.MISSILE);
                Records.Missile.put(w, 9, 120, 50, Math.PI / 2, Records.Missile.KIND_HEAT, 15);
                w.endSection();
                w.beginSection(Section.BALL);
                Records.Ball.put(w, 4, 130, 50, 2, 9);
                w.endSection();
                w.beginSection(Section.LASER);
                Records.Laser.put(w, 140, 50, Math.PI, 40, Records.ObjColor.RED);
                w.endSection();
                w.beginSection(Section.ASTEROID);
                Records.Asteroid.put(w, 150, 50, 3, Math.PI / 2);
                w.endSection();
                w.beginSection(Section.WRECKAGE);
                Records.Wreckage.put(w, 160, 50, 2, 8, Math.PI);
                w.endSection();
                w.beginSection(Section.ECM);
                Records.Ecm.put(w, 170, 50, 60);
                w.endSection();
                w.beginSection(Section.CONNECTOR);
                Records.Connector.put(w, 180, 50, 190, 60, Records.Connector.KIND_BALL);
                Records.Connector.put(w, 180, 70, 190, 80, Records.Connector.KIND_PRESSOR);
                w.endSection();
                w.beginSection(Section.TRANSPORTER);
                Records.Transporter.put(w, 200, 50, 210, 60);
                w.endSection();
            });
            fill(f);
            assertAll(
                () -> assertEquals(1, scene.itemCount, "one item"),
                () -> assertEquals(100f, scene.itemX[0], "item x"),
                () -> assertEquals(7, scene.itemType[0], "item type"),
                () -> assertEquals(2, scene.mineCount, "two mines"),
                () -> assertTrue(scene.mineTeam[0], "FRIENDLY: team mine"),
                () -> assertFalse(scene.mineTeam[1], "not friendly"),
                () -> assertNull(scene.mineName[0], "no mine names"),
                () -> assertEquals(15f, scene.missileLen[0], "missile length"),
                () -> assertEquals(32, scene.missileDir[0], "pi/2 = RES 32"),
                () -> assertEquals(130f, scene.ballX[0], "ball x"),
                () -> assertEquals(40f, scene.laserLen[0], "laser length"),
                () -> assertEquals(64, scene.laserDir[0], "pi = RES 64"),
                () -> assertEquals(Palette.RED, scene.laserColor[0], "red laser"),
                () -> assertEquals(0, scene.asteroidType[0], "asteroid type 0"),
                () -> assertEquals(32, scene.asteroidRot[0], "asteroid rotation RES"),
                () -> assertEquals(3, scene.asteroidSize[0], "asteroid size"),
                () -> assertFalse(scene.wreckDeadly[0], "wreck not deadly"),
                () -> assertEquals(2, scene.wreckType[0], "wreck shape"),
                () -> assertEquals(64, scene.wreckRot[0], "wreck rotation RES"),
                () -> assertEquals(8, scene.wreckSize[0], "wreck size"),
                () -> assertEquals(120f, scene.ecmSize[0], "ECM diameter = 2 * radius"),
                () -> assertEquals(3, scene.linkCount, "three links"),
                () -> assertEquals(SceneData.LINK_CONNECTOR, scene.linkKind[0], "ball connector: dashed pick-up line"),
                () -> assertEquals(190f, scene.linkX2[0], "connector end"),
                () -> assertEquals(SceneData.LINK_TRACTOR, scene.linkKind[1], "pressor: beam"),
                () -> assertEquals(SceneData.LINK_TRANSPORTER, scene.linkKind[2], "transporter"));
        }

        @Test
        @DisplayName("refuel and repair beams are drawn from the server's endpoints; ship flags alone draw none")
        void beams() {
            AppFixture f = new AppFixture(true).players().twice(x -> x
                .self(Records.ShipFlags.REFUELING, 400, 300, 0, 0xFFFF)
                .ship(FOE, Records.ShipFlags.REPAIRING, 600, 240, 0)
                .ship(MATE, Records.ShipFlags.REFUELING, 700, 300, 0)
                .connector(472.5, 297.5, 400, 300, Records.Connector.KIND_REFUEL)
                .connector(600, 240, 612.5, 297.5, Records.Connector.KIND_REPAIR));
            fill(f);
            assertAll(
                () -> assertEquals(2, scene.linkCount, "one beam per record; the mate's REFUELING flag adds none"),
                () -> assertEquals(SceneData.LINK_REFUEL, scene.linkKind[0], "refuel: LINK_REFUEL"),
                () -> assertEquals(472.5f, scene.linkX1[0], "from the station x"),
                () -> assertEquals(297.5f, scene.linkY1[0], "from the station y"),
                () -> assertEquals(400f, scene.linkX2[0], "to the ship x"),
                () -> assertEquals(300f, scene.linkY2[0], "to the ship y"),
                () -> assertEquals(SceneData.LINK_REFUEL, scene.linkKind[1], "repair: also LINK_REFUEL (C Paint_refuel)"),
                () -> assertEquals(612.5f, scene.linkX2[1], "repair beam ends at the target"),
                () -> assertEquals(SceneData.LINK_CONNECTOR, SceneFiller.linkKind(Records.Connector.KIND_BALL), "ball"),
                () -> assertEquals(SceneData.LINK_TRACTOR, SceneFiller.linkKind(Records.Connector.KIND_TRACTOR), "tractor"),
                () -> assertEquals(SceneData.LINK_TRACTOR, SceneFiller.linkKind(Records.Connector.KIND_PRESSOR),
                    "pressor"));
        }

        @Test
        @DisplayName("a connected ball is tethered to its holder's drawn ship (own or other, the near copy); free or "
            + "unseen holders have none")
        void ballTether() {
            AppFixture f = new AppFixture(true).players().twice(x -> {
                x.self(0, 100, 100, 0, 0xFFFF).ship(MATE, 0, 400, 100, 0);
                x.w.beginSection(Section.BALL);
                Records.Ball.put(x.w, Protocol.NO_ID, 150, 120, Protocol.NO_TEAM, AppFixture.SELF);
                Records.Ball.put(x.w, Protocol.NO_ID, 380, 140, Protocol.NO_TEAM, MATE);
                Records.Ball.put(x.w, Protocol.NO_ID, 300, 300, Protocol.NO_TEAM, Protocol.NO_ID);
                Records.Ball.put(x.w, Protocol.NO_ID, 330, 300, Protocol.NO_TEAM, FOE);
                Records.Ball.put(x.w, Protocol.NO_ID, 680, 100, Protocol.NO_TEAM, AppFixture.SELF);
                x.w.endSection();
            });
            fill(f);
            assertAll(
                () -> assertEquals(5, scene.ballCount, "every ball drawn"),
                () -> assertEquals(3, scene.linkCount, "three tethers: own (twice) and the mate's"),
                () -> assertEquals(SceneData.LINK_BALL, scene.linkKind[0], "solid ball line"),
                () -> assertEquals(150f, scene.linkX1[0], "from the ball"),
                () -> assertEquals(100f, scene.linkX2[0], "to the own ship x"),
                () -> assertEquals(100f, scene.linkY2[0], "to the own ship y"),
                () -> assertEquals(400f, scene.linkX2[1], "the mate's ball to the mate"),
                () -> assertEquals(800f, scene.linkX2[2], "across the seam: the own ship's copy nearest the ball"));
        }

        @Test
        @DisplayName("connectors and beams across the seam are drawn the short way")
        void shortLines() {
            AppFixture f = new AppFixture(true).players().twice(x -> x
                .self(0, 100, 100, 0, 0xFFFF)
                .connector(20, 100, 680, 100, Records.Connector.KIND_BALL)
                .connector(100, 340, 100, 20, Records.Connector.KIND_REFUEL));
            fill(f);
            assertAll(
                () -> assertEquals(2, scene.linkCount, "both lines"),
                () -> assertEquals(-20f, scene.linkX2[0], "680 is 40 px left of 20 across the seam (700 wide)"),
                () -> assertEquals(100f, scene.linkY2[0], "y unchanged"),
                () -> assertEquals(370f, scene.linkY2[1], "20 is 30 px above 340 across the seam (350 high)"));
        }

        @Test
        @DisplayName("damaged and round delay come from the world; a pending round makes non-friendly ships red")
        void timers() {
            AppFixture f = new AppFixture(true).players().twice(x -> {
                x.self(0, 100, 100, 0, 0xFFFF).ship(MATE, 0, 200, 100, 0).ship(FOE, 0, 300, 100, 0);
                x.w.beginSection(Section.TIMERS);
                Records.Timers.put(x.w, 12, 30, -1);
                x.w.endSection();
            });
            fill(f);
            assertAll(
                () -> assertEquals(12, scene.damaged, "damaged"),
                () -> assertEquals(30, scene.roundDelay, "round delay"),
                () -> assertEquals(Palette.RED, scene.shipColor[0], "own ship red while the round is pending"),
                () -> assertEquals(Palette.BLUE, scene.shipColor[1], "team mate stays blue"),
                () -> assertEquals(Palette.RED, scene.shipColor[2], "foe red"));
        }

        @Test
        @DisplayName("pointColor: temperature levels through sparkColors (clamped), else object colours; res rounds")
        void colourAndRes() {
            int base = ClientWorld.POINT_LEVEL_BASE;
            assertAll(
                () -> assertEquals(5, SceneFiller.pointColor(base), "level 0: sparkColors[0] = 5"),
                () -> assertEquals(3, SceneFiller.pointColor(base + 3), "level 3: sparkColors[3] = 3"),
                () -> assertEquals(3, SceneFiller.pointColor(base + 9), "level 9 clamped to 3"),
                () -> assertEquals(Palette.RED, SceneFiller.pointColor(Records.ObjColor.RED), "red"),
                () -> assertEquals(Palette.BLUE, SceneFiller.pointColor(Records.ObjColor.BLUE), "blue"),
                () -> assertEquals(Palette.WHITE, SceneFiller.pointColor(Records.ObjColor.NEUTRAL), "other: white"),
                () -> assertEquals(0, SceneFiller.res(0), "0"),
                () -> assertEquals(96, SceneFiller.res(-Math.PI / 2), "-pi/2 wraps to 96"),
                () -> assertEquals(0, SceneFiller.res(2 * Math.PI - 0.01), "just under a turn rounds to 128 = 0"),
                () -> assertEquals(1, SceneFiller.res(0.04), "0.81 rounds to 1"));
        }

        @Test
        @DisplayName("tracked point particles join the particles after the pool's")
        void points() {
            AppFixture f = new AppFixture(true).players();
            for (int t = T0_TICK; t <= T0_TICK + 1; t++) {
                f.begin(t);
                com.fulcrologic.jxpilot.common.math.DebrisBurst d = new com.fulcrologic.jxpilot.common.math.DebrisBurst();
                d.x = 300;
                d.y = 100;
                d.count = 1;
                f.w.beginSection(Section.BURST);
                Records.Burst.put(f.w, d, Records.BurstKind.SPARK, ClientWorld.POINT_LEVEL_BASE + 1, 0);
                f.w.endSection();
                f.send(t);
            }
            f.update();
            fill(f);
            assertAll(
                () -> assertEquals(1, scene.particleCount, "the point"),
                () -> assertEquals(300f, scene.particleX[0], "x"),
                () -> assertEquals(6, scene.particleColor[0], "level 1: sparkColors[1] = 6"));
        }
    }
}
