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

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.net.ControlKey;

/**
 * Control input for one ship: the key-edge loop of C {@code Handle_keyboard} ({@code src/server/event.c}) and the
 * keys that steer the ship (shield, fire, turn, thrust, the power and turn settings). The client sends the full
 * key state every tick; press and release edges are found by comparing with the last handled mask, in key order,
 * exactly like C's {@code last_keyv}/{@code prev_keyv}. Every other key goes to {@link KernelEvents#keyPressed}
 * and {@link KernelEvents#keyReleased} at its place in that order.
 */
public final class ShipKeys {

    private static final long DEAD_KEYS = ControlKey.PAUSE.mask() | ControlKey.LOCK_NEXT.mask()
        | ControlKey.LOCK_PREV.mask() | ControlKey.LOCK_CLOSE.mask() | ControlKey.LOCK_NEXT_CLOSE.mask()
        | ControlKey.TOGGLE_NUCLEAR.mask() | ControlKey.TOGGLE_CLUSTER.mask() | ControlKey.TOGGLE_IMPLOSION.mask()
        | ControlKey.TOGGLE_VELOCITY.mask() | ControlKey.TOGGLE_MINI.mask() | ControlKey.TOGGLE_SPREAD.mask()
        | ControlKey.TOGGLE_POWER.mask() | ControlKey.TOGGLE_LASER.mask() | ControlKey.TOGGLE_COMPASS.mask()
        | ControlKey.CLEAR_MODIFIERS.mask() | ControlKey.LOAD_MODIFIERS_1.mask() | ControlKey.LOAD_MODIFIERS_2.mask()
        | ControlKey.LOAD_MODIFIERS_3.mask() | ControlKey.LOAD_MODIFIERS_4.mask() | ControlKey.LOAD_LOCK_1.mask()
        | ControlKey.LOAD_LOCK_2.mask() | ControlKey.LOAD_LOCK_3.mask() | ControlKey.LOAD_LOCK_4.mask()
        | ControlKey.REPROGRAM.mask() | ControlKey.SWAP_SETTINGS.mask() | ControlKey.INCREASE_POWER.mask()
        | ControlKey.DECREASE_POWER.mask() | ControlKey.INCREASE_TURNSPEED.mask()
        | ControlKey.DECREASE_TURNSPEED.mask() | ControlKey.TANK_NEXT.mask() | ControlKey.TANK_PREV.mask()
        | ControlKey.TURN_LEFT.mask() | ControlKey.TURN_RIGHT.mask();

    /** Keys whose press is handled during the round delay (event.c:318-356). */
    private static final long ROUND_DELAY_KEYS = ControlKey.LOCK_NEXT.mask() | ControlKey.LOCK_PREV.mask()
        | ControlKey.LOCK_CLOSE.mask() | ControlKey.TOGGLE_NUCLEAR.mask() | ControlKey.PAUSE.mask()
        | ControlKey.TANK_NEXT.mask() | ControlKey.TANK_PREV.mask() | ControlKey.TOGGLE_VELOCITY.mask()
        | ControlKey.TOGGLE_CLUSTER.mask() | ControlKey.SWAP_SETTINGS.mask() | ControlKey.INCREASE_POWER.mask()
        | ControlKey.DECREASE_POWER.mask() | ControlKey.INCREASE_TURNSPEED.mask()
        | ControlKey.DECREASE_TURNSPEED.mask() | ControlKey.LOCK_NEXT_CLOSE.mask() | ControlKey.TOGGLE_COMPASS.mask()
        | ControlKey.TOGGLE_MINI.mask() | ControlKey.TOGGLE_SPREAD.mask() | ControlKey.TOGGLE_POWER.mask()
        | ControlKey.TOGGLE_LASER.mask() | ControlKey.CLEAR_MODIFIERS.mask() | ControlKey.LOAD_MODIFIERS_1.mask()
        | ControlKey.LOAD_MODIFIERS_2.mask() | ControlKey.LOAD_MODIFIERS_3.mask() | ControlKey.LOAD_MODIFIERS_4.mask()
        | ControlKey.SELECT_ITEM.mask() | ControlKey.TOGGLE_IMPLOSION.mask() | ControlKey.REPROGRAM.mask()
        | ControlKey.LOAD_LOCK_1.mask() | ControlKey.LOAD_LOCK_2.mask() | ControlKey.LOAD_LOCK_3.mask()
        | ControlKey.LOAD_LOCK_4.mask();

