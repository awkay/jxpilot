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
package com.fulcrologic.jxpilot.server.host;

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.math.ShipShape;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.ReliableType;
import com.fulcrologic.jxpilot.server.sim.Simulation;
import com.fulcrologic.jxpilot.server.world.Click;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.ShipGeom;
import com.fulcrologic.jxpilot.server.world.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.server.host.HostFx.THREE;
import static com.fulcrologic.jxpilot.server.host.HostFx.chat;
import static com.fulcrologic.jxpilot.server.host.HostFx.empty;
import static com.fulcrologic.jxpilot.server.host.HostFx.enter;
import static com.fulcrologic.jxpilot.server.host.HostFx.sim;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The host side of alliances (alliance marks), custom ship shapes, and options an operator changes. */
@DisplayName("SimGameHost: alliance marks, ship shapes, run-time options")
class HostCommandsTest {
    /** A shape with points 15 px to each side (the default ship's reach 8). */
    static final String WIDE = "(SH: 15,0 -9,15 -9,-15)(EN: -9,0)(MG: 15,0)";

    static List<String> only(List<String> l, String prefix) {
        return HostFx.only(l, prefix);
    }

    @Nested
    @DisplayName("alliance marks (C Send_score's alliance character)")
    class Marks {
        @Test
        @DisplayName("allianceMark: blank outside alliances; '+' for the viewer's own alliance; digits when announced")
        void mark() {
            Player v = new Player(0);
            v.alliance = 2;
            assertAll(
                () -> assertEquals(' ', SimGameHost.allianceMark(v, Player.ALLIANCE_NOT_SET, false), "not in one"),
                () -> assertEquals('+', SimGameHost.allianceMark(v, 2, false), "the viewer's alliance"),
                () -> assertEquals(' ', SimGameHost.allianceMark(v, 3, false), "another alliance is not shown"),
                () -> assertEquals(' ', SimGameHost.allianceMark(null, 3, false), "no viewer"),
                () -> assertEquals('3', SimGameHost.allianceMark(v, 3, true), "announced: the number"),
                () -> assertEquals(' ', SimGameHost.allianceMark(v, Player.ALLIANCE_NOT_SET, true), "announced, not in one"));
        }

        @Test
        @DisplayName("each viewer gets its own marks; a newcomer gets the marks of allied players only")
        void delivery() {
            Simulation s = sim("", THREE);
            SimGameHost h = new SimGameHost(s);
            int ann = enter(h, "Ann", 0);
            int bob = enter(h, "Bob", 0);
            int cid = enter(h, "Cid", 0);
            HostFx.Wire a = new HostFx.Wire();
            HostFx.Wire c = new HostFx.Wire();
            h.initialState(ann, a.out);
            h.initialState(cid, c.out);
            a.take();
            c.take();
            h.clientMessage(ann, ReliableType.CHAT, chat("/ally invite Bob"));
            h.clientMessage(bob, ReliableType.CHAT, chat("/ally accept Ann"));
            h.drainEvents(ann, a.out);
            h.drainEvents(cid, c.out);
            h.leave(cid);
            int dan = enter(h, "Dan", 0);
            HostFx.Wire d = new HostFx.Wire();
            h.initialState(dan, d.out);
            assertAll(
                () -> assertEquals(List.of("ALLIANCE " + bob + " '+'", "ALLIANCE " + ann + " '+'"), only(a.take(), "ALLIANCE"),
                    "Ann sees both as her allies"),
                () -> assertEquals(List.of("ALLIANCE " + bob + " ' '", "ALLIANCE " + ann + " ' '"), only(c.take(), "ALLIANCE"),
                    "Cid sees no mark (alliances not announced)"),
                () -> assertEquals(List.of("ALLIANCE " + ann + " ' '", "ALLIANCE " + bob + " ' '"), only(d.take(), "ALLIANCE"),
                    "the newcomer gets the allied players' marks, as it sees them"));
        }

        @Test
        @DisplayName("announced alliances show their number to everyone")
        void announced() {
            Simulation s = sim("announceAlliances: yes\n", THREE);
            SimGameHost h = new SimGameHost(s);
            int ann = enter(h, "Ann", 0);
            int bob = enter(h, "Bob", 0);
            int cid = enter(h, "Cid", 0);
            HostFx.Wire c = new HostFx.Wire();
            h.initialState(cid, c.out);
            c.take();
            h.clientMessage(ann, ReliableType.CHAT, chat("/ally invite Bob"));
            h.clientMessage(bob, ReliableType.CHAT, chat("/ally accept Ann"));
            h.drainEvents(cid, c.out);
            assertEquals(List.of("ALLIANCE " + bob + " '0'", "ALLIANCE " + ann + " '0'"), only(c.take(), "ALLIANCE"),
                "alliance 0");
        }
    }

