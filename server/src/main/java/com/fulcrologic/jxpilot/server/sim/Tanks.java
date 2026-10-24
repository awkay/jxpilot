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
package com.fulcrologic.jxpilot.server.sim;

import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.SoundEvent;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * Fuel tanks: ports of {@code Player_add_tank}, {@code Player_remove_tank}, {@code Player_init_fuel}
 * ({@code src/server/player.c}), {@code Update_tanks} ({@code src/server/ship.c}), the KEY_TANK_NEXT/PREV keys
 * (event.c) and the tank macros of {@code src/server/serverconst.h}. Fuel is C fixed point (256 per unit).
 */
public final class Tanks {
    /** C {@code MAX_PLAYER_FUEL}. */
    static final double MAX_PLAYER_FUEL = 2600 * 256;
    /** C {@code TANK_REFILL_LIMIT} = MIN_PLAYER_FUEL / 8. */
    static final long TANK_REFILL_LIMIT = (350 * 256) / 8;
    /** C {@code REFUEL_RATE}. */
    static final long REFUEL_RATE = 5 * 256;

    private final World w;

    public Tanks(World w) {
        this.w = w;
    }

    /** C {@code TANK_CAP(n)}: the main tank holds MAX_PLAYER_FUEL, extra tanks a third of it. */
    static double cap(int n) {
        return n == 0 ? MAX_PLAYER_FUEL : (long) (MAX_PLAYER_FUEL / 3);
    }

    /** C {@code TANK_FUEL(n)}: (cap * (5 + (randomMT() &amp; 3))) / 32, a C long. */
    long tankFuel(int n) {
        return ((long) cap(n) * (5 + (w.rng.nextInt() & 3))) / 32;
    }

    /** C {@code TANK_MASS}. */
    float tankMass() {
        return w.params.shipMass / 10;
    }

    /**
     * C {@code Player_add_tank}.
     * <p>
     * BEHAVIOR: below MAX_TANKS a tank with {@code fuel} (limited to its capacity) is added to the sum and the
     * maximum; the empty mass grows by TANK_MASS; the TANK item count is the tank count. At the limit nothing
     * happens.
     */
    public void add(Player pl, double fuel) {
        if (pl.numTanks >= Player.MAX_TANKS) {
            return;
        }
        pl.numTanks++;
        double c = cap(pl.numTanks);
        double f = fuel < 0 ? 0 : (fuel > c ? c : fuel);
        pl.fuel += f;
        pl.fuelMax += c;
        pl.tankFuel[pl.numTanks] = f;
        pl.emptyMass += tankMass();
        pl.items[Item.TANK.ordinal()] = pl.numTanks;
    }

    /**
     * C {@code Player_remove_tank}.
     * <p>
     * BEHAVIOR: without tanks nothing happens; otherwise tank {@code which} (limited to 1..numTanks) and its fuel
     * go; later tanks move down; a current tank past the end becomes the main tank.
     */
    public void remove(Player pl, int which) {
        if (pl.numTanks <= 0) {
            return;
        }
        int ti = which < 1 ? 1 : (which > pl.numTanks ? pl.numTanks : which);
        pl.emptyMass -= tankMass();
        double f = pl.tankFuel[ti];
        pl.fuelMax -= cap(ti);
        pl.fuel -= f;
        pl.numTanks--;
        if (pl.currentTank > pl.numTanks) {
            pl.currentTank = 0;
        } else {
            for (int i = ti; i <= pl.numTanks; i++) {
                pl.tankFuel[i] = pl.tankFuel[i + 1];
            }
        }
        pl.items[Item.TANK.ordinal()] = pl.numTanks;
    }

    /**
     * C {@code Player_init_fuel}: the main tank is filled first, then initialTanks extra tanks get the rest.
     * <p>
     * BEHAVIOR: no tanks, current = 0, max = TANK_CAP(0), sum = tank[0] = min(fuel, max), empty mass = shipMass;
     * then {@link #add} for each initial tank with what is left.
     */
    public void initFuel(Player pl, double total) {
        pl.numTanks = 0;
        pl.currentTank = 0;
        pl.fuelMax = cap(0);
        pl.fuel = Math.min(total, pl.fuelMax);
        pl.tankFuel[0] = pl.fuel;
        pl.emptyMass = w.params.shipMass;
        pl.items[Item.TANK.ordinal()] = 0;
        double left = total - pl.fuel;
        for (int i = 1; i <= w.items.initial[Item.TANK.ordinal()]; i++) {
            add(pl, left);
            left -= pl.tankFuel[i];
        }
    }

