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
package com.fulcrologic.jxpilot.client.game.input;

import com.fulcrologic.jxpilot.common.net.ControlKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Every bindable key function of the client, under its X resource name ({@code keyXxx} in
 * {@code src/client/default.c}), in C's order: the 63 game keys sent to the server ({@link ControlKey}, all
 * but TALK) and the client-only actions ({@link ClientAction}). Static table, built once.
 */
public final class KeyFunctions {
    private KeyFunctions() {}

    /**
     * One function: exactly one of {@code key} / {@code action} is non-null.
     *
     * @param index    position in {@link #all()}
     * @param resource X resource name
     * @param help     one-line description (C default.c)
     */
    public record Fn(int index, String resource, ControlKey key, ClientAction action, String help) {
        /** Default keysyms (C default.c, with the JXPilot changes of {@link ClientAction}). */
        public String defaultKeys() {
            return key != null ? key.defaultKeys : action.defaultKeys;
        }
    }

    private static final List<Fn> ALL = new ArrayList<>();

    static {
        game("keyTurnLeft", ControlKey.TURN_LEFT, "Turn left (anti-clockwise).");
        game("keyTurnRight", ControlKey.TURN_RIGHT, "Turn right (clockwise).");
        game("keyThrust", ControlKey.THRUST, "Thrust.");
        game("keyShield", ControlKey.SHIELD, "Raise or toggle the shield.");
        game("keyFireShot", ControlKey.FIRE_SHOT, "Fire shot.");
        game("keyFireMissile", ControlKey.FIRE_MISSILE, "Fire smart missile.");
        game("keyFireTorpedo", ControlKey.FIRE_TORPEDO, "Fire unguided torpedo.");
        game("keyFireHeat", ControlKey.FIRE_HEAT, "Fire heatseeking missile.");
        game("keyFireLaser", ControlKey.FIRE_LASER, "Activate laser beam.");
        game("keyDropMine", ControlKey.DROP_MINE, "Drop a stationary mine.");
        game("keyDetachMine", ControlKey.DETACH_MINE, "Detach a moving mine.");
        game("keyDetonateMines", ControlKey.DETONATE_MINES, "Detonate the closest mine you have dropped or thrown.");
        game("keyLockClose", ControlKey.LOCK_CLOSE, "Lock on closest player.");
        game("keyLockNextClose", ControlKey.LOCK_NEXT_CLOSE, "Lock on next closest player.");
        game("keyLockNext", ControlKey.LOCK_NEXT, "Lock on next player.");
        game("keyLockPrev", ControlKey.LOCK_PREV, "Lock on previous player.");
        game("keyRefuel", ControlKey.REFUEL, "Refuel.");
        game("keyRepair", ControlKey.REPAIR, "Repair target.");
        game("keyCloak", ControlKey.CLOAK, "Toggle cloakdevice.");
        game("keyEcm", ControlKey.ECM, "Use ECM.");
        game("keySelfDestruct", ControlKey.SELF_DESTRUCT, "Toggle self destruct.");
        game("keyPause", ControlKey.PAUSE, "Toggle pause mode.");
        game("keySwapSettings", ControlKey.SWAP_SETTINGS, "Swap control settings.");
        game("keyChangeHome", ControlKey.CHANGE_HOME, "Change home base.");
        game("keyConnector", ControlKey.CONNECTOR, "Connect to a ball.");
        game("keyDropBall", ControlKey.DROP_BALL, "Drop a ball.");
        game("keyTankNext", ControlKey.TANK_NEXT, "Use the next tank.");
        game("keyTankPrev", ControlKey.TANK_PREV, "Use the the previous tank.");
        game("keyTankDetach", ControlKey.TANK_DETACH, "Detach the current tank.");
        game("keyIncreasePower", ControlKey.INCREASE_POWER, "Increase engine power.");
        game("keyDecreasePower", ControlKey.DECREASE_POWER, "Decrease engine power.");
        game("keyIncreaseTurnspeed", ControlKey.INCREASE_TURNSPEED, "Increase turnspeed.");
        game("keyDecreaseTurnspeed", ControlKey.DECREASE_TURNSPEED, "Decrease turnspeed.");
        game("keyTransporter", ControlKey.TRANSPORTER, "Use transporter to steal an item.");
        game("keyDeflector", ControlKey.DEFLECTOR, "Toggle deflector.");
        game("keyHyperJump", ControlKey.HYPERJUMP, "Teleport.");
        game("keyPhasing", ControlKey.PHASING, "Use phasing device.");
        game("keyToggleNuclear", ControlKey.TOGGLE_NUCLEAR, "Toggle nuclear weapon modifier.");
        game("keyToggleCluster", ControlKey.TOGGLE_CLUSTER, "Toggle cluster weapon modifier.");
        game("keyToggleImplosion", ControlKey.TOGGLE_IMPLOSION, "Toggle implosion weapon modifier.");
        game("keyToggleVelocity", ControlKey.TOGGLE_VELOCITY, "Toggle explosion velocity weapon modifier.");
        game("keyToggleMini", ControlKey.TOGGLE_MINI, "Toggle mini weapon modifier.");
        game("keyToggleSpread", ControlKey.TOGGLE_SPREAD, "Toggle spread weapon modifier.");
        game("keyTogglePower", ControlKey.TOGGLE_POWER, "Toggle power weapon modifier.");
        game("keyToggleCompass", ControlKey.TOGGLE_COMPASS, "Toggle HUD/radar compass lock.");
        game("keyToggleAutoPilot", ControlKey.TOGGLE_AUTOPILOT, "Toggle automatic pilot mode.");
        game("keyToggleLaser", ControlKey.TOGGLE_LASER, "Toggle laser modifier.");
        game("keyEmergencyThrust", ControlKey.EMERGENCY_THRUST, "Pull emergency thrust handle.");
        game("keyEmergencyShield", ControlKey.EMERGENCY_SHIELD, "Toggle emergency shield power.");
        game("keyTractorBeam", ControlKey.TRACTOR_BEAM, "Use tractor beam in attract mode.");
        game("keyPressorBeam", ControlKey.PRESSOR_BEAM, "Use tractor beam in repulse mode.");
        game("keyClearModifiers", ControlKey.CLEAR_MODIFIERS, "Clear current weapon modifiers.");
        game("keyLoadModifiers1", ControlKey.LOAD_MODIFIERS_1, "Load the weapon modifiers from bank 1.");
        game("keyLoadModifiers2", ControlKey.LOAD_MODIFIERS_2, "Load the weapon modifiers from bank 2.");
        game("keyLoadModifiers3", ControlKey.LOAD_MODIFIERS_3, "Load the weapon modifiers from bank 3.");
        game("keyLoadModifiers4", ControlKey.LOAD_MODIFIERS_4, "Load the weapon modifiers from bank 4.");
        game("keyReprogram", ControlKey.REPROGRAM, "Reprogram modifier or lock bank.");
        game("keyLoadLock1", ControlKey.LOAD_LOCK_1, "Load player lock from bank 1.");
        game("keyLoadLock2", ControlKey.LOAD_LOCK_2, "Load player lock from bank 2.");
        game("keyLoadLock3", ControlKey.LOAD_LOCK_3, "Load player lock from bank 3.");
        game("keyLoadLock4", ControlKey.LOAD_LOCK_4, "Load player lock from bank 4.");
        game("keySelectItem", ControlKey.SELECT_ITEM, "Select an item to lose.");
        game("keyLoseItem", ControlKey.LOSE_ITEM, "Lose the selected item.");
        for (ClientAction a : ClientAction.values()) {
            ALL.add(new Fn(ALL.size(), a.resource, null, a, a.help));
        }
    }

    private static void game(String resource, ControlKey key, String help) {
        ALL.add(new Fn(ALL.size(), resource, key, null, help));
    }

    /** Every function, in C's order (unmodifiable). */
    public static List<Fn> all() {
        return Collections.unmodifiableList(ALL);
    }

    /** Number of functions. */
    public static int count() {
        return ALL.size();
    }

    /**
     * BEHAVIOR: the function with this resource name; an exact match first, else one equal ignoring case
     * (X resource names are case-sensitive, but a wrong case is an easy mistake); null when none.
     */
    public static Fn byResource(String name) {
        for (Fn f : ALL) {
            if (f.resource().equals(name)) {
                return f;
            }
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (Fn f : ALL) {
            if (f.resource().toLowerCase(Locale.ROOT).equals(lower)) {
                return f;
            }
        }
        return null;
    }

    /** BEHAVIOR: the function of a game key; null for TALK (a client action). */
    public static Fn of(ControlKey key) {
        for (Fn f : ALL) {
            if (f.key() == key) {
                return f;
            }
        }
        return null;
    }

    /** BEHAVIOR: the function of a client action. */
    public static Fn of(ClientAction action) {
        return ALL.get(ALL.size() - ClientAction.values().length + action.ordinal());
    }
}
