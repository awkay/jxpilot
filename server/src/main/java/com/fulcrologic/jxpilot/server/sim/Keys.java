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
import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.sim.Has;
import com.fulcrologic.jxpilot.server.world.Mods;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;

/**
 * Control input: C {@code Handle_keyboard} ({@code src/server/event.c}) for the S1 keys. The client sends
 * the full key state every tick; press and release edges are found by comparing with the last handled
 * mask, in key order, exactly like C's {@code last_keyv}/{@code prev_keyv}.
 */
public final class Keys {

    // ---- message texts as fields: resolved when the class loads, never inside a tick (no allocation)
    private static final String T_HAS_CHANGED_HOME_BASE = Texts.of(" has changed home base.");
    private static final String T_HAS_TAKEN_OVER = Texts.of(" has taken over ");
    private static final String T_S_HOME_BASE = Texts.of("'s home base.");

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
            if (pressed && (pl.used & Has.PHASING_DEVICE) != 0 && (PHASED_KEYS & m) == 0) {
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
     * CHANGE_HOME takes over the base the ship is on; PAUSE toggles pause ({@link #pauseKey}); DROP_BALL drops
     * every ball; REPAIR picks the nearest own target ({@link Targets#startRepair}); CONNECTOR uses the
     * connector (if the ship has one).
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
            case TURN_LEFT, TURN_RIGHT -> {
                autopilotOff(pl);
                turnKeys(pl);
            }
            case THRUST -> {
                autopilotOff(pl);
                pl.status |= Obj.THRUSTING;
            }
            case REFUEL -> sim.ships.startRefuel(pl);
            case TANK_NEXT -> sim.tanks.select(pl, true);
            case TANK_PREV -> sim.tanks.select(pl, false);
            case TANK_DETACH -> sim.tanks.detach(pl);
            case LOCK_NEXT -> sim.locks.lockNext(pl, false);
            case LOCK_PREV -> sim.locks.lockNext(pl, true);
            case TOGGLE_COMPASS -> sim.locks.toggleCompass(pl);
            case LOCK_NEXT_CLOSE -> sim.locks.lockNextClose(pl);
            case LOCK_CLOSE -> sim.locks.lockClosest(pl, false);
            case FIRE_MISSILE -> sim.missiles.key(pl, Obj.SMART_SHOT);
            case FIRE_HEAT -> sim.missiles.key(pl, Obj.HEAT_SHOT);
            case FIRE_TORPEDO -> sim.missiles.key(pl, Obj.TORPEDO);
            case FIRE_LASER -> {
                if (pl.items[Item.LASER.ordinal()] > 0 && (pl.used & Has.SHIELD) == 0) {
                    pl.used |= Has.LASER;
                }
            }
            case TOGGLE_NUCLEAR, TOGGLE_CLUSTER, TOGGLE_IMPLOSION, TOGGLE_VELOCITY, TOGGLE_MINI, TOGGLE_SPREAD,
                 TOGGLE_LASER, TOGGLE_POWER, CLEAR_MODIFIERS -> pl.mods = toggleMods(pl.mods, k);
            case REPROGRAM -> pl.status |= Obj.REPROGRAM;
            case LOAD_MODIFIERS_1, LOAD_MODIFIERS_2, LOAD_MODIFIERS_3, LOAD_MODIFIERS_4 ->
                loadModifiers(pl, k.ordinal() - ControlKey.LOAD_MODIFIERS_1.ordinal());
            case LOAD_LOCK_1, LOAD_LOCK_2, LOAD_LOCK_3, LOAD_LOCK_4 ->
                sim.locks.loadLock(pl, k.ordinal() - ControlKey.LOAD_LOCK_1.ordinal());
            case TOGGLE_AUTOPILOT -> {
                if ((pl.have & Has.AUTOPILOT) != 0) {
                    sim.devices.autopilot(pl, (pl.used & Has.AUTOPILOT) == 0);
                }
            }
            case EMERGENCY_THRUST -> {
                if ((pl.have & Has.EMERGENCY_THRUST) != 0) {
                    sim.devices.emergencyThrust(pl, (pl.used & Has.EMERGENCY_THRUST) == 0);
                }
            }
            case EMERGENCY_SHIELD -> {
                if ((pl.have & Has.EMERGENCY_SHIELD) != 0) {
                    sim.devices.emergencyShield(pl, (pl.used & Has.EMERGENCY_SHIELD) == 0);
                }
            }
            case DROP_MINE -> sim.mines.placeMine(pl);
            case DETACH_MINE -> sim.mines.placeMovingMine(pl);
            case DETONATE_MINES -> sim.mines.detonate(pl);
            case CLOAK -> {
                if (pl.items[Item.CLOAK.ordinal()] > 0) {
                    sim.devices.cloak(pl, (pl.used & Has.CLOAKING_DEVICE) == 0);
                }
            }
            case ECM -> sim.ecm.fire(pl);
            case TRANSPORTER -> sim.devices.transporter(pl);
            case DEFLECTOR -> {
                if (pl.items[Item.DEFLECTOR.ordinal()] > 0) {
                    sim.devices.deflector(pl, (pl.used & Has.DEFLECTOR) == 0);
                }
            }
            case HYPERJUMP -> sim.devices.hyperjumpKey(pl);
            case PHASING -> {
                if ((pl.have & Has.PHASING_DEVICE) != 0) {
                    sim.devices.phasing(pl, (pl.used & Has.PHASING_DEVICE) == 0);
                }
            }
            case SELECT_ITEM -> sim.items.selectItem(pl);
            case LOSE_ITEM -> sim.items.loseItem(pl);
            case PRESSOR_BEAM, TRACTOR_BEAM -> {
                if ((pl.have & Has.TRACTOR_BEAM) != 0) {
                    pl.tractorIsPressor = k == ControlKey.PRESSOR_BEAM;
                    pl.used |= Has.TRACTOR_BEAM;
                }
            }
            case SELF_DESTRUCT -> {
                pl.status ^= Obj.SELF_DESTRUCT;
                if ((pl.status & Obj.SELF_DESTRUCT) != 0) {
                    pl.count = 150;
                }
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
            case CHANGE_HOME -> changeHome(pl);
            case DROP_BALL -> sim.balls.detach(pl, -1);
            case REPAIR -> sim.targets.startRepair(pl);
            case CONNECTOR -> {
                if ((pl.have & Has.CONNECTOR) != 0) {
                    pl.used |= Has.CONNECTOR;
                }
            }
            case PAUSE -> {
                return pauseKey(pl);
            }
            default -> { }
        }
        return false;
    }

    /**
     * C KEY_PAUSE (event.c:791-880).
     * <p>
     * BEHAVIOR: a paused ship asks to unpause (C Pause_player(off): only once the pause count ran out); a
     * hover-paused one asks to end the hover pause. Otherwise a ship on its home base block slower than 3 +
     * |gravity| pauses; one farther than 2 blocks from home and slower than 5 + |gravity| hover-pauses; anything
     * else does nothing.
     * BEHAVIOR: pausing turns the autopilot off. Hover pause (not while paused) lasts at least 5*FPS frames:
     * self-destruct, emergency thrust, emergency shield and phasing go off, the autopilot holds the ship, the
     * USED_KILL abilities stop and shields go up if available; ending it (after the count) turns the autopilot
     * off and drops shields the ship does not have.
     * BEHAVIOR: returns true when an unpause left the ship playing (C sets the key in both key vectors and stops
     * handling keys for this frame).
     */
    boolean pauseKey(Player pl) {
        int mode;
        if ((pl.status & Obj.PAUSE) != 0) {
            mode = Obj.PAUSE;
        } else if ((pl.status & Obj.HOVERPAUSE) != 0) {
            mode = Obj.HOVERPAUSE;
        } else {
            if (pl.homeBase < 0) {
                return false;
            }
            Base b = w.map.bases().get(pl.homeBase);
            float minv;
            if (b.bx() == pl.bx && b.by() == pl.by) {
                minv = 3.0f;
                mode = Obj.PAUSE;
            } else {
                if (Math.abs(b.bx() - pl.bx) <= 2 && Math.abs(b.by() - pl.by) <= 2) {
                    return false;
                }
                minv = 5.0f;
                mode = Obj.HOVERPAUSE;
            }
            float gx = w.gravityX(pl.bx, pl.by);
            float gy = w.gravityY(pl.bx, pl.by);
            // C: minv += VECTOR_LENGTH(gravity) adds the double length, then stores the DFLOAT (event.c:816).
            minv = (float) (minv + StrictMath.hypot(gx, gy));
            if (pl.velocity > minv) {
                return false;
            }
        }
        if (mode == Obj.PAUSE) {
            if ((pl.status & Obj.HOVERPAUSE) != 0) {
                return false;
            }
            autopilotOff(pl);
            sim.ships.pause(pl, (pl.status & Obj.PAUSE) == 0);
            return (pl.status & Obj.PLAYING) != 0;
        }
        if ((pl.status & Obj.PAUSE) != 0) {
            return false;
        }
        if ((pl.status & Obj.HOVERPAUSE) == 0) {
            pl.count = 5 * w.params.fps;
            pl.status &= ~Obj.SELF_DESTRUCT;
            pl.status |= Obj.HOVERPAUSE;
            if ((pl.used & Has.EMERGENCY_THRUST) != 0) {
                sim.devices.emergencyThrust(pl, false);
            }
            if ((pl.used & Has.EMERGENCY_SHIELD) != 0) {
                sim.devices.emergencyShield(pl, false);
            }
            if ((pl.used & Has.AUTOPILOT) == 0) {
                sim.devices.autopilot(pl, true);
            }
            if ((pl.used & Has.PHASING_DEVICE) != 0) {
                sim.devices.phasing(pl, false);
            }
            pl.used &= ~Has.USED_KILL;
            if ((w.params.defHave & Has.SHIELD) == 0) {
                pl.have &= ~Has.SHIELD;
            }
            if ((pl.have & Has.SHIELD) != 0) {
                pl.used |= Has.SHIELD;
            }
        } else if (pl.count <= 0) {
            sim.devices.autopilot(pl, false);
            pl.status &= ~Obj.HOVERPAUSE;
            if ((pl.have & Has.SHIELD) == 0) {
                pl.used &= ~Has.SHIELD;
            }
        }
        return false;
    }

    /**
     * Key release actions.
     * <p>
     * BEHAVIOR: TURN keys recompute turnAcc; REFUEL stops refuelling; SHIELD (outside the round delay) lowers
     * shields and restarts the fire-repeat timer (no instant shot after dropping shields); FIRE_SHOT stops
     * autofire; THRUST stops; REPAIR and CONNECTOR stop.
     */
    void released(Player pl, ControlKey k) {
        switch (k) {
            case TURN_LEFT, TURN_RIGHT -> {
                autopilotOff(pl);
                turnKeys(pl);
            }
            case REFUEL -> pl.used &= ~Has.REFUEL;
            case REPAIR -> pl.used &= ~Has.REPAIR;
            case CONNECTOR -> pl.used &= ~Has.CONNECTOR;
            case TRACTOR_BEAM, PRESSOR_BEAM -> pl.used &= ~Has.TRACTOR_BEAM;
            case FIRE_LASER -> pl.used &= ~Has.LASER;
            case REPROGRAM -> pl.status &= ~Obj.REPROGRAM;
            case SELECT_ITEM -> pl.loseItemState = 1;
            case SHIELD -> {
                if ((pl.used & Has.SHIELD) != 0 && w.roundDelay == 0) {
                    pl.used &= ~(Has.SHIELD | Has.LASER);
                    pl.shotTime = w.frames;
                }
            }
            case FIRE_SHOT -> pl.used &= ~Has.SHOT;
            case THRUST -> {
                autopilotOff(pl);
                pl.status &= ~Obj.THRUSTING;
            }
            default -> { }
        }
    }

    private void autopilotOff(Player pl) {
        if ((pl.used & Has.AUTOPILOT) != 0) {
            sim.devices.autopilot(pl, false);
        }
    }

    /** BEHAVIOR: true while hover-paused or on autopilot (the power and turn settings keys do nothing then). */
    /**
     * C {@code Receive_pointer_move} (netserver.c:3140): pointer control turned the ship by {@code movement} (the
     * client's horizontal mouse movement in window pixels).
     * <p>
     * BEHAVIOR: ignored in HOVERPAUSE; an engaged autopilot is switched off first; the turn speed is
     * {@code movement * turnSpeed / MAX_PLAYER_TURNSPEED}, its size limited to MIN..MAX_PLAYER_TURNSPEED with a
     * turn resistance, else to 0..5 * RES; {@code turnVel -= direction * size}.
     */
    public void pointerMove(Player pl, int movement) {
        if ((pl.status & Obj.HOVERPAUSE) != 0) {
            return;
        }
        if ((pl.used & Has.AUTOPILOT) != 0) {
            sim.devices.autopilot(pl, false);
        }
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

    static boolean hoverOrAutopilot(Player pl) {
        return (pl.status & Obj.HOVERPAUSE) != 0 || (pl.used & Has.AUTOPILOT) != 0;
    }

    /**
     * The modifier keys (event.c:623-703), each only when the rules allow it.
     * <p>
     * BEHAVIOR: TOGGLE_NUCLEAR cycles none - nuclear - full nuclear (allowNukes); TOGGLE_CLUSTER and
     * TOGGLE_IMPLOSION flip their warhead bit (allowClusters / allowModifiers); velocity, mini, spread and power
     * step 0..3 and wrap (allowModifiers); TOGGLE_LASER steps 0..2 (allowLaserModifiers); CLEAR_MODIFIERS clears
     * everything.
     */
    int toggleMods(int m, ControlKey k) {
        com.fulcrologic.jxpilot.server.world.ItemParams ip = w.items;
        switch (k) {
            case TOGGLE_NUCLEAR -> {
                if (ip.allowNukes) {
                    int n = Mods.nuclear(m);
                    m = Mods.withNuclear(m, n == Mods.NUCLEAR ? n | Mods.FULLNUCLEAR : (n == 0 ? Mods.NUCLEAR : 0));
                }
            }
            case TOGGLE_CLUSTER -> {
                if (ip.allowClusters) {
                    m ^= Obj.MOD_CLUSTER;
                }
            }
            case TOGGLE_IMPLOSION -> {
                if (ip.allowModifiers) {
                    m ^= Obj.MOD_IMPLOSION;
                }
            }
            case TOGGLE_VELOCITY -> {
                if (ip.allowModifiers) {
                    m = Mods.withVelocity(m, Mods.velocity(m) == Mods.VELOCITY_MAX ? 0 : Mods.velocity(m) + 1);
                }
            }
            case TOGGLE_MINI -> {
                if (ip.allowModifiers) {
                    m = Mods.withMini(m, Mods.mini(m) == Mods.MINI_MAX ? 0 : Mods.mini(m) + 1);
                }
            }
            case TOGGLE_SPREAD -> {
                if (ip.allowModifiers) {
                    m = Mods.withSpread(m, Mods.spread(m) == Mods.SPREAD_MAX ? 0 : Mods.spread(m) + 1);
                }
            }
            case TOGGLE_LASER -> {
                if (ip.allowLaserModifiers) {
                    m = Mods.withLaser(m, Mods.laser(m) == Mods.LASER_MAX ? 0 : Mods.laser(m) + 1);
                }
            }
            case TOGGLE_POWER -> {
                if (ip.allowModifiers) {
                    m = Mods.withPower(m, Mods.power(m) == Mods.POWER_MAX ? 0 : Mods.power(m) + 1);
                }
            }
            case CLEAR_MODIFIERS -> m = 0;
            default -> { }
        }
        return m;
    }

    /**
     * C KEY_LOAD_MODIFIERS_1..4.
     * <p>
     * BEHAVIOR: while REPROGRAM is held the current modifiers are stored in the bank, otherwise the bank is loaded
     * and filtered by the rules (C {@code filter_mods}).
     */
    void loadModifiers(Player pl, int bank) {
        if ((pl.status & Obj.REPROGRAM) != 0) {
            pl.modBank[bank] = pl.mods;
        } else {
            com.fulcrologic.jxpilot.server.world.ItemParams ip = w.items;
            pl.mods = Mods.filter(pl.modBank[bank], ip.allowNukes, ip.allowClusters, ip.allowModifiers,
                ip.allowLaserModifiers);
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
            m.append(T_HAS_CHANGED_HOME_BASE);
        } else {
            m.append(T_HAS_TAKEN_OVER).append(displaced.name).append(T_S_HOME_BASE);
        }
    }
}