    /** Keys whose press is handled while phased (event.c:403-453). */
    private static final long PHASED_KEYS = ControlKey.LOCK_NEXT.mask() | ControlKey.LOCK_PREV.mask()
        | ControlKey.LOCK_CLOSE.mask() | ControlKey.SHIELD.mask() | ControlKey.TOGGLE_NUCLEAR.mask()
        | ControlKey.TURN_LEFT.mask() | ControlKey.TURN_RIGHT.mask() | ControlKey.SELF_DESTRUCT.mask()
        | ControlKey.LOSE_ITEM.mask() | ControlKey.PAUSE.mask() | ControlKey.TANK_NEXT.mask()
        | ControlKey.TANK_PREV.mask() | ControlKey.TOGGLE_VELOCITY.mask() | ControlKey.TOGGLE_CLUSTER.mask()
        | ControlKey.SWAP_SETTINGS.mask() | ControlKey.INCREASE_POWER.mask() | ControlKey.DECREASE_POWER.mask()
        | ControlKey.INCREASE_TURNSPEED.mask() | ControlKey.DECREASE_TURNSPEED.mask() | ControlKey.THRUST.mask()
        | ControlKey.CLOAK.mask() | ControlKey.DROP_BALL.mask() | ControlKey.TALK.mask()
        | ControlKey.LOCK_NEXT_CLOSE.mask() | ControlKey.TOGGLE_COMPASS.mask() | ControlKey.TOGGLE_MINI.mask()
        | ControlKey.TOGGLE_SPREAD.mask() | ControlKey.TOGGLE_POWER.mask() | ControlKey.TOGGLE_LASER.mask()
        | ControlKey.EMERGENCY_THRUST.mask() | ControlKey.CLEAR_MODIFIERS.mask()
        | ControlKey.LOAD_MODIFIERS_1.mask() | ControlKey.LOAD_MODIFIERS_2.mask()
        | ControlKey.LOAD_MODIFIERS_3.mask() | ControlKey.LOAD_MODIFIERS_4.mask() | ControlKey.SELECT_ITEM.mask()
        | ControlKey.PHASING.mask() | ControlKey.TOGGLE_IMPLOSION.mask() | ControlKey.REPROGRAM.mask()
        | ControlKey.LOAD_LOCK_1.mask() | ControlKey.LOAD_LOCK_2.mask() | ControlKey.LOAD_LOCK_3.mask()
        | ControlKey.LOAD_LOCK_4.mask() | ControlKey.EMERGENCY_SHIELD.mask() | ControlKey.TOGGLE_AUTOPILOT.mask();

    private final ShipWorld w;
    private final ShipShooter shooter;
    /** The mask being applied (C {@code last_keyv}). */
    private long current;

    public ShipKeys(ShipWorld w, ShipShooter shooter) {
        this.w = w;
        this.shooter = shooter;
    }

    /**
     * Handle the key changes between the last handled mask and {@code keys}, then remember {@code keys}.
     * <p>
     * BEHAVIOR: only changed keys act, in ascending key order; any change except SHIELD marks the player busy
     * (C {@code frame_last_busy}, for idle pause).
     * BEHAVIOR: after Go_home the held keys are ignored until the client's mask changes; then every held key
     * counts as newly pressed (C clears its key vectors in Go_home and gets the full state with the next
     * key packet).
     * BEHAVIOR: during the round delay only the presses C allows then act (pause, settings, locks...);
     * releases always act.
     * BEHAVIOR: a dead or paused ship only handles the keys C allows while dead (turning, power settings,
     * pause, locks...), and the others are consumed without effect.
     * BEHAVIOR: a press that changes the power is reported ({@link KernelEvents#powerChanged}).
     * BEHAVIOR: a PAUSE press that unpauses ends key handling for this tick (C returns early): the handled
     * mask is just PAUSE and, as after any Go_home, held keys wait for the next change of the client's mask.
     */
    public void apply(ShipBody pl, long keys, KernelEvents ev) {
        ev.begin(pl, KernelEvents.PHASE_KEYS);
        handle(pl, keys, ev);
        ev.end(pl, KernelEvents.PHASE_KEYS);
    }

