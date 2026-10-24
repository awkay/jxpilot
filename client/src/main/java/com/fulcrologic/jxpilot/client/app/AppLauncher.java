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
import com.fulcrologic.jxpilot.client.game.input.KeyConfig;
import com.fulcrologic.jxpilot.client.sound.SoundPlayer;
import com.fulcrologic.jxpilot.client.gl.MapViewerApp;
import com.fulcrologic.jxpilot.client.net.Endpoint;
import com.fulcrologic.jxpilot.client.xp.ConnectTarget;
import com.fulcrologic.jxpilot.client.xp.GameConnection;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Runs a parsed {@link AppOptions}: the viewer, a game on a server, or a game on an embedded server. */
public final class AppLauncher {
    private AppLauncher() {}

    /** @return the process exit code */
    public static int run(AppOptions o) throws Exception {
        KeyConfig keys = null;
        if (o.mode() == AppOptions.Mode.LIST_KEYS || o.mode() == AppOptions.Mode.CONNECT
            || o.mode() == AppOptions.Mode.LOCAL) {
            try {
                keys = keyConfig(o, Path.of(System.getProperty("user.home", ".")), System.err);
            } catch (IOException e) {
                System.err.println("cannot read the key file: " + e.getMessage());
                return 2;
            }
        }
        switch (o.mode()) {
            case LIST_KEYS -> {
                for (String line : keys.listing()) {
                    System.out.println(line);
                }
                return 0;
            }
            case HELP -> {
                System.err.println(AppOptions.USAGE);
                return 2;
            }
            case VIEW_MAP -> {
                new MapViewerApp(o.viewer()).run();
                return 0;
            }
            case CONNECT -> {
                return play(o, o.target(), keys);
            }
            case LOCAL -> {
                EmbeddedServer server;
                try {
                    server = EmbeddedServer.start(Path.of(o.localMap()));
                } catch (UnsupportedOperationException e) {
                    System.err.println(e.getMessage());
                    return 3;
                } catch (IOException | IllegalArgumentException e) {
                    System.err.println("cannot start the local server: " + e.getMessage());
                    return 3;
                }
                try (server) {
                    Endpoint ep = server.endpoint();
                    return play(o, new ConnectTarget(ConnectTarget.Protocol.JXPILOT, ep), keys);
                }
            }
            default -> throw new IllegalStateException("mode " + o.mode());
        }
    }

    private static int play(AppOptions o, ConnectTarget target, KeyConfig keys) throws Exception {
        ClientGame game = new ClientGame();
        String shape = ShipShapeResource.resolve(o.shape(), o.shipShapeFile(), keys.shipShape(), keys.shipShapeFile(),
            ShipShapeResource::readLatin1, System.err);
        GameConnection conn = GameConnector.open(target, o.bind(), game, o.name(), o.team(), shape,
            o.sound() && maxVolume(o, keys) > 0, (int) System.nanoTime());
        new GameApp(o, game, conn, target.toString(), null, keys).run();
        return 0;
    }

    /**
     * C {@code maxVolume} for a launch.
     * <p>
     * BEHAVIOR: {@code --volume} when given; else the key file's maxVolume (C {@code ~/.xpilotrc}) when it sets
     * one; else {@value SoundPlayer#DEFAULT_MAX_VOLUME}. A null key config counts as a file without it.
     */
    public static int maxVolume(AppOptions o, KeyConfig keys) {
        if (o.maxVolume() != AppOptions.VOLUME_UNSET) {
            return o.maxVolume();
        }
        return keys != null && keys.maxVolume() >= 0 ? keys.maxVolume() : SoundPlayer.DEFAULT_MAX_VOLUME;
    }

    /**
     * The key configuration of a launch.
     * <p>
     * BEHAVIOR: {@code --keys FILE} is read (a missing or unreadable file is an IOException); without it
     * {@code HOME/.xpilotrc} is read when it exists, else the defaults are used. Every problem and every
     * conflicting key is printed to {@code err} ("jxpilot keys: ...").
     */
    static KeyConfig keyConfig(AppOptions o, Path home, PrintStream err) throws IOException {
        Path file = o.keysFile() != null ? Path.of(o.keysFile()) : home.resolve(".xpilotrc");
        KeyConfig c;
        if (o.keysFile() != null || Files.isRegularFile(file)) {
            c = KeyConfig.load(Files.readString(file, StandardCharsets.ISO_8859_1), file.toString(), o.swapScaleKey());
        } else {
            c = KeyConfig.defaults(o.swapScaleKey());
        }
        for (String p : c.problems()) {
            err.println("jxpilot keys: " + p);
        }
        for (String k : c.conflicts()) {
            err.println("jxpilot keys: warning: key bound to more than one function: " + k);
        }
        return c;
    }
}
