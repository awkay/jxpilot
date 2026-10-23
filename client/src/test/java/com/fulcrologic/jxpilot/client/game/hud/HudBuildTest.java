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
package com.fulcrologic.jxpilot.client.game.hud;

import com.fulcrologic.jxpilot.client.game.ClientGame;
import com.fulcrologic.jxpilot.client.game.ScoreObjects;
import com.fulcrologic.jxpilot.client.net.ConnectionState;
import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.Section;
import com.fulcrologic.jxpilot.common.net.SnapshotWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static com.fulcrologic.jxpilot.client.game.hud.HudTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** {@link Hud#build} orchestration and remaining layout boundaries. */
class HudBuildTest {
    static final long T0 = 1_000_000_000L;

    /** A playing game with an own ship at the given fuel, flags and velocity. */
    static ClientGame game(float fuel, int flags, float vx, float vy) {
        Options o = new Options();
        ConfigParser.parse("mapWidth: 4\nmapHeight: 4\nframesPerSecond: 14\nmapData: \\multiline: E\n    \n    \n    \n    \nE\n",
            "t", OptionOrigin.MAP, o);
        WorldMap m = WorldMap.fromOptions(o);
        Msg.Welcome w = new Msg.Welcome();
        w.simHz = 60;
        w.snapshotHz = 30;
        w.gameFps = 14;
        ClientGame g = new ClientGame(8, 8, 64, 8);
        g.onAccepted(7);
        g.onWelcome(w);
        g.onConfig(w, o, m);
        g.onStateChange(ConnectionState.PLAYING, "Playing");
        ByteBuffer b = ByteBuffer.allocate(Protocol.MAX_PACKET);
        SnapshotWriter sw = new SnapshotWriter();
        sw.setWorld(140, 140, false);
        sw.begin(b, 100, 0, 0, 1, 0, 70, 70);
        sw.beginSection(Section.SELF);
        Records.Self.put(sw, 7, flags, 70, 70, vx, vy, 0, fuel, 1000, Protocol.NO_ID, 0, 0, 0);
        sw.endSection();
        b.flip();
        g.onSnapshot(b, T0);
        g.frame(T0, 0);
        return g;
    }

    @Nested
    @DisplayName("build")
    class Build {
        @Test
        @DisplayName("a live ship gets HUD lines, fuel text and gauge; messages and talk follow")
        void live() {
            ClientGame g = game(300, 0, 0, 0);
            g.messages().add(Msg.Text.KIND_GAME, "hello");
            TalkLine talk = new TalkLine();
            talk.open();
            HudFrame f = new HudFrame();
            f.line(0, 0, 0, 0, 0, 0);
            new Hud().build(f, g, talk, 800, 600, M, 0);
            assertAll(
                () -> assertEquals(List.of("310,230-490,230 dashed", "310,370-490,370 dashed", "53,446-53,459"), lines(f),
                    "the frame was cleared; HUD lines then the talk cursor"),
                () -> assertEquals(List.of("box 475,235 11x131", "fill 477,327 8x38", "fill 50,443 700x19",
                    "box 48,441 704x23"), rects(f), "fuel gauge (38 px for 300/1000), talk window"),
                () -> assertEquals(List.of("473,383 0300", "10,587 ", "53,456 "), texts(f),
                    "fuel number, the untyped message, the empty talk line"));
        }

        @Test
        @DisplayName("a dead ship keeps HUD and meters (C paints them) but no speed pointer; a lost connection shows the status")
        void deadAndStatus() {
            ClientGame g = game(300, Records.ShipFlags.DEAD, 10, 5);
            g.onStateChange(ConnectionState.CONNECTING, "Lost");
            HudFrame f = new HudFrame();
            Hud h = new Hud();
            h.ptrMoveFact = 2;
            h.fuelMeter = true;
            h.build(f, g, new TalkLine(), 800, 600, M, 0);
            assertAll(
                () -> assertFalse(lines(f).contains("400,300-380,310"), "no speed pointer while dead (selfVisible)"),
                () -> assertTrue(lines(f).contains("310,230-490,230 dashed"), "HUD lines while dead"),
                () -> assertTrue(texts(f).contains("557,29 Fuel"), "meters while dead"),
                () -> assertTrue(texts(f).contains("473,383 0300"), "fuel text while dead"),
                () -> assertTrue(rects(f).contains("box 475,235 11x131"), "fuel gauge while dead"),
                () -> assertEquals(List.of("10,587 ", "386,300 Lost"), texts(f).subList(texts(f).size() - 2, texts(f).size()),
                    "the (not yet typed) loss message and the status at ((800-28)/2, 300)"));
        }

        @Test
        @DisplayName("fuel notify: a refill seen while the ship was visible notifies, the respawn refill after death does not")
        void fuelNotifyGate() {
            Hud h = new Hud();
            h.advance(1, 100, 14, true);
            h.advance(1, 120, 14, true);
            double whileVisible = h.fuelCount();
            Hud d = new Hud();
            d.advance(1, 100, 14, false);
            d.advance(1, 1000, 14, true);
            double afterDeath = d.fuelCount();
            d.advance(1, 1100, 14, true);
            assertAll(
                () -> assertEquals(42.0, whileVisible, "refuelling while visible: 3 s at 14 fps"),
                () -> assertEquals(0.0, afterDeath, "the refill arriving with the respawn (not visible before) is silent"),
                () -> assertEquals(42.0, d.fuelCount(), "the next increase while visible notifies"));
        }

        @Test
        @DisplayName("speed pointer, fuel meter, score message and showHud off")
        void options() {
            ClientGame g = game(800, 0, 10, 5);
            Msg.PlayerJoin j = new Msg.PlayerJoin();
            j.id = 7;
            ByteBuffer jb = ByteBuffer.allocate(200);
            j.write(jb);
            g.onReliable(com.fulcrologic.jxpilot.common.net.ReliableType.PLAYER_JOIN, jb.flip());
            Msg.Score s = new Msg.Score();
            s.id = 7;
            s.score = 1;
            ByteBuffer sb = ByteBuffer.allocate(20);
            s.write(sb);
            g.onReliable(com.fulcrologic.jxpilot.common.net.ReliableType.SCORE, sb.flip());
            s.score = 4;
            sb.clear();
            s.write(sb);
            g.onReliable(com.fulcrologic.jxpilot.common.net.ReliableType.SCORE, sb.flip());
            Hud h = new Hud();
            h.ptrMoveFact = 2;
            h.fuelMeter = true;
            HudFrame f = new HudFrame();
            h.build(f, g, new TalkLine(), 800, 600, M, 0);
            Hud off = new Hud();
            off.showHud = false;
            HudFrame f2 = new HudFrame();
            off.build(f2, g, new TalkLine(), 800, 600, M, 0);
            assertAll(
                () -> assertEquals("400,300-380,310", lines(f).get(0), "speed pointer against the velocity"),
                () -> assertEquals("557,29 Fuel", texts(f).get(0), "fuel meter title"),
                () -> assertTrue(texts(f).contains("390,383 3.0"), "own score change under the HUD"),
                () -> assertEquals(0, f2.lineCount(), "showHud off: no lines"),
                () -> assertEquals(0, f2.textCount(), "and no HUD texts"));
        }
    }

    @Nested
    @DisplayName("boundaries")
    class Bounds {
        @Test
        @DisplayName("fuel limits, blink phases, tank text and meter at offset 0")
        void limits() {
            Hud h = new Hud();
            HudFrame f = new HudFrame();
            boolean atNotify = h.fuelText(f, 0, 0, 500, 0, 0, M);
            boolean belowNotify = h.fuelText(f, 0, 0, 499, 0, 0, M);
            int textsNoTank = f.textCount();
            boolean gaugeAtNotify = h.fuelGaugeVisible(500);
            h.advance(1, 0, 14, true);
            boolean critPhase1 = h.fuelGaugeVisible(50);
            h.advance(2, 0, 14, true);
            boolean warnPhase3 = h.fuelGaugeVisible(150);
            boolean critPhase3 = h.fuelGaugeVisible(50);
            h.advance(1, 0, 14, true);
            boolean equalFuel = h.fuelCount() == 0;
            h.notifyFuel(14);
            double notified = h.fuelCount();
            HudFrame m = new HudFrame();
            h.meter(m, 800, 0, 10, "X", 1, 2, M);
            assertAll(
                () -> assertFalse(atNotify, "fuel equal to fuelNotify is not shown"),
                () -> assertTrue(belowNotify, "just below is"),
                () -> assertEquals(1, textsNoTank, "no tank text without tanks"),
                () -> assertFalse(gaugeAtNotify, "gauge hidden at fuelNotify"),
                () -> assertTrue(critPhase1, "critical blink on at loops 1"),
                () -> assertTrue(warnPhase3, "warning blink on at loops 3"),
                () -> assertFalse(critPhase3, "critical blink off at loops 3"),
                () -> assertTrue(equalFuel, "unchanged fuel does not notify"),
                () -> assertEquals(42.0, notified, "3 s at 14 fps"),
                () -> assertEquals("205,19 X", texts(m).get(0), "offset 0 puts the title right of the meter"));
        }

        @Test
        @DisplayName("a score message exactly as wide as the HUD stays on the first line")
        void scoreWidth() {
            HudFrame f = new HudFrame();
            ScoreObjects so = new ScoreObjects();
            so.add(12.5f, null, false, 0, 0);
            new Hud().scoreStack(f, 400, 300, so, true, new Mono(35));
            assertEquals("330,383 12.5", texts(f).get(0), "width 140 is not wider than 140");
        }

        @Test
        @DisplayName("HudFrame text length limits and colours")
        void frame() {
            HudFrame f = new HudFrame();
            f.text(0, 0, HudColor.RED).append("abc");
            f.setTextLength(0);
            f.line(0, 0, 1, 1, HudFrame.SOLID, HudColor.WHITE);
            f.rect(0, 0, 1, 1, false, HudColor.BLACK);
            assertAll(
                () -> assertEquals(0, f.textLength(0), "a zero limit draws nothing"),
                () -> assertEquals(HudColor.WHITE, f.lineColor(0), "line colour"),
                () -> assertEquals(HudColor.BLACK, f.rectColor(0), "rect colour"));
        }
    }
}