    @Nested
    @DisplayName("ship shapes")
    class Shapes {
        @Test
        @DisplayName("the CONNECT shape (limits apply) is worn from the spawn and broadcast in canonical form")
        void shapes() {
            Simulation s = sim("", THREE);
            SimGameHost h = new SimGameHost(s);
            int ann = h.join("Ann", 0, WIDE);
            boolean before = s.world.byId(ann).ship == s.ships.defaultShipShape || !s.world.byId(ann).spawned;
            h.clientMessage(ann, ReliableType.READY, empty());
            int bob = enter(h, "Bob", 0);
            int cid = h.join("Cid", 0, "(SH: 1,0 0,1 -1,0)");
            h.clientMessage(cid, ReliableType.READY, empty());
            HostFx.Wire w = new HostFx.Wire();
            h.initialState(bob, w.out);
            List<String> joins = only(w.take(), "JOIN");
            Player a = s.world.byId(ann);
            assertAll(
                () -> assertTrue(before, "a reserved player does not wear it yet"),
                () -> assertEquals(ShipShape.parse(WIDE).toWireString(), a.ship.wire, "Ann wears her shape"),
                () -> assertEquals(15, a.ship.shape.ptY[1], "with its points"),
                () -> assertTrue(joins.get(0).endsWith("shape=" + ShipShape.parse(WIDE).toWireString()),
                    "PLAYER_JOIN carries the canonical string: " + joins.get(0)),
                () -> assertTrue(joins.get(1).endsWith("shape="), "the default ship is sent empty"),
                () -> assertSame(ShipGeom.defaultShip(), s.world.byId(cid).ship, "a shape too small for the limits: default"),
                () -> assertTrue(joins.get(2).endsWith("shape="), "and sent empty"));
        }

        @Test
        @DisplayName("without allowShipShapes everyone wears defaultShipShape")
        void notAllowed() {
            Simulation s = sim("allowShipShapes: no\n", THREE);
            SimGameHost h = new SimGameHost(s);
            int ann = h.join("Ann", 0, WIDE);
            h.clientMessage(ann, ReliableType.READY, empty());
            Player a = s.world.byId(ann);
            assertAll(
                () -> assertSame(s.ships.defaultShipShape, a.ship, "the option's ship"),
                () -> assertEquals(ShipShape.parse(Opt.DEFAULT_SHIP_SHAPE.defaultValue()).toWireString(), a.ship.wire,
                    "sent in canonical form"),
                () -> assertNotEquals("", a.ship.wire, "it is not the built-in ship (it has wide-angle guns)"));
        }

        @Test
        @DisplayName("tankShipShape (C ship.c:416-418): its own ship with allowShipShapes, else defaultShipShape")
        void tank() {
            Simulation on = sim("", THREE);
            Simulation off = sim("allowShipShapes: no\n", THREE);
            assertAll(
                () -> assertEquals(ShipShape.parse(Opt.TANK_SHIP_SHAPE.defaultValue()).toWireString(),
                    on.ships.tankShipShape.wire, "the fuel tank shape"),
                () -> assertSame(off.ships.defaultShipShape, off.ships.tankShipShape, "without allowShipShapes"));
        }

        @Test
        @DisplayName("collisions use the shape: a wide ship touches a wall the default ship does not reach")
        void collision() {
            String[] map = {"xxxxxxxxx", "         ", "         ", "         ", "_       _"};
            Simulation s = sim("", map);
            SimGameHost h = new SimGameHost(s);
            int ann = h.join("Ann", 0, WIDE);
            h.clientMessage(ann, ReliableType.READY, empty());
            int bob = enter(h, "Bob", 0);
            World w = s.world;
            int wallFace = 4 * Const.BLOCK_SZ;
            Player wide = w.byId(ann);
            Player plain = w.byId(bob);
            for (Player p : new Player[] {wide, plain}) {
                p.vx = 0;
                p.vy = 4;
                p.dir = 0;
                p.floatDir = 0;
                p.turnVel = 0;
                p.used = 0;
                p.status &= ~com.fulcrologic.jxpilot.server.world.Obj.GRAVITY;
            }
            wide.setClicks((int) (1.5 * Const.BLOCK_SZ) * Click.CLICK, (wallFace - 18) * Click.CLICK);
            plain.setClicks((int) (7.5 * Const.BLOCK_SZ) * Click.CLICK, (wallFace - 18) * Click.CLICK);
            wide.remember();
            plain.remember();
            w.objects.clear();
            long from = w.events.seq();
            s.tick(new long[w.players.length]);
            List<String> ev = HostFx.events(w, from);
            assertAll(
                () -> assertEquals(4f, plain.vy, 1e-6, "the default ship (reach 8) flies on: status " + plain.status
                    + " count " + plain.count + " delay " + w.roundDelay + " y " + plain.py + " " + ev),
                () -> assertTrue(wide.vy < 4f, "the wide ship (reach 15) hit the wall: " + wide.vy));
        }
    }

    @Nested
    @DisplayName("options changed by an operator")
    class Options {
        @Test
        @DisplayName("playersOnRadar and idleRun take effect at once in the host")
        void hostOptions() {
            Simulation s = sim("", THREE);
            SimGameHost h = new SimGameHost(s);
            int ann = enter(h, "Ann", 0);
            s.world.options.force(Opt.PASSWORD, "pw");
            h.clientMessage(ann, ReliableType.CHAT, chat("/password pw"));
            h.clientMessage(ann, ReliableType.CHAT, chat("/set idleRun yes"));
            h.leave(ann);
            h.tick(1);
            assertTrue(s.robots.enabled(), "with idleRun the game runs without humans");
        }
    }
}
