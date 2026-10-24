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

import com.fulcrologic.jxpilot.common.Const;
import com.fulcrologic.jxpilot.common.SoundEvent;
import com.fulcrologic.jxpilot.common.map.Base;
import com.fulcrologic.jxpilot.common.map.BlockType;
import com.fulcrologic.jxpilot.common.net.ControlKey;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.server.world.Events;
import com.fulcrologic.jxpilot.server.world.Has;
import com.fulcrologic.jxpilot.server.world.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * Control input: C {@code Handle_keyboard} ({@code src/server/event.c}) for the S1 keys. The client sends
 * the full key state every tick; press and release edges are found by comparing with the last handled
 * mask, in key order, exactly like C's {@code last_keyv}/{@code prev_keyv}.
 */
public final class Keys {
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

    private final World w;
    private final Simulation sim;
    /** The mask being applied (C {@code last_keyv}). */
    private long current;

    public Keys(World w, Simulation sim) {
        this.w = w;
        this.sim = sim;
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
     * BEHAVIOR: a PAUSE press that unpauses ends key handling for this tick (C returns early): the handled
     * mask is just PAUSE and, as after any Go_home, held keys wait for the next change of the client's mask.
     */
    public void apply(Player pl, long keys) {
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
                pl.frameLastBusy = w.frames;
            }
            if (w.roundDelay > 0 && pressed && (ROUND_DELAY_KEYS & m) == 0) {
                continue;
            }
            boolean alive = (pl.status & (Obj.PLAYING | Obj.GAME_OVER | Obj.PAUSE | Obj.HOVERPAUSE)) == Obj.PLAYING;
            if (!alive && (DEAD_KEYS & m) == 0) {
                continue;
            }
            ControlKey k = ControlKey.ofBit(bit);
            if (pressed) {
                float power = pl.power;
                if (pressed(pl, k)) {
                    pl.prevKeys = m;
                    return;
                }
                if (pl.power != power) {
                    w.events.add(Events.THRUST, pl.id, pl.afterburners(), 0, pl.power);
                }
            } else {
                released(pl, k);
            }
        }
        pl.prevKeys = keys;
    }

    /**
     * Key press actions; returns true when C stops handling keys for this frame (an unpause).
     * <p>
     * BEHAVIOR: SHIELD raises shields (if allowed); FIRE_SHOT, when neither shielded nor already firing,
     * fires and starts autofire; TURN keys set turnAcc = (left - right) * turnspeed; THRUST starts thrusting;
     * REFUEL picks the nearest station; SELF_DESTRUCT toggles a 150-frame countdown; SWAP_SETTINGS swaps
     * power/turn settings when not turning; power +-10% and turnspeed +-5% (when not turning) within limits;
     * CHANGE_HOME takes over the base the ship is on; PAUSE toggles pause ({@link #pauseKey}).
     */
    boolean pressed(Player pl, ControlKey k) {
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
                    sim.shots.fireNormalShots(pl);
                }
            }
            case TURN_LEFT, TURN_RIGHT -> turnKeys(pl);
            case THRUST -> pl.status |= Obj.THRUSTING;
            case REFUEL -> sim.ships.startRefuel(pl);
            case SELF_DESTRUCT -> {
                pl.status ^= Obj.SELF_DESTRUCT;
                if ((pl.status & Obj.SELF_DESTRUCT) != 0) {
                    pl.count = 150;
                }
            }
            case SWAP_SETTINGS -> {
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
                pl.power *= 1.10;
                pl.power = (float) Math.min(pl.power, Const.MAX_PLAYER_POWER);
            }
            case DECREASE_POWER -> {
                pl.power *= 0.90;
                pl.power = (float) Math.max(pl.power, Const.MIN_PLAYER_POWER);
            }
            case INCREASE_TURNSPEED -> {
                if (pl.turnAcc == 0.0) {
                    pl.turnSpeed *= 1.05;
                }
                pl.turnSpeed = (float) Math.min(pl.turnSpeed, Const.MAX_PLAYER_TURNSPEED);
            }
            case DECREASE_TURNSPEED -> {
                if (pl.turnAcc == 0.0) {
                    pl.turnSpeed *= 0.95;
                }
                pl.turnSpeed = (float) Math.max(pl.turnSpeed, Const.MIN_PLAYER_TURNSPEED);
            }
            case CHANGE_HOME -> changeHome(pl);
            case PAUSE -> {
                return pauseKey(pl);
            }
            default -> { }
        }
        return false;
    }

    /**
     * C KEY_PAUSE without hover pause (that needs the autopilot, M2).
     * <p>
     * BEHAVIOR: a paused ship asks to unpause (C Pause_player(off): only once the pause count ran out); a ship
     * on its home base block flying slower than 3 + |gravity there| pauses; anywhere else nothing happens.
     * BEHAVIOR: returns true when the ship is playing again (C sets the key in both key vectors and stops
     * handling keys for this frame).
     */
    boolean pauseKey(Player pl) {
        if ((pl.status & Obj.PAUSE) == 0) {
            if (pl.homeBase < 0) {
                return false;
            }
            Base b = w.map.bases().get(pl.homeBase);
            if (b.bx() != pl.bx || b.by() != pl.by) {
                return false;
            }
            float gx = w.gravityX(pl.bx, pl.by);
            float gy = w.gravityY(pl.bx, pl.by);
            float minv = 3.0f;
            minv += (float) StrictMath.hypot(gx, gy);
            if (pl.velocity > minv) {
                return false;
            }
        }
        sim.ships.pause(pl, (pl.status & Obj.PAUSE) == 0);
        return (pl.status & Obj.PLAYING) != 0;
    }

    /**
     * Key release actions.
     * <p>
     * BEHAVIOR: TURN keys recompute turnAcc; REFUEL stops refuelling; SHIELD (outside the round delay) lowers
     * shields and restarts the fire-repeat timer (no instant shot after dropping shields); FIRE_SHOT stops
     * autofire; THRUST stops.
     */
    void released(Player pl, ControlKey k) {
        switch (k) {
            case TURN_LEFT, TURN_RIGHT -> turnKeys(pl);
            case REFUEL -> pl.used &= ~Has.REFUEL;
            case SHIELD -> {
                if ((pl.used & Has.SHIELD) != 0 && w.roundDelay == 0) {
                    pl.used &= ~(Has.SHIELD | Has.LASER);
                    pl.shotTime = w.frames;
                }
            }
            case FIRE_SHOT -> pl.used &= ~Has.SHOT;
            case THRUST -> pl.status &= ~Obj.THRUSTING;
            default -> { }
        }
    }

    private void turnKeys(Player pl) {
        pl.turnAcc = 0;
        if ((current & ControlKey.TURN_LEFT.mask()) != 0) {
            pl.turnAcc += pl.turnSpeed;
        }
        if ((current & ControlKey.TURN_RIGHT.mask()) != 0) {
            pl.turnAcc -= pl.turnSpeed;
        }
    }

    /**
     * C KEY_CHANGE_HOME (event.c) on the base block the ship is in.
     * <p>
     * BEHAVIOR: off a base block nothing happens; a base of another team (or the current home) is not taken.
     * BEHAVIOR: otherwise the old home is released (BASE_OWNER(old, NO_ID)), the base becomes home (BASE_OWNER)
     * and the message is "NAME has changed home base.".
     * BEHAVIOR: a player whose home it was gets a new base (C Pick_startpos: BASE_OWNER; a dead one restarts
     * its 3*FPS recovery count, a paused/game-over one is put on the new base) and the message becomes
     * "NAME has taken over OTHER's home base.".
     */
    void changeHome(Player pl) {
        if (w.map.blockAt(pl.bx, pl.by) != BlockType.BASE) {
            return;
        }
        boolean changed = false;
        java.util.List<Base> bases = w.map.bases();
        for (int i = 0; i < bases.size(); i++) {
            Base b = bases.get(i);
            if (b.bx() == pl.bx && b.by() == pl.by) {
                if (b.index() == pl.homeBase || (b.team() != Const.TEAM_NOT_SET && b.team() != pl.team)) {
                    return;
                }
                if (pl.homeBase >= 0) {
                    w.events.add(Events.BASE_OWNER, pl.homeBase, Obj.NO_ID, 0, 0f);
                }
                pl.homeBase = b.index();
                changed = true;
                w.events.add(Events.BASE_OWNER, pl.homeBase, pl.id, 0, 0f);
                break;
            }
        }
        if (!changed) {
            return;
        }
        Player displaced = null;
        for (int k = 0; k < w.numPlayers; k++) {
            Player o = w.playerAt(k);
            if (o != pl && o.homeBase == pl.homeBase) {
                displaced = o;
                if (sim.ships.pickStartPos(o)) {
                    w.events.add(Events.BASE_OWNER, o.homeBase, o.id, 0, 0f);
                    if ((o.status & Obj.PLAYING) == 0) {
                        o.count = 3 * w.params.fps;
                    } else if ((o.status & (Obj.PAUSE | Obj.GAME_OVER)) != 0) {
                        sim.ships.goHome(o);
                    }
                }
            }
        }
        Sounds.playAll(w, SoundEvent.CHANGE_HOME);
        StringBuilder m = w.events.message(Msg.Text.KIND_GAME, Obj.NO_ID).append(pl.name);
        if (displaced == null) {
            m.append(" has changed home base.");
        } else {
            m.append(" has taken over ").append(displaced.name).append("'s home base.");
        }
    }
}