    /**
     * C {@code Update_tanks}: fuel flows from extra tanks to the main tank and over/underflows are moved on.
     * <p>
     * BEHAVIOR: without extra tanks the main tank holds the sum. Otherwise, from the last tank down: an over- or
     * underflow is carried to the next tank (wrapping from the main tank to the last); a non-current extra tank
     * with at least low_level + REFUEL_RATE gives REFUEL_RATE to the tank below when that has room, one below
     * low_level takes REFUEL_RATE from it (low_level = sum/(tanks+1) - 1, limited to [0, TANK_REFILL_LIMIT]).
     * When the last of the MAX_TANKS*4 allowed steps ends the pass (C's {@code !check} test), the fuel is
     * redistributed from the main tank up.
     */
    public void update(Player pl) {
        double[] tank = pl.tankFuel;
        if (pl.numTanks == 0) {
            tank[0] = pl.fuel;
            return;
        }
        long low = (long) pl.fuel / (pl.numTanks + 1) - 1;
        if (low < 0) {
            low = 0;
        }
        if (TANK_REFILL_LIMIT < low) {
            low = TANK_REFILL_LIMIT;
        }
        int t = pl.numTanks;
        int check = Player.MAX_TANKS << 2;
        double fuel = 0;
        while (t >= 0 && check-- != 0) {
            double m = cap(t);
            tank[t] += fuel;
            if (tank[t] > m) {
                fuel = tank[t] - m;
                tank[t] = m;
            } else if (tank[t] < 0) {
                fuel = tank[t];
                tank[t] = 0;
            } else {
                fuel = 0;
            }
            if (fuel == 0) {
                if (t != 0 && t != pl.currentTank && tank[t] >= low + REFUEL_RATE
                    && tank[t - 1] <= cap(t - 1) - REFUEL_RATE) {
                    tank[t] -= REFUEL_RATE;
                    fuel = REFUEL_RATE;
                } else if (t != 0 && tank[t] < low) {
                    tank[t] += REFUEL_RATE;
                    fuel = -REFUEL_RATE;
                }
            }
            if (fuel != 0 && t == 0) {
                t = pl.numTanks;
            } else {
                t--;
            }
        }
        if (check == 0) {
            redistribute(pl);
        }
    }

    /** The "fuel problem" recovery of Update_tanks: refill the tanks from the main one up with the sum. */
    private void redistribute(Player pl) {
        double fuel = pl.fuel;
        pl.fuel = 0;
        pl.fuelMax = 0;
        for (int t = 0; t <= pl.numTanks; t++) {
            if (fuel != 0) {
                if (fuel > cap(t)) {
                    pl.tankFuel[t] = cap(t);
                    fuel -= cap(t);
                } else {
                    pl.tankFuel[t] = fuel;
                    fuel = 0;
                }
                pl.fuel += pl.tankFuel[t];
            } else {
                pl.tankFuel[t] = 0;
            }
            pl.fuelMax += cap(t);
        }
    }

    /**
     * C KEY_TANK_NEXT / KEY_TANK_PREV.
     * <p>
     * BEHAVIOR: with tanks the current tank steps by +1/-1 through 0..numTanks, wrapping; without tanks
     * nothing happens.
     */
    public void select(Player pl, boolean next) {
        if (pl.numTanks == 0) {
            return;
        }
        pl.currentTank += next ? 1 : -1;
        if (pl.currentTank < 0) {
            pl.currentTank = pl.numTanks;
        } else if (pl.currentTank > pl.numTanks) {
            pl.currentTank = 0;
        }
    }

    /** C {@code TANK_THRUST_FACT} (a double: {@code power *= 0.7} rounds the double product to float). */
    static final double TANK_THRUST_FACT = 0.7;

    /** The tank ship (C {@code tankShipShape}), parsed once. */
    private com.fulcrologic.jxpilot.server.world.ShipGeom tankShip;

    /** Wired by the simulation (detaching a tank logs in a pseudo player). */
    Simulation sim;

    private com.fulcrologic.jxpilot.server.world.ShipGeom tankShip() {
        if (tankShip == null) {
            String s = w.options.getString(com.fulcrologic.jxpilot.common.config.Opt.TANK_SHIP_SHAPE);
            com.fulcrologic.jxpilot.common.math.ShipShape shape =
                com.fulcrologic.jxpilot.common.math.ShipShape.parse(s == null ? "" : s);
            tankShip = shape == null ? com.fulcrologic.jxpilot.server.world.ShipGeom.defaultShip()
                : new com.fulcrologic.jxpilot.server.world.ShipGeom(shape);
        }
        return tankShip;
    }

