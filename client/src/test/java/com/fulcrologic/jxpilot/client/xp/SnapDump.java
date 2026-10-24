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
package com.fulcrologic.jxpilot.client.xp;

import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.Section;
import com.fulcrologic.jxpilot.common.net.SnapshotReader;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Decodes a JXPilot SNAPSHOT body into readable lines with the native reader (positions rounded). */
final class SnapDump {
    private SnapDump() {}

    static String f(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    static String a(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    /** Header line then one line per record, prefixed with the section name. */
    static List<String> dump(byte[] body, double w, double h, boolean wrap) {
        SnapshotReader r = new SnapshotReader();
        r.setWorld(w, h, wrap);
        List<String> out = new ArrayList<>();
        if (!r.begin(ByteBuffer.wrap(body))) {
            out.add("BAD HEADER");
            return out;
        }
        out.add("tick=" + r.tick() + " part=" + r.part() + "/" + r.partCount() + " flags=" + r.flags() + " view="
            + f(r.viewX()) + "," + f(r.viewY()));
        while (r.nextSection()) {
            int n = r.count();
            for (int i = 0; i < n; i++) {
                out.add(record(r, i));
                if (r.type() == Section.SELF) {
                    Records.Self x = new Records.Self().wrap(r, i);
                    out.add("SELFCTL power=" + x.power() + " turn=" + x.turnSpeed() + " res=" + x.turnResistance255()
                        + " lockDist=" + x.lockDist() + " lockDir=" + x.lockDir() + " check=" + x.check()
                        + " mods=" + x.mods());
                } else if (r.type() == Section.TIMERS) {
                    Records.Timers x = new Records.Timers().wrap(r, i);
                    out.add("METERS destruct=" + x.destruct() + " thrust=" + x.thrustLeft() + "/" + x.thrustMax()
                        + " shield=" + x.shieldLeft() + "/" + x.shieldMax() + " phasing=" + x.phasingLeft() + "/"
                        + x.phasingMax() + " shutdown=" + x.shutdownCount() + "/" + x.shutdownDelay());
                }
            }
        }
        if (r.malformed()) {
            out.add("MALFORMED");
        }
        return out;
    }

    /** Only the lines of one section prefix (e.g. "SHIP"). */
    static List<String> only(List<String> lines, String prefix) {
        return lines.stream().filter(s -> s.startsWith(prefix + " ")).toList();
    }

    private static String record(SnapshotReader r, int i) {
        return switch (r.type()) {
            case Section.SELF -> {
                Records.Self s = new Records.Self().wrap(r, i);
                yield "SELF id=" + s.id() + " flags=" + s.flags() + " pos=" + f(s.x()) + "," + f(s.y()) + " vel="
                    + f(s.vx()) + "," + f(s.vy()) + " h=" + a(s.heading()) + " fuel=" + f(s.fuel()) + "/"
                    + f(s.fuelMax()) + " lock=" + s.lockId() + " tanks=" + s.numTanks() + " cur=" + s.currentTank();
            }
            case Section.SHIP -> {
                Records.Ship s = new Records.Ship().wrap(r, i);
                yield "SHIP id=" + s.id() + " flags=" + s.flags() + " pos=" + f(s.x()) + "," + f(s.y()) + " h="
                    + a(s.heading()) + " vel=" + f(s.vx()) + "," + f(s.vy());
            }
            case Section.SHOT -> {
                Records.Shot s = new Records.Shot().wrap(r, i);
                yield "SHOT kind=" + Records.ShotKind.kindOf(r.variant()) + " color="
                    + Records.ShotKind.colorOf(r.variant()) + " pos=" + f(s.x()) + "," + f(s.y()) + " vel=" + s.vx()
                    + "," + s.vy() + (Records.ShotKind.harmless(r.variant()) ? " harmless" : "");
            }
            case Section.BURST -> {
                Records.Burst b = new Records.Burst().wrap(r, i);
                com.fulcrologic.jxpilot.common.math.DebrisBurst d = new com.fulcrologic.jxpilot.common.math.DebrisBurst();
                b.read(d);
                yield "BURST kind=" + b.kind() + " color=" + b.color() + " pos=" + f(d.x) + "," + f(d.y) + " count="
                    + d.count + " speed=" + f(d.minSpeed) + "-" + f(d.maxSpeed) + " life=" + d.minLife + "-"
                    + d.maxLife + " vel=" + f(d.vx) + "," + f(d.vy) + " age=" + b.age();
            }
            case Section.CANNON -> {
                Records.CannonState c = new Records.CannonState().wrap(r, i);
                yield "CANNON " + c.index() + " flags=" + c.flags();
            }
            case Section.FUEL -> {
                Records.FuelState c = new Records.FuelState().wrap(r, i);
                yield "FUEL " + c.index() + " frac=" + a(c.fraction());
            }
            case Section.TARGET -> {
                Records.TargetState c = new Records.TargetState().wrap(r, i);
                yield "TARGET " + c.index() + " flags=" + c.flags() + " left=" + a(c.damageLeft());
            }
            case Section.RADAR -> {
                Records.Radar c = new Records.Radar().wrap(r, i);
                yield "RADAR cell=" + c.cellX() + "," + c.cellY() + " flags=" + c.flags()
                    + (r.variant() != 0 ? " size=" + r.variant() : "");
            }
            case Section.SELF_ITEMS -> {
                Records.SelfItems c = new Records.SelfItems().wrap(r, i);
                StringBuilder sb = new StringBuilder("SELF_ITEMS");
                for (int k = 0; k < XpProto.NUM_ITEMS; k++) {
                    sb.append(' ').append(c.count(k));
                }
                if (c.loseItem() != 0) {
                    sb.append(" lose=").append(c.loseItem());
                }
                yield sb.toString();
            }
            case Section.ITEM -> {
                Records.ItemObj c = new Records.ItemObj().wrap(r, i);
                yield "ITEM pos=" + f(c.x()) + "," + f(c.y()) + " type=" + c.itemType();
            }
            case Section.MINE -> {
                Records.Mine c = new Records.Mine().wrap(r, i);
                yield "MINE pos=" + f(c.x()) + "," + f(c.y()) + " flags=" + c.flags() + " owner=" + c.ownerId();
            }
            case Section.MISSILE -> {
                Records.Missile c = new Records.Missile().wrap(r, i);
                yield "MISSILE id=" + c.id() + " pos=" + f(c.x()) + "," + f(c.y()) + " h=" + a(c.heading()) + " kind="
                    + c.kind() + " len=" + c.length();
            }
            case Section.BALL -> {
                Records.Ball c = new Records.Ball().wrap(r, i);
                yield "BALL id=" + c.id() + " pos=" + f(c.x()) + "," + f(c.y()) + " team=" + c.team() + " holder="
                    + c.connectedPlayer();
            }
            case Section.LASER -> {
                Records.Laser c = new Records.Laser().wrap(r, i);
                yield "LASER pos=" + f(c.x()) + "," + f(c.y()) + " h=" + a(c.heading()) + " len=" + c.length()
                    + " color=" + c.color();
            }
            case Section.ASTEROID -> {
                Records.Asteroid c = new Records.Asteroid().wrap(r, i);
                yield "ASTEROID pos=" + f(c.x()) + "," + f(c.y()) + " size=" + c.size() + " rot=" + a(c.rotation());
            }
            case Section.WRECKAGE -> {
                Records.Wreckage c = new Records.Wreckage().wrap(r, i);
                yield "WRECKAGE pos=" + f(c.x()) + "," + f(c.y()) + " shape=" + c.shape() + " size=" + c.size()
                    + " rot=" + a(c.rotation());
            }
            case Section.ECM -> {
                Records.Ecm c = new Records.Ecm().wrap(r, i);
                yield "ECM pos=" + f(c.x()) + "," + f(c.y()) + " radius=" + c.radius();
            }
            case Section.CONNECTOR -> {
                Records.Connector c = new Records.Connector().wrap(r, i);
                yield "CONNECTOR " + f(c.x0()) + "," + f(c.y0()) + " " + f(c.x1()) + "," + f(c.y1()) + " kind="
                    + c.kind();
            }
            case Section.TRANSPORTER -> {
                Records.Transporter c = new Records.Transporter().wrap(r, i);
                yield "TRANSPORTER " + f(c.x0()) + "," + f(c.y0()) + " " + f(c.x1()) + "," + f(c.y1());
            }
            case Section.TEMP_WORMHOLE -> {
                Records.TempWormhole c = new Records.TempWormhole().wrap(r, i);
                yield "TEMP_WORMHOLE " + c.bx() + "," + c.by() + " type=" + c.type();
            }
            case Section.TIMERS -> {
                Records.Timers c = new Records.Timers().wrap(r, i);
                yield "TIMERS damaged=" + c.damaged() + " roundDelay=" + c.roundDelay() + " timeLeft=" + c.timeLeft();
            }
            case Section.SOUND -> {
                Records.Sound c = new Records.Sound().wrap(r, i);
                yield "SOUND event=" + c.event() + " volume=" + c.volume();
            }
            default -> "UNKNOWN " + r.type();
        };
    }
}