    private void handle(ShipBody pl, long keys, KernelEvents ev) {
        if (pl.keysCleared) {
            if (keys == pl.lastReceivedKeys) {
                return;
            }
            pl.keysCleared = false;
        }
        pl.lastReceivedKeys = keys;
        current = keys;
        long changed = keys ^ pl.prevKeys;
        while (changed != 0) {
            int bit = Long.numberOfTrailingZeros(changed);
            long m = 1L << bit;
            changed &= ~m;
            boolean pressed = (keys & m) != 0;
            if (m != ControlKey.SHIELD.mask()) {
                pl.frameLastBusy = w.frames();
            }
            if (w.roundDelay() > 0 && pressed && (ROUND_DELAY_KEYS & m) == 0) {
                continue;
            }
            boolean alive = (pl.status & (Obj.PLAYING | Obj.GAME_OVER | Obj.PAUSE | Obj.HOVERPAUSE)) == Obj.PLAYING;
            if (!alive && (DEAD_KEYS & m) == 0) {
                continue;
            }
            if (pressed && (pl.used & Has.PHASING_DEVICE) != 0 && (PHASED_KEYS & m) == 0) {
                continue;
            }
            ControlKey k = ControlKey.ofBit(bit);
            if (pressed) {
                float power = pl.power;
                if (pressed(pl, k, ev)) {
                    pl.prevKeys = m;
                    return;
                }
                if (pl.power != power) {
                    ev.powerChanged(pl);
                }
            } else {
                released(pl, k, ev);
            }
        }
        pl.prevKeys = keys;
    }

    /**
     * Key press actions; returns true when C stops handling keys for this frame (an unpause).
     * <p>
     * BEHAVIOR: SHIELD raises shields (if allowed); FIRE_SHOT, when neither shielded nor already firing,
     * fires and starts autofire; TURN keys set turnAcc = (left - right) * turnspeed; THRUST starts thrusting;
     * TURN and THRUST switch an engaged autopilot off first; SWAP_SETTINGS swaps power/turn settings when not
     * turning; power +-10% and turnspeed +-5% (when not turning) within limits; the settings keys do nothing
     * while hover-paused or on autopilot. Every other key is {@link KernelEvents#keyPressed}'s.
     */
    public boolean pressed(ShipBody pl, ControlKey k, KernelEvents ev) {
        switch (k) {
            case SHIELD -> {
                if ((pl.have & Has.SHIELD) != 0) {
                    pl.used |= Has.SHIELD;
                    pl.used &= ~Has.LASER;
                }
            }
            case FIRE_SHOT -> {
                if ((pl.used & (Has.SHIELD | Has.SHOT)) == 0 && (pl.have & Has.SHOT) != 0) {
                    pl.used |= Has.SHOT;
                    shooter.fireNormalShots(pl, ev);
                }
            }
            case TURN_LEFT, TURN_RIGHT -> {
                autopilotOff(pl, ev);
                turnKeys(pl);
            }
            case THRUST -> {
                autopilotOff(pl, ev);
                pl.status |= Obj.THRUSTING;
            }
            case SWAP_SETTINGS -> {
                if (hoverOrAutopilot(pl)) {
                    return false;
                }
                if (pl.turnAcc == 0.0) {
                    float t = pl.power;
                    pl.power = pl.powerS;
                    pl.powerS = t;
                    t = pl.turnSpeed;
                    pl.turnSpeed = pl.turnSpeedS;
                    pl.turnSpeedS = t;
                    t = pl.turnResistance;
                    pl.turnResistance = pl.turnResistanceS;
                    pl.turnResistanceS = t;
                }
            }
            case INCREASE_POWER -> {
                if (hoverOrAutopilot(pl)) {
                    return false;
                }
                pl.power *= 1.10;
                pl.power = (float) Math.min(pl.power, Const.MAX_PLAYER_POWER);
            }
            case DECREASE_POWER -> {
                if (hoverOrAutopilot(pl)) {
                    return false;
                }
                pl.power *= 0.90;
                pl.power = (float) Math.max(pl.power, Const.MIN_PLAYER_POWER);
            }
            case INCREASE_TURNSPEED -> {
                if (hoverOrAutopilot(pl)) {
                    return false;
                }
                if (pl.turnAcc == 0.0) {
                    pl.turnSpeed *= 1.05;
                }
                pl.turnSpeed = (float) Math.min(pl.turnSpeed, Const.MAX_PLAYER_TURNSPEED);
            }
            case DECREASE_TURNSPEED -> {
                if (hoverOrAutopilot(pl)) {
                    return false;
                }
                if (pl.turnAcc == 0.0) {
                    pl.turnSpeed *= 0.95;
                }
                pl.turnSpeed = (float) Math.max(pl.turnSpeed, Const.MIN_PLAYER_TURNSPEED);
            }
            default -> {
                return ev.keyPressed(pl, k);
            }
        }
        return false;
    }

