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
package com.fulcrologic.jxpilot.common;

/**
 * XPilot sound events: ordinal and {@link #cName()} equal the C {@code *_SOUND} value and its
 * {@code soundNames} entry in {@code src/common/audio.h}. The server queues them per player with a volume
 * ({@link #MIN_VOLUME}..{@link #MAX_VOLUME}, C {@code saudio.c}); the wire carries the ordinal (the native
 * SNAPSHOT SOUND section and C's {@code PKT_AUDIO} alike).
 */
public enum SoundEvent {
    START("start"),
    FIRE_SHOT("fire_shot"),
    FIRE_TORPEDO("fire_torpedo"),
    FIRE_HEAT_SHOT("fire_heat_shot"),
    FIRE_SMART_SHOT("fire_smart_shot"),
    PLAYER_EXPLOSION("player_explosion"),
    PLAYER_HIT_PLAYER("player_hit_player"),
    PLAYER_HIT_CANNON("player_hit_cannon"),
    PLAYER_HIT_MINE("player_hit_mine"),
    PLAYER_EAT_TORPEDO_SHOT("player_eat_torpedo_shot"),
    PLAYER_EAT_HEAT_SHOT("player_eat_heat_shot"),
    PLAYER_EAT_SMART_SHOT("player_eat_smart_shot"),
    DROP_MINE("drop_mine"),
    PLAYER_HIT_WALL("player_hit_wall"),
    WORM_HOLE("worm_hole"),
    WIDEANGLE_SHOT_PICKUP("wideangle_shot_pickup"),
    SENSOR_PACK_PICKUP("sensor_pack_pickup"),
    BACK_SHOT_PICKUP("back_shot_pickup"),
    ROCKET_PACK_PICKUP("smart_shot_pickup"),
    CLOAKING_DEVICE_PICKUP("cloaking_device_pickup"),
    ENERGY_PACK_PICKUP("energy_pack_pickup"),
    MINE_PACK_PICKUP("mine_pack_pickup"),
    REFUEL("refuel"),
    THRUST("thrust"),
    CLOAK("cloak"),
    CHANGE_HOME("change_home"),
    ECM_PICKUP("ecm_pickup"),
    AFTERBURNER_PICKUP("afterburner_pickup"),
    TANK_PICKUP("tank_pickup"),
    DROP_MOVING_MINE("drop_moving_mine"),
    MINE_EXPLOSION("mine_explosion"),
    ECM("ecm"),
    TANK_DETACH("tank_detach"),
    CANNON_FIRE("cannon_fire"),
    PLAYER_SHOT_THEMSELF("player_shot_themself"),
    DECLARE_WAR("declare_war"),
    PLAYER_HIT_CANNONFIRE("player_hit_cannonfire"),
    OBJECT_EXPLOSION("object_explosion"),
    PLAYER_EAT_SHOT("player_eat_shot"),
    TRANSPORTER_PICKUP("transporter_pickup"),
    TRANSPORTER_SUCCESS("transporter_success"),
    TRANSPORTER_FAIL("transporter_fail"),
    DEFLECTOR_PICKUP("deflector_pickup"),
    DEFLECTOR("deflector"),
    HYPERJUMP_PICKUP("hyperjump_pickup"),
    HYPERJUMP("hyperjump"),
    PHASING_DEVICE_PICKUP("phasing_device_pickup"),
    PHASING_ON("phasing_on"),
    PHASING_OFF("phasing_off"),
    MIRROR_PICKUP("mirror_pickup"),
    ARMOR_PICKUP("armor_pickup"),
    NUKE_LAUNCH("nuke_launch"),
    NUKE_EXPLOSION("nuke_explosion"),
    PLAYER_RAN_OVER_PLAYER("player_ran_over_player"),
    LASER_PICKUP("laser_pickup"),
    EMERGENCY_THRUST_PICKUP("emergency_thrust_pickup"),
    AUTOPILOT_PICKUP("autopilot_pickup"),
    TRACTOR_BEAM_PICKUP("tractor_beam_pickup"),
    PLAYER_BOUNCED("player_bounced"),
    FIRE_LASER("fire_laser"),
    AUTOPILOT_ON("autopilot_on"),
    AUTOPILOT_OFF("autopilot_off"),
    EMERGENCY_THRUST_ON("emergency_thrust_on"),
    EMERGENCY_THRUST_OFF("emergency_thrust_off"),
    TRACTOR_BEAM("tractor_beam"),
    PRESSOR_BEAM("pressor_beam"),
    CONNECT_BALL("connect_ball"),
    DROP_BALL("drop_ball"),
    EXPLODE_BALL("explode_ball"),
    DESTROY_BALL("destroy_ball"),
    DESTROY_TARGET("destroy_target"),
    TEAM_WIN("team_win"),
    TEAM_DRAW("team_draw"),
    PLAYER_WIN("player_win"),
    PLAYER_DRAW("player_draw"),
    PLAYER_ROASTED("player_roasted"),
    PLAYER_EAT_LASER("player_eat_laser"),
    EMERGENCY_SHIELD_PICKUP("emergency_shield_pickup"),
    EMERGENCY_SHIELD_ON("emergency_shield_on"),
    EMERGENCY_SHIELD_OFF("emergency_shield_off"),
    CANNON_EXPLOSION("cannon_explosion"),
    ASTEROID_HIT("asteroid_hit"),
    ASTEROID_BREAK("asteroid_break");

    /** Number of sound events (C {@code MAX_SOUNDS}). */
    public static final int COUNT = 83;
    /** Loudest volume (C {@code SOUND_MAX_VOLUME}): a sound at the listener, or for one player or everyone. */
    public static final int MAX_VOLUME = 100;
    /** Quietest volume of a sound in range (C {@code SOUND_MIN_VOLUME}). */
    public static final int MIN_VOLUME = 10;

    private static final SoundEvent[] VALUES = values();

    private final String cName;

    SoundEvent(String cName) {
        this.cName = cName;
    }

    /** The C {@code soundNames} entry (also the event name in sound map files). */
    public String cName() {
        return cName;
    }

    /** BEHAVIOR: the event for a wire id, or null outside 0..{@link #COUNT}-1. */
    public static SoundEvent of(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : null;
    }

    /** BEHAVIOR: the event with the C name, or null. */
    public static SoundEvent byName(String name) {
        for (SoundEvent e : VALUES) {
            if (e.cName.equals(name)) {
                return e;
            }
        }
        return null;
    }
}
