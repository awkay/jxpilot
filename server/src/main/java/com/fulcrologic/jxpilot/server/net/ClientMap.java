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
package com.fulcrologic.jxpilot.server.net;

import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.Options;

/**
 * The map as clients may see it: C {@code Packet_map} (netserver.c:225-262) turns blocks the options hide
 * into space before sending the map.
 */
public final class ClientMap {
    private ClientMap() {}

    /**
     * BEHAVIOR: with gravityVisible off the gravity blocks ({@code + - > < i m k j}) become space; with
     * wormholeVisible off the wormholes ({@code @ ( )}); with itemConcentratorVisible off the item concentrators
     * ({@code %}); with asteroidConcentratorVisible off the asteroid concentrators ({@code &}).
     * BEHAVIOR: friction blocks ({@code z}) are sent as filled decor ({@code b}) with blockFrictionVisible, else
     * as space. Every other character (newlines included) is kept; null stays null.
     */
    public static String of(String mapData, Options o) {
        if (mapData == null) {
            return null;
        }
        boolean gravity = o.getBool(Opt.GRAVITY_VISIBLE);
        boolean wormhole = o.getBool(Opt.WORMHOLE_VISIBLE);
        boolean items = o.getBool(Opt.ITEM_CONCENTRATOR_VISIBLE);
        boolean asteroids = o.getBool(Opt.ASTEROID_CONCENTRATOR_VISIBLE);
        boolean friction = o.getBool(Opt.BLOCK_FRICTION_VISIBLE);
        char[] c = mapData.toCharArray();
        for (int i = 0; i < c.length; i++) {
            c[i] = switch (c[i]) {
                case '+', '-', '>', '<', 'i', 'm', 'k', 'j' -> gravity ? c[i] : ' ';
                case '@', '(', ')' -> wormhole ? c[i] : ' ';
                case '%' -> items ? c[i] : ' ';
                case '&' -> asteroids ? c[i] : ' ';
                case 'z' -> friction ? 'b' : ' ';
                default -> c[i];
            };
        }
        return new String(c);
    }
}
