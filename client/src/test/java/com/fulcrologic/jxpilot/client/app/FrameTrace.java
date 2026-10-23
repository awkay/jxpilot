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

import com.fulcrologic.jxpilot.client.game.ClientGame;
import com.fulcrologic.jxpilot.client.game.ClientWorld;
import com.fulcrologic.jxpilot.client.net.ConnectionListener;
import com.fulcrologic.jxpilot.client.net.ConnectionState;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.net.Msg;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Dev instrumentation (allocates freely): a {@link ConnectionListener} tap that logs snapshot arrivals
 * (local time, tick) and passes everything on to the game, plus a per-frame log of time, render/server
 * ticks, the own ship, the camera and the first shot. Written as two CSV files.
 */
final class FrameTrace implements ConnectionListener {
    private final ClientGame game;
    private final List<String> frames = new ArrayList<>();
    private final List<String> snaps = new ArrayList<>();

    FrameTrace(ClientGame game) {
        this.game = game;
        frames.add("frame,t_ms,render_tick,server_tick,newest_tick,self,self_x,self_y,cam_x,cam_y,shots,shot0_x,shot0_y,"
            + "ships,ship0_id,ship0_x,ship0_y,particles");
        snaps.add("t_ms,tick");
    }

    void frame(int n, ClientFrame f, long now) {
        ClientWorld w = game.world();
        frames.add(n + "," + now / 1e6 + "," + w.renderTick() + "," + w.serverTickNow() + "," + w.newestTick() + ","
            + w.hasSelf() + "," + w.selfX() + "," + w.selfY() + "," + f.centreX() + "," + f.centreY() + ","
            + w.shotCount() + "," + (w.shotCount() > 0 ? w.shotX(0) : 0) + "," + (w.shotCount() > 0 ? w.shotY(0) : 0)
            + "," + w.shipCount() + "," + (w.shipCount() > 0 ? w.shipId(0) : -1) + ","
            + (w.shipCount() > 0 ? w.shipX(0) : 0) + "," + (w.shipCount() > 0 ? w.shipY(0) : 0) + ","
            + f.scene().particleCount);
    }

    void write(Path file) throws IOException {
        Files.write(file, frames);
        Files.write(Path.of(file + ".snaps.csv"), snaps);
    }

    @Override public void onAccepted(int playerId) { game.onAccepted(playerId); }

    @Override public void onWelcome(Msg.Welcome welcome) { game.onWelcome(welcome); }

    @Override public void onConfig(Msg.Welcome w, Options o, WorldMap m) { game.onConfig(w, o, m); }

    @Override public void onReliable(int type, ByteBuffer payload) { game.onReliable(type, payload); }

    @Override
    public void onSnapshot(ByteBuffer body, long nowNanos) {
        snaps.add(nowNanos / 1e6 + "," + body.getInt(body.position()));
        game.onSnapshot(body, nowNanos);
    }

    @Override public void onStateChange(ConnectionState s, CharSequence st) { game.onStateChange(s, st); }
}
