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

import com.fulcrologic.jxpilot.client.net.NetDiagnostics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("NetStatsLine")
class NetStatsLineTest {
    /** 6 px per char, ascent 11. */
    static final TextMetrics MONO = new TextMetrics() {
        @Override public int ascent() { return 11; }
        @Override public int descent() { return 3; }
        @Override public int width(CharSequence s, int len) { return 6 * len; }
    };

    static NetDiagnostics measured() {
        NetDiagnostics d = new NetDiagnostics();
        d.reset(10, 1);
        d.rttSample(203_400_000L);
        d.snapshot(1, 0);
        d.snapshot(2, 104_250_000L); // 4.25 ms late
        return d;
    }

    @Test
    @DisplayName("format: measured values with units, unknown ones as '-'; phase 1 figures when set")
    void format() {
        NetStatsLine l = new NetStatsLine();
        String measured = l.format(measured(), 91.4).toString();
        String none = l.format(null, Double.NaN).toString();
        NetDiagnostics p = measured();
        p.leadTicks = 3.25;
        p.inputSlackTicks = 0.5;
        p.correctionsPerSecond = 1.25;
        p.lastCorrectionPx = 3.4;
        String predicted = l.format(p, 91.4).toString();
        assertAll(
            () -> assertEquals("rtt 203 ms  interp 91 ms  jitter 4.2 ms  loss -  late -  lead -  slack -  corr -", measured,
                "RTT and interpolation in whole ms, jitter one decimal; loss unknown before a full window"),
            () -> assertEquals("rtt -  interp -  jitter -  loss -  late -  lead -  slack -  corr -", none,
                "no diagnostics: everything unknown"),
            () -> assertEquals("rtt 203 ms  interp 91 ms  jitter 4.2 ms  loss -  late -  lead 3.2  slack 0.50  corr 1.2/s 3 px",
                predicted, "lead one decimal, slack two, corrections per second and the last one in px"));
    }

    @Test
    @DisplayName("position: right-aligned 10 px from the right edge, never left of 10 px; baseline 10 + ascent")
    void position() {
        assertAll(
            () -> assertEquals(1024 - 10 - 300, NetStatsLine.x(1024, 300), "right-aligned"),
            () -> assertEquals(10, NetStatsLine.x(200, 300), "a narrow view clamps at the left margin"),
            () -> assertEquals(10, NetStatsLine.x(320, 300), "exactly fitting"),
            () -> assertEquals(21, NetStatsLine.baseline(MONO), "10 px margin plus the ascent"));
    }

    @Test
    @DisplayName("layout: hidden adds nothing; shown adds one GAME-font HUD-colour run; toggle flips it")
    void layout() {
        NetStatsLine l = new NetStatsLine();
        HudFrame f = new HudFrame();
        boolean startsHidden = !l.visible;
        l.layout(f, 1024, measured(), 91.4, MONO);
        int hidden = f.textCount();
        l.toggle();
        l.layout(f, 1024, measured(), 91.4, MONO);
        String text = f.text(0).toString();
        l.toggle();
        assertAll(
            () -> assertTrue(startsHidden, "off by default"),
            () -> assertEquals(0, hidden, "hidden: no text"),
            () -> assertEquals(1, f.textCount(), "shown: one run"),
            () -> assertEquals(l.text().toString(), text, "the formatted line"),
            () -> assertEquals(1024 - 10 - 6 * text.length(), f.textX(0), "right-aligned"),
            () -> assertEquals(21, f.textY(0), "at the baseline"),
            () -> assertEquals(HudColor.HUD, f.textColor(0), "HUD colour"),
            () -> assertEquals(FontRole.GAME, f.textRole(0), "game font"),
            () -> assertFalse(l.visible, "toggled off again"));
    }
}
