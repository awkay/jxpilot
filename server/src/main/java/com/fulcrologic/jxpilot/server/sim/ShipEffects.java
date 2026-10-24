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

import com.fulcrologic.jxpilot.common.SoundEvent;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.common.sim.KernelEvents;
import com.fulcrologic.jxpilot.common.sim.MoveState;
import com.fulcrologic.jxpilot.common.sim.ShipBody;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Params;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * The server's side of the ship kernel ({@link com.fulcrologic.jxpilot.common.sim.ShipKernel}): each kernel event
 * runs the server code that C runs at that point of the frame, exactly as before the kernel was extracted (crashes,
 * wall sparks and target damage, bullet objects, devices, refuelling, locks, warps, the keys the kernel does not
 * handle). The kernel steps the server's {@link Player}s in place, so every body here is a {@code Player}.
 */
public final class ShipEffects implements KernelEvents {
    private final World w;
    private final Simulation sim;

    public ShipEffects(World w, Simulation sim) {
        this.w = w;
        this.sim = sim;
    }

    private static Player pl(ShipBody b) {
        return (Player) b;
    }

    /**
     * BEHAVIOR: with predictableBounces, the flying phase (PHASE_FLY) of each ship starts by reseeding the bounce
     * tie-break source from (net tick {@code w.tick}, player id): its turn and its move draw from there.
     */
    @Override
    public void begin(ShipBody b, int phase) {
        if (phase == PHASE_FLY && sim.predictableBounces) {
            sim.tieBreak.reseed(w.tick, pl(b).id);
        }
    }

    @Override
    public boolean keyPressed(ShipBody b, ControlKey k) {
        return sim.keys.pressedOther(pl(b), k);
    }

    @Override
    public void keyReleased(ShipBody b, ControlKey k) {
        sim.keys.releasedOther(pl(b), k);
    }

    @Override
    public void autopilotOff(ShipBody b) {
        sim.devices.autopilot(pl(b), false);
    }

    @Override
    public void powerChanged(ShipBody b) {
        Player p = pl(b);
        w.events.add(Events.THRUST, p.id, p.afterburners(), 0, p.power);
    }

    @Override
    public boolean shotRoom() {
        return w.objects.count() < w.objects.capacity;
    }

    @Override
    public int shot(ShipBody b, float x, float y, int dir, float vx, float vy, boolean outside) {
        return sim.shots.playerShot(pl(b), x, y, dir, vx, vy, outside);
    }

    @Override
    public void devices(ShipBody b) {
        Player p = pl(b);
        sim.devices.timers(p);
        sim.devices.use(p);
    }

    @Override
    public void upkeep(ShipBody b) {
        Player p = pl(b);
        sim.devices.drain(p);
        sim.locks.updateVisibility(p);
        sim.ships.refuel(p);
        sim.targets.repair(p);
    }

    @Override
    public boolean warp(ShipBody b) {
        return sim.ships.warp(pl(b));
    }

    @Override
    public void thrustSparks(ShipBody b) {
        sim.debris.thrust(pl(b));
    }

    @Override
    public void turnSparks(ShipBody b, int count) {
        sim.debris.turnThrust(pl(b), count);
    }

    @Override
    public void sensors(ShipBody b) {
        sim.locks.sensorRange(pl(b));
    }

    @Override
    public void crashed(ShipBody b, MoveState m, int point) {
        sim.crashes.playerCrashed(pl(b), m, point);
    }

    @Override
    public void wallDamage(ShipBody b) {
        sim.items.damage(pl(b), w.items.wallBounceDestroyItemProb);
    }

    /** Move_player's wall bounce effects (walls.c:2520-2530): sparks, the bounce sound, the target's damage. */
    @Override
    public void bounced(ShipBody b, int wallDir, long cost, int target) {
        Player p = pl(b);
        Params pa = w.params;
        sim.debris.wallSparks(p, wallDir, (int) (cost * pa.wallBounceExplosionMult));
        Sounds.play(w, p.px, p.py, SoundEvent.PLAYER_BOUNCED);
        if (target >= 0) {
            sim.targets.playerHits(target, p, (long) ((cost << 8) * (pa.wallBounceFuelDrainMult / 4.0)));
        }
    }
}
