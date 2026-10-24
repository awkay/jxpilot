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
import com.fulcrologic.jxpilot.common.sim.Mods;
import com.fulcrologic.jxpilot.common.sim.Obj;
import com.fulcrologic.jxpilot.server.world.Player;
import com.fulcrologic.jxpilot.server.world.World;
import com.fulcrologic.jxpilot.common.sim.ShipKeys;

/**
 * Control input: C {@code Handle_keyboard} ({@code src/server/event.c}). The key-edge loop and the flight keys
 * are the ship kernel's ({@link ShipKeys}); this class handles every other key when the kernel passes it on
 * ({@link ShipEffects#keyPressed}, {@link ShipEffects#keyReleased}).
 */
public final class Keys {

    // ---- message texts as fields: resolved when the class loads, never inside a tick (no allocation)
    private static final String T_HAS_CHANGED_HOME_BASE = Texts.of(" has changed home base.");
    private static final String T_HAS_TAKEN_OVER = Texts.of(" has taken over ");
    private static final String T_S_HOME_BASE = Texts.of("'s home base.");

    private final World w;
    private final Simulation sim;

    public Keys(World w, Simulation sim) {
        this.w = w;
        this.sim = sim;
    }

    /**
     * Handle the key changes between the last handled mask and {@code keys} ({@link ShipKeys#apply}: the kernel
     * handles the flight keys, {@link #pressedOther}/{@link #releasedOther} the rest), then remember {@code keys}.
     */
    public void apply(Player pl, long keys) {
        sim.kernel.keys.apply(pl, keys, sim.effects);
    }

    /** A key press ({@link ShipKeys#pressed}); returns true when C stops handling keys for this frame. */
    boolean pressed(Player pl, ControlKey k) {
        return sim.kernel.keys.pressed(pl, k, sim.effects);
    }

    /** A key release ({@link ShipKeys#released}). */
    void released(Player pl, ControlKey k) {
        sim.kernel.keys.released(pl, k, sim.effects);
    }

    /**
     * Press actions of the keys the kernel does not handle (its {@link ShipKeys#pressed} handles shield, fire, turn,
     * thrust and the power and turn settings); returns true when C stops handling keys for this frame (an unpause).
     * <p>
     * BEHAVIOR: REFUEL picks the nearest station; SELF_DESTRUCT toggles a 150-frame countdown;
     * CHANGE_HOME takes over the base the ship is on; PAUSE toggles pause ({@link #pauseKey}); DROP_BALL drops
     * every ball; REPAIR picks the nearest own target ({@link Targets#startRepair}); CONNECTOR uses the
     * connector (if the ship has one).
     */
    boolean pressedOther(Player pl, ControlKey k) {
        switch (k) {
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
     * Release actions of the keys the kernel does not handle (its {@link ShipKeys#released} handles turn, shield,
     * fire and thrust).
     * <p>
     * BEHAVIOR: REFUEL stops refuelling; REPAIR and CONNECTOR stop; tractor/pressor beams and the laser stop;
     * REPROGRAM ends; SELECT_ITEM marks the key up.
     */
    void releasedOther(Player pl, ControlKey k) {
        switch (k) {
            case REFUEL -> pl.used &= ~Has.REFUEL;
            case REPAIR -> pl.used &= ~Has.REPAIR;
            case CONNECTOR -> pl.used &= ~Has.CONNECTOR;
            case TRACTOR_BEAM, PRESSOR_BEAM -> pl.used &= ~Has.TRACTOR_BEAM;
            case FIRE_LASER -> pl.used &= ~Has.LASER;
            case REPROGRAM -> pl.status &= ~Obj.REPROGRAM;
            case SELECT_ITEM -> pl.loseItemState = 1;
            default -> { }
        }
    }

    private void autopilotOff(Player pl) {
        if ((pl.used & Has.AUTOPILOT) != 0) {
            sim.devices.autopilot(pl, false);
        }
    }

    /** C {@code Receive_pointer_move}: pointer control turned the ship ({@link ShipKeys#pointerMove}). */
    public void pointerMove(Player pl, int movement) {
        sim.kernel.keys.pointerMove(pl, movement, sim.effects);
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