    /**
     * Key release actions.
     * <p>
     * BEHAVIOR: TURN keys recompute turnAcc (an engaged autopilot goes off first); SHIELD (outside the round delay)
     * lowers shields and restarts the fire-repeat timer (no instant shot after dropping shields); FIRE_SHOT stops
     * autofire; THRUST stops (autopilot off first). Every other key is {@link KernelEvents#keyReleased}'s.
     */
    public void released(ShipBody pl, ControlKey k, KernelEvents ev) {
        switch (k) {
            case TURN_LEFT, TURN_RIGHT -> {
                autopilotOff(pl, ev);
                turnKeys(pl);
            }
            case SHIELD -> {
                if ((pl.used & Has.SHIELD) != 0 && w.roundDelay() == 0) {
                    pl.used &= ~(Has.SHIELD | Has.LASER);
                    pl.shotTime = w.frames();
                }
            }
            case FIRE_SHOT -> pl.used &= ~Has.SHOT;
            case THRUST -> {
                autopilotOff(pl, ev);
                pl.status &= ~Obj.THRUSTING;
            }
            default -> ev.keyReleased(pl, k);
        }
    }

    private static void autopilotOff(ShipBody pl, KernelEvents ev) {
        if ((pl.used & Has.AUTOPILOT) != 0) {
            ev.autopilotOff(pl);
        }
    }

    /**
     * C {@code Receive_pointer_move} (netserver.c:3140): pointer control turned the ship by {@code movement} (the
     * client's horizontal mouse movement in window pixels).
     * <p>
     * BEHAVIOR: ignored in HOVERPAUSE; an engaged autopilot is switched off first; the turn speed is
     * {@code movement * turnSpeed / MAX_PLAYER_TURNSPEED}, its size limited to MIN..MAX_PLAYER_TURNSPEED with a
     * turn resistance, else to 0..5 * RES; {@code turnVel -= direction * size}.
     */
    public void pointerMove(ShipBody pl, int movement, KernelEvents ev) {
        if ((pl.status & Obj.HOVERPAUSE) != 0) {
            return;
        }
        autopilotOff(pl, ev);
        float turnspeed = (float) (movement * pl.turnSpeed / Const.MAX_PLAYER_TURNSPEED);
        float turndir = 1.0f;
        if (turnspeed < 0) {
            turndir = -1.0f;
            turnspeed = -turnspeed;
        }
        if (pl.turnResistance != 0) {
            turnspeed = (float) Math.max(Const.MIN_PLAYER_TURNSPEED, Math.min(Const.MAX_PLAYER_TURNSPEED, turnspeed));
        } else {
            turnspeed = Math.max(0, Math.min(5 * Const.RES, turnspeed));
        }
        pl.turnVel -= turndir * turnspeed;
    }

    /** BEHAVIOR: true while hover-paused or on autopilot (the power and turn settings keys do nothing then). */
    public static boolean hoverOrAutopilot(ShipBody pl) {
        return (pl.status & Obj.HOVERPAUSE) != 0 || (pl.used & Has.AUTOPILOT) != 0;
    }

    private void turnKeys(ShipBody pl) {
        pl.turnAcc = 0;
        if ((current & ControlKey.TURN_LEFT.mask()) != 0) {
            pl.turnAcc += pl.turnSpeed;
        }
        if ((current & ControlKey.TURN_RIGHT.mask()) != 0) {
            pl.turnAcc -= pl.turnSpeed;
        }
    }
}
