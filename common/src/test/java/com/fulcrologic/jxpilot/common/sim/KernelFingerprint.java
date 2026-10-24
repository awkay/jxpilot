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
package com.fulcrologic.jxpilot.common.sim;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Everything the ship kernel reads from a {@link ShipWorld} and its {@link ShipParams}, as text: dimensions and
 * wrap, every block's type, gravity and wall distance, the cannons, targets and wormholes as the world reports
 * them, and every {@link ShipParams} field by reflection. Two worlds with the same fingerprint run the kernel the
 * same way. Tests only.
 */
public final class KernelFingerprint {
    private KernelFingerprint() {}

    public static String of(ShipWorld w) {
        StringBuilder sb = new StringBuilder(1 << 16);
        sb.append("size ").append(w.width()).append('x').append(w.height()).append(' ').append(w.pixelWidth())
            .append('x').append(w.pixelHeight()).append(' ').append(w.clickWidth()).append('x').append(w.clickHeight())
            .append(" wrap ").append(w.wrap()).append('\n');
        for (int by = 0; by < w.height(); by++) {
            for (int bx = 0; bx < w.width(); bx++) {
                sb.append(w.map().block(bx, by)).append(',').append(Float.floatToRawIntBits(w.gravityX(bx, by)))
                    .append(',').append(Float.floatToRawIntBits(w.gravityY(bx, by))).append(',')
                    .append(w.wallDist(bx, by)).append(';');
            }
            sb.append('\n');
        }
        for (int i = 0; i < w.map().cannons().size(); i++) {
            sb.append("cannon ").append(i).append(' ').append(w.cannonPhased(i)).append('\n');
        }
        for (int i = 0; i < w.map().targets().size(); i++) {
            sb.append("target ").append(i).append(' ').append(w.targetTeam(i)).append('\n');
        }
        for (int i = 0; i < w.wormholeCount(); i++) {
            sb.append("worm ").append(i).append(' ').append(w.wormholeType(i)).append(' ').append(w.wormholeBx(i))
                .append(',').append(w.wormholeBy(i)).append('\n');
        }
        sb.append(params(w.shipParams()));
        return sb.toString();
    }

    /** Every instance field ShipParams declares, by name. */
    public static String params(ShipParams p) {
        StringBuilder sb = new StringBuilder();
        try {
            for (Field f : ShipParams.class.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                f.setAccessible(true);
                Object v = f.get(p);
                if (v instanceof Float x) {
                    v = Float.floatToRawIntBits(x);
                } else if (v instanceof Double x) {
                    v = Double.doubleToRawLongBits(x);
                }
                sb.append(f.getName()).append('=').append(v).append('\n');
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        return sb.toString();
    }
}
