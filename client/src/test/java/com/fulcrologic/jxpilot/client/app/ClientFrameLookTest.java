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

import com.fulcrologic.jxpilot.client.game.hud.HudFrame;
import com.fulcrologic.jxpilot.client.game.input.InputState;
import com.fulcrologic.jxpilot.client.game.options.ClientOption;
import com.fulcrologic.jxpilot.client.game.options.ClientOptions;
import com.fulcrologic.jxpilot.client.net.ConnectionState;
import com.fulcrologic.jxpilot.client.paint.SceneData;
import com.fulcrologic.jxpilot.client.render.Palette;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.fulcrologic.jxpilot.client.app.AppFixture.T0;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ClientFrame: look options and extras")
class ClientFrameLookTest {
    final FakeConnection conn = new FakeConnection();

    ClientFrame frame(AppFixture f) {
        return new ClientFrame(f.game, conn, new InputState(KeyRouter.bindings(null)), new SceneData(8, 16, 64, 8),
            new RadarBlips(8));
    }

    static String find(HudFrame h, String regex) {
        for (int i = 0; i < h.textCount(); i++) {
            if (h.text(i).toString().matches(regex)) {
                return h.text(i).toString();
            }
        }
        return null;
    }

    @Test
    @DisplayName("each step applies the look options and draws the packet meters and the clock")
    void stepAppliesLookOptions() {
        AppFixture f = new AppFixture(true).players();
        ClientFrame cf = frame(f);
        ClientOptions o = cf.options();
        f.game.onStateChange(ConnectionState.PLAYING, "Playing");
        cf.step(T0, 800, 600, 1, ClientFrameTest.M, 256, 100);
        boolean meterBefore = find(cf.viewHud(), "Lag") != null;
        o.setFlag(ClientOption.PACKET_LAG_METER, true);
        o.setFlag(ClientOption.CLOCK, true);
        o.setInteger(ClientOption.HUD_COLOR, 3);
        o.setText(ClientOption.color(2), "#010203");
        cf.step(T0 + 1_000_000, 800, 600, 1, ClientFrameTest.M, 256, 100);
        f.game.onStateChange(ConnectionState.CLOSED, "Closed");
        cf.step(T0 + 2_000_000, 800, 600, 1, ClientFrameTest.M, 256, 100);
        boolean closedMeter = find(cf.viewHud(), "Lag") != null;
        f.game.onStateChange(ConnectionState.PLAYING, "Playing");
        cf.step(T0 + 3_000_000, 800, 600, 1, ClientFrameTest.M, 256, 100);
        assertAll(
            () -> assertFalse(meterBefore, "meters off by default"),
            () -> assertFalse(closedMeter, "no packet meters while not playing"),
            () -> assertNotNull(find(cf.viewHud(), "Lag"), "the lag meter"),
            () -> assertNotNull(find(cf.panelHud(), "\\d\\d:\\d\\d"), "the clock in the score window"),
            () -> assertEquals(3, cf.style().hudColor, "hudColor reaches the painters' style"),
            () -> assertEquals(Palette.pack(1, 2, 3, 255), cf.palette().color(2), "color2 reaches the palette"),
            () -> assertFalse(cf.bell(), "no time left reported: no bell"),
            () -> assertEquals(LookFrame.TITLE_KEEP, cf.titleChange(), "no title change at frame 0"),
            () -> assertNotNull(cf.look(), "the look extras"));
    }
}
