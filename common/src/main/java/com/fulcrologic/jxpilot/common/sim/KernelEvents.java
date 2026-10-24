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

import com.fulcrologic.jxpilot.common.net.ControlKey;

/**
 * What the ship kernel cannot do itself: its side effects (crashes, wall bounce sparks and damage, the bullet
 * object a shot creates, exhaust) and the world interactions that C runs in the middle of a ship's frame
 * (devices, refuelling, target repair, warps, locks). The kernel calls these at exactly the points where C's
 * {@code Update_objects}, {@code Move_player}, {@code Handle_keyboard} and {@code Fire_general_shot} do that
 * work, so the server's implementation keeps C's order and C's random stream. A client implements them as
 * predictions or no-ops.
 * <p>
 * Every method receives the body being stepped; on the server it is the {@code Player} itself.
 */
public interface KernelEvents {
    /** {@link #begin}/{@link #end} phase: key handling ({@link ShipKeys#apply}). */
    int PHASE_KEYS = 0;
    /** Phase: autofire ({@link ShipShooter#autofire}). */
    int PHASE_AUTOFIRE = 1;
    /** Phase: the flying part of the player loop ({@link ShipKernel#fly}). */
    int PHASE_FLY = 2;

    /** A kernel phase starts for {@code b} (observation point; no-op by default). */
    default void begin(ShipBody b, int phase) {
    }

    /** A kernel phase ended for {@code b}, also when it returned early (observation point; no-op by default). */
    default void end(ShipBody b, int phase) {
    }

    // ---------------------------------------------------------------- keys (C Handle_keyboard)

    /**
     * A press of a key the kernel does not handle (items, weapons, locks, pause...).
     *
     * @return true when C stops handling keys for this frame (an unpause)
     */
    boolean keyPressed(ShipBody b, ControlKey k);

    /** A release of a key the kernel does not handle. */
    void keyReleased(ShipBody b, ControlKey k);

    /** A turn or thrust key switches an engaged autopilot off (C {@code Autopilot(pl, false)}). */
    void autopilotOff(ShipBody b);

    /** A key press changed the engine power (the server tells the clients: THRUST event). */
    void powerChanged(ShipBody b);

    // ---------------------------------------------------------------- shots (C Fire_general_shot)

    /** The object pool has room for one more object (C returns before anything when it is full). */
    boolean shotRoom();

    /**
     * A bullet was fired (fuel paid, shot counted): create its object. When {@code outside}, the wrapped start lies
     * outside a flat world and C makes its unpositioned object, with no velocity and no recoil.
     *
     * @return the object handle, or -1 when none is positioned
     */
    int shot(ShipBody b, float x, float y, int dir, float vx, float vy, boolean outside);

    // ---------------------------------------------------------------- the player loop (C Update_objects)

    /** Device timers and use (emergency thrust/shield, phasing, autopilot...), before turning. */
    void devices(ShipBody b);

    /** After the shield fuel: device fuel drain, lock visibility, refuelling and target repair. */
    void upkeep(ShipBody b);

    /**
     * A warping ship goes through its wormhole.
     *
     * @return true when C's {@code break} ends the whole player loop for this frame
     */
    boolean warp(ShipBody b);

    /** Exhaust of a thrusting ship this frame. */
    void thrustSparks(ShipBody b);

    /** Turn sparks ({@code turnThrust}): {@code count} of them. */
    void turnSparks(ShipBody b, int count);

    /** After the move: the sensor range. */
    void sensors(ShipBody b);

    // ---------------------------------------------------------------- moving (C Move_player, Turn_player)

    /** The ship crashed; {@code m} holds the crash type and what was hit, {@code point} the hull point. */
    void crashed(ShipBody b, MoveState m, int point);

    /** A wall bounce of a ship that is not fully shielded may destroy items (C {@code Item_damage}). */
    void wallDamage(ShipBody b);

    /** A wall bounce that cost fuel: sparks, sound, and damage to the target hit ({@code target} or -1). */
    void bounced(ShipBody b, int wallDir, long cost, int target);
}
