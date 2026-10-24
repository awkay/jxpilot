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

import com.fulcrologic.jxpilot.client.game.CharBuf;
import com.fulcrologic.jxpilot.client.game.hud.Hud;
import com.fulcrologic.jxpilot.client.game.hud.HudColor;
import com.fulcrologic.jxpilot.client.game.hud.HudFrame;
import com.fulcrologic.jxpilot.client.game.hud.TextMetrics;
import com.fulcrologic.jxpilot.client.game.options.ClientOptions;
import com.fulcrologic.jxpilot.client.net.PacketStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import static com.fulcrologic.jxpilot.client.game.options.ClientOption.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Look frame extras")
class LookFrameTest {
    record Mono() implements TextMetrics {
        public int ascent() { return 10; }

        public int descent() { return 3; }

        public int width(CharSequence s, int len) { return 7 * len; }
    }

    static final Mono M = new Mono();
    static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    /** 1970-01-01 13:59:30 UTC. */
    static final long T1359 = (13 * 3600 + 59 * 60 + 30) * 1000L;

    final ClientOptions o = new ClientOptions();
    final long[] now = {T1359};
    final LookFrame look = new LookFrame(o, () -> now[0], UTC);

    static List<String> texts(HudFrame f) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < f.textCount(); i++) {
            out.add(f.text(i).toString());
        }
        return out;
    }

    static String clock(long localMillis, boolean ampm) {
        return LookFrame.clockText(new CharBuf(16), localMillis, ampm).toString();
    }

    @Nested
    @DisplayName("packet meters")
    class Meters {
        @Test
        @DisplayName("each meter iff its option, with C's titles, rows and scales")
        void meters() {
            PacketStats st = new PacketStats();
            st.reset(10);
            st.frameSize(5000);
            HudFrame off = new HudFrame();
            look.packetMeters(off, new Hud(), 800, st, M);
            o.setFlag(PACKET_SIZE_METER, true);
            o.setFlag(PACKET_LOSS_METER, true);
            o.setFlag(PACKET_DROP_METER, true);
            o.setFlag(PACKET_LAG_METER, true);
            HudFrame on = new HudFrame();
            look.packetMeters(on, new Hud(), 800, st, M);
            o.setFlag(PACKET_SIZE_METER, false);
            o.setFlag(PACKET_DROP_METER, false);
            HudFrame some = new HudFrame();
            look.packetMeters(some, new Hud(), 800, st, M);
            assertAll(
                () -> assertEquals(0, off.textCount() + off.rectCount(), "all off: nothing"),
                () -> assertEquals(List.of("Packet", "Loss", "Drop", "Lag"), texts(on), "all four, in C's order"),
                () -> assertEquals(197, on.rectW(0), "a size above 4096 fills the Packet bar (197 px)"),
                () -> assertEquals(80 + 2, on.rectY(0), "Packet bar at y 80 (+2)"),
                () -> assertEquals(800 - 210 + 2, on.rectX(0), "right aligned at x offset -10"),
                () -> assertEquals(100 + 2, on.rectY(2), "Loss at y 100"),
                () -> assertEquals(120 + 2, on.rectY(4), "Drop at y 120"),
                () -> assertEquals(140 + 2, on.rectY(6), "Lag at y 140"),
                () -> assertEquals(0, on.rectW(2), "no loss: an empty bar"),
                () -> assertEquals(HudColor.RED, on.rectColor(0), "red bars"),
                () -> assertEquals(List.of("Loss", "Lag"), texts(some), "only the meters that are on"));
        }

        @Test
        @DisplayName("loss, drop and lag are scaled by the rate; lag at most the rate")
        void scales() {
            PacketStats st = new PacketStats();
            st.reset(10);
            st.measure(15, PacketStats.DRAW);    // starts window 10
            st.measure(25, PacketStats.DRAW);    // window 20: the first window counts nothing; slot 5 drawn
            st.measure(35, PacketStats.DRAW);    // window 30 counts window 20: 9 of 10 lost
            st.keyboardSent(1, 0);
            st.keyboardAck(1, 50);                // lag 50, capped at 10
            o.setFlag(PACKET_LOSS_METER, true);
            o.setFlag(PACKET_LAG_METER, true);
            HudFrame f = new HudFrame();
            look.packetMeters(f, new Hud(), 800, st, M);
            assertAll(
                () -> assertEquals(197 * 9 / 10, f.rectW(0), "loss 9 of rate 10"),
                () -> assertEquals(197, f.rectW(2), "lag capped at the rate: full"));
        }
    }

    @Nested
    @DisplayName("clock")
    class Clock {
        @Test
        @DisplayName("24-hour text rounds the minute up; AM/PM keeps C's quirks")
        void text() {
            assertAll(
                () -> assertEquals("14:00", clock(T1359, false), "13:59 shows the next minute, carrying the hour"),
                () -> assertEquals("00:00", clock((23 * 60 + 59) * 60_000L, false), "23:59 carries into 00:00"),
                () -> assertEquals("09:06", clock((9 * 60 + 5) * 60_000L, false), "two digits each"),
                () -> assertEquals(" 1:59PM", clock(T1359, true), "AM/PM: unrounded, hour above 12 is PM modulo 12"),
                () -> assertEquals("12:30AM", clock((12 * 60 + 30) * 60_000L, true), "noon is AM (C tests > 12)"),
                () -> assertEquals(" 0:05AM", clock(5 * 60_000L, true), "midnight is 0"),
                () -> assertEquals("23:59", clock(-2 * 60_000L, false), "times before the epoch wrap to the day before"));
        }

        @Test
        @DisplayName("drawn at the score window's top right, refreshed every 32 seconds of frames")
        void drawnAndRefreshed() {
            HudFrame off = new HudFrame();
            look.clock(off, 0, 200, 256, M, 100, 12);
            o.setFlag(CLOCK, true);
            HudFrame f = new HudFrame();
            look.clock(f, 0, 200, 256, M, 100, 12);
            now[0] += 3600_000L;
            HudFrame later = new HudFrame();
            look.clock(later, 0, 200, 256, M, 100 + 12 * 32 - 1, 12);
            HudFrame refreshed = new HudFrame();
            look.clock(refreshed, 0, 200, 256, M, 100 + 12 * 32, 12);
            now[0] += 3600_000L;
            HudFrame back = new HudFrame();
            look.clock(back, 0, 200, 256, M, 50, 12);
            o.setFlag(CLOCK_AMPM, true);
            HudFrame ampm = new HudFrame();
            look.clock(ampm, 0, 200, 256, M, 51, 12);
            assertAll(
                () -> assertEquals(0, off.textCount() + off.rectCount(), "without clock: nothing"),
                () -> assertEquals(List.of("14:00"), texts(f), "the time"),
                () -> assertEquals(256 - (35 + 3), f.textX(0), "text at width - (text width + 3)"),
                () -> assertEquals(200 + 10 + 4, f.textY(0), "baseline y0 + ascent + 4"),
                () -> assertEquals(HudColor.SCORE, f.textColor(0), "shadowed white"),
                () -> assertEquals(256 - (35 + 6), f.rectX(0), "box from width - (text width + 6)"),
                () -> assertEquals(200, f.rectY(0), "at the window top"),
                () -> assertEquals(41, f.rectW(0), "text width + 6 wide"),
                () -> assertEquals(16, f.rectH(0), "ascent + descent + 3 high"),
                () -> assertEquals(HudColor.WINDOW, f.rectColor(0), "in the window colour"),
                () -> assertTrue(f.rectFilled(0), "filled"),
                () -> assertEquals(List.of("14:00"), texts(later), "an hour later but within 32 s of frames: unchanged"),
                () -> assertEquals(List.of("15:00"), texts(refreshed), "after 32 s of frames: refreshed"),
                () -> assertEquals(List.of("16:00"), texts(back), "frames going back refresh"),
                () -> assertEquals(List.of(" 3:59PM"), texts(ampm), "an options change refreshes"));
        }
    }

    @Nested
    @DisplayName("bell")
    class Bell {
        @Test
        @DisplayName("rings when the time drops to a value below 10, and at 0")
        void rings() {
            boolean[] r = {look.bell(-1), look.bell(12), look.bell(10), look.bell(9), look.bell(9), look.bell(-1),
                look.bell(8), look.bell(15), look.bell(9), look.bell(0), look.bell(0), look.bell(3)};
            assertArrayEquals(new boolean[] {false, false, false, true, false, false, true, false, true, true, false,
                false}, r, "-1 never; 12, 10 no; 9 yes (down from 10); 9 again no; -1 keeps 9; 8 yes; 15 no; 9 down from"
                + " 15 yes; 0 yes; 0 again no; 3 up from 0 no");
        }

        @Test
        @DisplayName("a first report of 0 rings")
        void zero() {
            assertTrue(look.bell(0), "0 rings even without a lower previous value");
        }
    }

    @Nested
    @DisplayName("title flip")
    class Title {
        @Test
        @DisplayName("toggles every 500 frames while titleFlip is on; restores the title when it is turned off")
        void flips() {
            int[] r = {look.title(0), look.title(499), look.title(500), look.title(500), look.title(750),
                look.title(1000), look.title(1500)};
            o.setFlag(TITLE_FLIP, false);
            int off1 = look.title(1600);
            int off2 = look.title(2000);
            o.setFlag(TITLE_FLIP, true);
            int on = look.title(2000);
            assertAll(
                () -> assertArrayEquals(new int[] {LookFrame.TITLE_KEEP, LookFrame.TITLE_KEEP, LookFrame.TITLE_COPYRIGHT,
                    LookFrame.TITLE_KEEP, LookFrame.TITLE_KEEP, LookFrame.TITLE_NORMAL, LookFrame.TITLE_COPYRIGHT}, r,
                    "frame 0 and non-multiples keep; 500 copyright (once per frame number); 1000 back; 1500 copyright"),
                () -> assertEquals(LookFrame.TITLE_NORMAL, off1, "turned off while showing the copyright: normal"),
                () -> assertEquals(LookFrame.TITLE_KEEP, off2, "then keep, even on a multiple of 500"),
                () -> assertEquals(LookFrame.TITLE_COPYRIGHT, on, "on again: flips at the next multiple"));
        }
    }
}
