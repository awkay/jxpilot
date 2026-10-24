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

import com.fulcrologic.jxpilot.client.game.ScoreObjects;
import com.fulcrologic.jxpilot.client.game.CharBuf;
import com.fulcrologic.jxpilot.client.game.MessageLog;
import com.fulcrologic.jxpilot.client.game.Players;
import com.fulcrologic.jxpilot.client.game.Scoreboard;
import com.fulcrologic.jxpilot.common.net.Msg;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HudTest {
    /** Fixed-width font: ascent 10, descent 3, {@code adv} px per char. */
    record Mono(int adv) implements TextMetrics {
        @Override
        public int ascent() { return 10; }

        @Override
        public int descent() { return 3; }

        @Override
        public int width(CharSequence s, int len) { return adv * len; }
    }

    static final Mono M = new Mono(7);

    static List<String> lines(HudFrame f) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < f.lineCount(); i++) {
            l.add((int) f.lineX0(i) + "," + (int) f.lineY0(i) + "-" + (int) f.lineX1(i) + "," + (int) f.lineY1(i)
                + (f.lineStyle(i) == HudFrame.DASHED ? " dashed" : ""));
        }
        return l;
    }

    static List<String> rects(HudFrame f) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < f.rectCount(); i++) {
            l.add((f.rectFilled(i) ? "fill " : "box ") + (int) f.rectX(i) + "," + (int) f.rectY(i) + " "
                + (int) f.rectW(i) + "x" + (int) f.rectH(i));
        }
        return l;
    }

    static List<String> texts(HudFrame f) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < f.textCount(); i++) {
            l.add((int) f.textX(i) + "," + (int) f.textY(i) + " " + f.text(i).subSequence(0, f.textLength(i)));
        }
        return l;
    }

    @Nested
    @DisplayName("ship HUD")
    class ShipHud {
        @Test
        @DisplayName("HUD centre moves against the velocity by speedFactHUD")
        void centre() {
            Hud h = new Hud();
            int cx = h.hudX(800, 5);
            h.hudMoveFact = 2;
            assertAll(
                () -> assertEquals(400, cx, "centred with factor 0"),
                () -> assertEquals(390, h.hudX(800, 5), "x = 400 - 2*5"),
                () -> assertEquals(306, h.hudY(600, 3), "y = 300 + 2*3 (window y down)"));
        }

        @Test
        @DisplayName("frame lines: horizontal by default, vertical optional, dashed")
        void hudLines() {
            Hud h = new Hud();
            HudFrame f = new HudFrame();
            h.hudLines(f, 400, 300);
            List<String> horizontal = lines(f);
            f.clear();
            h.verticalLines = true;
            h.horizontalLines = false;
            h.hudLines(f, 400, 300);
            assertAll(
                () -> assertEquals(List.of("310,230-490,230 dashed", "310,370-490,370 dashed"), horizontal,
                    "lines at y -+ 70 spanning x -+ 90"),
                () -> assertEquals(List.of("330,210-330,390 dashed", "470,210-470,390 dashed"), lines(f),
                    "vertical lines at x -+ 70 spanning y -+ 90"));
        }

        @Test
        @DisplayName("fuel gauge rectangles follow C's arithmetic")
        void gauge() {
            HudFrame f = new HudFrame();
            new Hud().fuelGauge(f, 400, 300, 500, 1000);
            HudFrame empty = new HudFrame();
            new Hud().fuelGauge(empty, 400, 300, 500, 0);
            assertAll(
                () -> assertEquals(List.of("box 475,235 11x131", "fill 477,301 8x64"), rects(f),
                    "outline and a half-full bar of 64 px"),
                () -> assertEquals(0f, empty.rectH(1), "fuelMax 0 draws an empty bar"));
        }

        @Test
        @DisplayName("fuel gauge visibility: notify, warning blink, critical fast blink, exact limits hidden")
        void gaugeVisibility() {
            Hud h = new Hud();
            boolean[] v = new boolean[10];
            v[0] = h.fuelGaugeVisible(50);
            h.advance(2, 50, 14, true);
            v[1] = h.fuelGaugeVisible(50);
            v[2] = h.fuelGaugeVisible(150);
            h.advance(2, 50, 14, true);
            v[3] = h.fuelGaugeVisible(150);
            v[4] = h.fuelGaugeVisible(300);
            v[5] = h.fuelGaugeVisible(600);
            v[6] = h.fuelGaugeVisible(100);
            v[7] = h.fuelGaugeVisible(200);
            h.notifyFuel(14);
            v[8] = h.fuelGaugeVisible(600);
            h.fuelGauge = false;
            v[9] = h.fuelGaugeVisible(50);
            assertAll(
                () -> assertTrue(v[0], "critical, loops 0: on"),
                () -> assertFalse(v[1], "critical, loops 2: off"),
                () -> assertTrue(v[2], "warning, loops 2: on"),
                () -> assertFalse(v[3], "warning, loops 4: off"),
                () -> assertTrue(v[4], "below notify: steady"),
                () -> assertFalse(v[5], "above notify: hidden"),
                () -> assertFalse(v[6], "exactly critical: hidden as in C"),
                () -> assertFalse(v[7], "exactly warning: hidden as in C"),
                () -> assertTrue(v[8], "the notification shows it at any level"),
                () -> assertFalse(v[9], "gauge off"));
        }

        @Test
        @DisplayName("fuel notification: refuelling restarts 3 s, then it counts down")
        void fuelNotification() {
            Hud h = new Hud();
            h.advance(1, 100, 14, true);
            double first = h.fuelCount();
            h.advance(1, 120, 14, true);
            double up = h.fuelCount();
            h.advance(10, 120, 14, true);
            double later = h.fuelCount();
            h.advance(100, 110, 14, true);
            assertAll(
                () -> assertEquals(0.0, first, "the first sample is no increase"),
                () -> assertEquals(42.0, up, "3 seconds at 14 fps"),
                () -> assertEquals(32.0, later, "counts down by frames"),
                () -> assertEquals(0.0, h.fuelCount(), "never below 0"),
                () -> assertEquals(112, h.loops(), "loops counts frames"));
        }

        @Test
        @DisplayName("fuel number, tank indicator and score message placement")
        void textPlacement() {
            Hud h = new Hud();
            HudFrame f = new HudFrame();
            boolean shown = h.fuelText(f, 400, 300, 300, 2, 0, M);
            h.fuelText(f, 400, 300, 300, 2, 3, M);
            boolean hidden = h.fuelText(new HudFrame(), 400, 300, 600, 0, 0, M);
            h.scoreStack(f, 400, 300, objects(2.5f), true, M);
            h.scoreStack(f, 400, 300, objects(1234567.5f), true, new Mono(20));
            h.scoreStack(f, 400, 300, objects(1234567.5f), false, new Mono(20));
            assertAll(
                () -> assertTrue(shown, "fuel below fuelNotify is shown"),
                () -> assertFalse(hidden, "fuel above fuelNotify without notification is not"),
                () -> assertEquals(List.of("473,383 0300", "473,396 M ", "473,383 0300", "473,396 T3",
                        "390,383 2.5", "310,396 1234567.5", "310,383 1234567.5"), texts(f),
                    "fuel %04d, M for the main tank, T<n>; a wide score message moves down a line only with fuel shown"));
        }

        static ScoreObjects objects(float... points) {
            ScoreObjects so = new ScoreObjects();
            for (float p : points) {
                so.add(p, null, false, 0, 0);
            }
            return so;
        }

        @Test
        @DisplayName("the score stack draws HUD texts oldest first, one line each; only the first is bumped; empty ones skipped")
        void scoreStack() {
            ScoreObjects so = new ScoreObjects();
            so.add(1, "Robby", false, 0, 0);
            so.add(2, "", false, 0, 0);
            so.add(-3, "[Wall]", false, 0, 0);
            HudFrame f = new HudFrame();
            new Hud().scoreStack(f, 400, 300, so, false, M);
            ScoreObjects wide = new ScoreObjects();
            wide.add(1, "a very long name indeed", false, 0, 0);
            wide.add(2, "x", false, 0, 0);
            HudFrame w = new HudFrame();
            new Hud().scoreStack(w, 400, 300, wide, true, M);
            ScoreObjects twoWide = new ScoreObjects();
            twoWide.add(1, "a very long name indeed", false, 0, 0);
            twoWide.add(2, "another long name here", false, 0, 0);
            HudFrame w2 = new HudFrame();
            new Hud().scoreStack(w2, 400, 300, twoWide, true, M);
            for (int i = 0; i < ScoreObjects.MAX_SCORE_OBJECTS; i++) {
                so.add(i, null, false, 0, 0);
            }
            HudFrame ring = new HudFrame();
            new Hud().scoreStack(ring, 400, 300, so, false, M);
            assertAll(
                () -> assertEquals(List.of("369,383 Robby 1.0", "362,396 [Wall] -3.0"), texts(f),
                    "oldest first; the empty message has no HUD text and takes no line"),
                () -> assertEquals(List.of("306,396 a very long name indeed 1.0", "383,409 x 2.0"), texts(w),
                    "a wide first text with fuel shown moves the whole stack down one line"),
                () -> assertEquals(List.of(396, 409), List.of((int) w2.textY(0), (int) w2.textY(1)),
                    "only the first text is bumped: a second wide one just follows"),
                () -> assertEquals("390,383 0.0", texts(ring).get(0), "after the ring wrapped the oldest is the slot after the newest"),
                () -> assertEquals(10, texts(ring).size(), "ten objects"));
        }

        @Test
        @DisplayName("meters follow Paint_meter")
        void meters() {
            Hud h = new Hud();
            HudFrame f = new HudFrame();
            h.meter(f, 800, -10, 20, "Fuel", 500, 1000, M);
            List<String> r = rects(f);
            List<String> l = lines(f);
            List<String> t = texts(f);
            HudFrame g = new HudFrame();
            h.meter(g, 800, 10, 40, "Power", 5, 0, M);
            assertAll(
                () -> assertEquals(List.of("fill 592,22 98x5", "box 590,20 200x8"), r, "bar and outline at viewW-210"),
                () -> assertEquals(List.of("590,16-590,32", "790,16-790,32", "690,17-690,31", "640,19-640,29",
                    "740,19-740,29"), l, "scale ticks"),
                () -> assertEquals(List.of("557,29 Fuel"), t, "title to the left"),
                () -> assertEquals("215,49 Power", texts(g).get(0), "positive offset: title to the right"),
                () -> assertEquals(985f, g.rectW(0), "max 0 counts as 1"));
        }
    }

    @Nested
    @DisplayName("messages, talk window, scoreboard")
    class Panels {
        @Test
        @DisplayName("talk messages from the top, game messages from the bottom, typed and coloured")
        void messages() {
            Hud h = new Hud();
            MessageLog log = new MessageLog();
            log.add(Msg.Text.KIND_CHAT, "older chat");
            log.add(Msg.Text.KIND_CHAT, "newer chat");
            log.add(Msg.Text.KIND_GAME, "game one");
            log.add(Msg.Text.KIND_GAME, "game two");
            log.tick(1.0);
            log.game(1).text();
            HudFrame f = new HudFrame();
            h.messages(f, log, 600, M, 14);
            int red = f.textColor(0);
            log.tick(200);
            HudFrame g = new HudFrame();
            h.showMessages = false;
            h.messages(g, log, 600, M, 14);
            assertAll(
                () -> assertEquals(List.of("10,20 new", "10,34 old", "10,587 gam", "10,573 gam"), texts(f),
                    "1 frame at 50 cps / 14 fps shows 3 chars; talk down from 20, game up from 587"),
                () -> assertEquals(HudColor.RED, red, "new messages flash red"),
                () -> assertEquals(HudColor.OLD_MESSAGE, g.textColor(0), "older ones use the old colour"),
                () -> assertEquals(2, g.textCount(), "game messages hidden when showMessages is off"));
        }

        @Test
        @DisplayName("talk window geometry and cursor")
        void talk() {
            Hud h = new Hud();
            TalkLine t = new TalkLine();
            t.open();
            t.type('h');
            t.type('e');
            t.type('y');
            t.left();
            HudFrame f = new HudFrame();
            h.talk(f, t, 800, 600, M);
            assertAll(
                () -> assertEquals(List.of("fill 50,443 700x19", "box 48,441 704x23"), rects(f),
                    "inner window and border"),
                () -> assertEquals(List.of("53,456 hey", "67,456 _"), texts(f),
                    "text inside the borders, then C's '_' cursor after 2 chars"),
                () -> assertEquals(List.of(), lines(f), "no cursor line"));
        }

        @Test
        @DisplayName("the talk cursor blinks every FPS / 2 frames; on an '_' it is emphasized; a selection is emphasized")
        void talkCursorAndSelection() {
            Hud h = new Hud();
            TalkLine t = new TalkLine();
            t.open();
            for (char c : "a_bc".toCharArray()) {
                t.type(c);
            }
            t.setCursor(1);
            HudFrame on = new HudFrame();
            h.talk(on, t, 800, 600, M);
            h.advance(6, 0, 14, false);
            HudFrame stillOn = new HudFrame();
            h.talk(stillOn, t, 800, 600, M);
            h.advance(1, 0, 14, false);
            HudFrame off = new HudFrame();
            h.talk(off, t, 800, 600, M);
            boolean blinkOff = !h.cursorVisible();
            h.advance(7, 0, 14, false);
            boolean backOn = h.cursorVisible();
            h.selection.start(TextSelection.TALK_LINE, 0, 2);
            h.selection.drag(TextSelection.TALK_LINE, 0, 4);
            HudFrame sel = new HudFrame();
            h.talk(sel, t, 800, 600, M);
            assertAll(
                () -> assertEquals(List.of("53,456 a_bc", "60,456 _"), texts(on), "cursor on the '_' at char 1"),
                () -> assertEquals(HudColor.EMPHASIZED, on.textColor(1), "an '_' under the cursor: inverse cursor"),
                () -> assertEquals(2, stillOn.textCount(), "6 of 14 frames: still in the first half"),
                () -> assertTrue(blinkOff, "at FPS / 2 = 7 frames the cursor goes off"),
                () -> assertEquals(1, off.textCount(), "only the text then"),
                () -> assertTrue(backOn, "and back on 7 frames later"),
                () -> assertEquals(List.of("53,456 a_", "67,456 bc"), texts(sel).subList(0, 2),
                    "the selected 'bc' is its own run"),
                () -> assertEquals(HudColor.EMPHASIZED, sel.textColor(1), "drawn emphasized"));
        }

        @Test
        @DisplayName("score list lines")
        void scoreboard() {
            Players p = new Players();
            Msg.PlayerJoin j = new Msg.PlayerJoin();
            j.id = 1;
            j.name.append("Ann");
            p.join(j);
            Msg.PlayerJoin k = new Msg.PlayerJoin();
            k.id = 2;
            k.name.append("Bob");
            p.join(k);
            p.setScore(2, 5, -1, Msg.Score.STATUS_PAUSED);
            Scoreboard sb = new Scoreboard();
            sb.configure(false, false, false, 1);
            sb.update(p);
            HudFrame f = new HudFrame();
            new Hud().scoreboard(f, sb, 0, 0, 300, M);
            assertAll(
                () -> assertEquals(List.of("6,16  AL   SCORE   NAME", "6,38 P      5.0  Bob", "6,54        0.0  Ann"),
                    texts(f), "heading then rows 16 px apart"),
                () -> assertEquals(List.of("6,16-294,16", "6,38-294,38"), lines(f), "heading rule and best underline"),
                () -> assertEquals(HudColor.SCORE_DIM, f.textColor(1), "paused row dimmed"),
                () -> assertEquals(HudColor.SCORE, f.textColor(2), "normal row"),
                () -> assertEquals(HudColor.SCORE, f.textColor(0), "heading shadowed white"),
                () -> assertEquals(HudFrame.SOLID, f.lineStyle(0), "heading rule solid"),
                () -> assertEquals(HudColor.WHITE, f.lineColor(0), "heading rule white"),
                () -> assertEquals(HudFrame.DASHED_X, f.lineStyle(1), "best underline dashed (C LineOnOffDash)"),
                () -> assertEquals(HudColor.SCORE_DIM, f.lineColor(1), "best underline in its (dim) row's colour"),
                () -> assertEquals(0, f.rectCount(), "no window background without a height"));
        }

        @Test
        @DisplayName("score list window: blue background; a normal best row is underlined in white")
        void scoreWindow() {
            Players p = new Players();
            Msg.PlayerJoin j = new Msg.PlayerJoin();
            j.id = 1;
            j.name.append("Ann");
            p.join(j);
            p.setScore(1, 5, -1, 0);
            Scoreboard sb = new Scoreboard();
            sb.configure(false, false, false, 1);
            sb.update(p);
            HudFrame f = new HudFrame();
            new Hud().scoreboard(f, sb, 0, 300, 256, 500, M);
            assertAll(
                () -> assertEquals(List.of("fill 0,300 256x500"), rects(f), "the whole window is filled"),
                () -> assertEquals(HudColor.WINDOW, f.rectColor(0), "in windowColor"),
                () -> assertEquals(HudColor.WHITE, f.lineColor(1), "a white row's underline is white"),
                () -> assertEquals(HudFrame.DASHED_X, f.lineStyle(1), "dashed"));
        }
    }

    @Test
    @DisplayName("HudFrame drops items beyond capacity")
    void capacity() {
        HudFrame f = new HudFrame(1, 1, 1, 4);
        f.line(0, 0, 1, 1, HudFrame.SOLID, 0);
        f.line(0, 0, 1, 1, HudFrame.SOLID, 0);
        f.rect(0, 0, 1, 1, true, 0);
        f.rect(0, 0, 1, 1, true, 0);
        CharBuf a = f.text(0, 0, 0);
        CharBuf b = f.text(0, 0, 0);
        assertAll(
            () -> assertEquals(1, f.lineCount(), "one line"),
            () -> assertEquals(1, f.rectCount(), "one rect"),
            () -> assertNotNull(a, "one text"),
            () -> assertNull(b, "no second text"));
    }
}