    /**
     * C {@code Tank_handle_detach} (KEY_TANK_DETACH; heat-seekers chase the tank instead of the ship).
     * <p>
     * BEHAVIOR: nothing while phased, without tanks, or when no pseudo player slot or id is free.
     * BEHAVIOR: the current extra tank (the last one when the main tank is current) leaves as a pseudo player
     * "NAME's tank" ('T', the owner's team, home and pseudo team, score - tankScoreDecrement, no tanks of its own,
     * the tank ship with allowShipShapes) at the ship's position, velocity and heading, with the tank's fuel
     * (its capacity as maximum), mass shipMass + fuel and power * 0.7; it thrusts and is shielded (with
     * shield time 30*FPS when shields are not allowed). Its owner is remembered in its lock id. TANK_DETACH
     * plays at the ship.
     * BEHAVIOR: heat-seekers chasing the ship retarget to the tank's position in the player order (C uses the
     * index where it means an id; kept); the ship loses the tank (Player_remove_tank). JOIN and SCORE go out.
     */
    public void detach(Player pl) {
        if ((pl.used & com.fulcrologic.jxpilot.server.world.Has.PHASING_DEVICE) != 0 || pl.numTanks == 0) {
            return;
        }
        int ct = pl.currentTank;
        if (ct == 0) {
            ct = pl.numTanks;
        }
        Player dummy = w.addTank();
        if (dummy == null) {
            return;
        }
        update(pl);
        sim.ships.initPlayer(dummy);
        dummy.tank = true;
        dummy.ship = w.items.allowShipShapes ? tankShip() : com.fulcrologic.jxpilot.server.world.ShipGeom.defaultShip();
        while (dummy.numTanks > 0) {
            remove(dummy, dummy.numTanks);
        }
        dummy.initPixels(pl.px, pl.py);
        dummy.vx = pl.vx;
        dummy.vy = pl.vy;
        dummy.accX = pl.accX;
        dummy.accY = pl.accY;
        dummy.dir = pl.dir;
        dummy.turnSpeed = pl.turnSpeed;
        dummy.velocity = pl.velocity;
        dummy.floatDir = pl.floatDir;
        dummy.turnResistance = pl.turnResistance;
        dummy.turnVel = pl.turnVel;
        dummy.oldTurnVel = pl.oldTurnVel;
        dummy.turnAcc = pl.turnAcc;
        dummy.power = pl.power;
        dummy.name.setLength(0);
        dummy.name.append(pl.name);
        int max = com.fulcrologic.jxpilot.common.Const.MAX_CHARS - 1;
        CharSequence suffix = "'s tank";
        for (int i = 0; i < suffix.length() && dummy.name.length() < max; i++) {
            dummy.name.append(suffix.charAt(i));
        }
        if (dummy.name.length() > max) {
            dummy.name.setLength(max);
        }
        dummy.homeBase = pl.homeBase;
        dummy.team = pl.team;
        dummy.mychar = 'T';
        dummy.score = pl.score - w.items.tankScoreDecrement;
        dummy.fuel = pl.tankFuel[ct];
        dummy.tankFuel[0] = dummy.fuel;
        dummy.fuelMax = cap(ct);
        dummy.currentTank = 0;
        dummy.numTanks = 0;
        dummy.emptyMass = w.params.shipMass;
        dummy.mass = (float) (dummy.emptyMass + dummy.fuel * 0.005 / 256);
        dummy.power *= TANK_THRUST_FACT;
        dummy.updateVisibility = true;
        dummy.lockPlId = pl.id;
        w.enter(dummy);
        dummy.pseudoTeam = pl.pseudoTeam;
        sim.locks.resetVisibility(dummy);
        Sounds.play(w, pl.px, pl.py, SoundEvent.TANK_DETACH);
        dummy.status = com.fulcrologic.jxpilot.server.world.Obj.PLAYING | com.fulcrologic.jxpilot.server.world.Obj.GRAVITY
            | com.fulcrologic.jxpilot.server.world.Obj.THRUSTING;
        dummy.have = w.params.defHave;
        dummy.used = (Has.DEF_USED & w.params.defHave & ~Has.USED_KILL & pl.have) | Has.SHIELD;
        if (!w.params.playerShielding) {
            dummy.shieldTime = 30 * w.params.fps;
            dummy.have |= Has.SHIELD;
        }
        com.fulcrologic.jxpilot.server.world.Objects o = w.objects;
        int tankIndex = w.numPlayers - 1;
        for (int k = 0; k < o.count(); k++) {
            int h = o.active[k];
            if (o.type[h] == com.fulcrologic.jxpilot.server.world.Obj.HEAT_SHOT && o.info[h] > 0
                && w.byId(o.info[h]) == pl) {
                o.info[h] = tankIndex;
            }
        }
        remove(pl, ct);
        w.events.add(com.fulcrologic.jxpilot.server.world.Events.JOIN, dummy.id,
            com.fulcrologic.jxpilot.common.net.Msg.PlayerJoin.FLAG_TANK, 0, 0f);
        dummy.scoreDirty = true;
    }

    /**
     * C {@code Tank_play} (robot.c): a tank thrusts in pulses, TANK_THRUST_TIME frames of every
     * TANK_NOTHRUST_TIME + TANK_THRUST_TIME (frame clock), starting at 0 and stopping at TANK_THRUST_TIME.
     */
    public void play() {
        int nothrust = (2 * w.params.fps) / 2 + 2;
        int thrust = nothrust / 2 + 1;
        int t = (int) (w.frames % (nothrust + thrust));
        for (int k = 0; k < w.numPlayers; k++) {
            Player p = w.playerAt(k);
            if (!p.tank) {
                continue;
            }
            if (t == 0) {
                p.status |= com.fulcrologic.jxpilot.server.world.Obj.THRUSTING;
            } else if (t == thrust) {
                p.status &= ~com.fulcrologic.jxpilot.server.world.Obj.THRUSTING;
            }
        }
    }
}
