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
package com.fulcrologic.jxpilot.common.net;

/**
 * Snapshot section types and their record sizes (bytes). A snapshot is a header followed by
 * sections {@code u8 type, u8 recordSize, u8 variant, u16 count, count * recordSize bytes}. Readers use the
 * transmitted record size as stride, so fields may be appended to a record later without breaking
 * older readers, and unknown sections are skipped. Record layouts are in {@link Records}.
 */
public final class Section {
    private Section() {}

    // ---- M1
    /** {@link Records.Self}: full-precision state of the receiving player's ship (0 or 1 record). */
    public static final int SELF = 1;
    /** {@link Records.Ship}: other visible ships (own ship may be repeated here too). */
    public static final int SHIP = 2;
    /** {@link Records.Shot}: bullets; variant = {@code kind << 4 | colour}. */
    public static final int SHOT = 3;
    /**
     * {@link Records.Burst}: spark/debris spawn events (seeded; the client expands them with
     * {@link com.fulcrologic.jxpilot.common.math.DebrisBurst}). Replaces per-particle records.
     */
    public static final int BURST = 4;
    /** {@link Records.CannonState}: visible cannons (alive/dead...). */
    public static final int CANNON = 5;
    /** {@link Records.FuelState}: visible fuel stations' fuel levels. */
    public static final int FUEL = 6;
    /** {@link Records.TargetState}: visible targets. */
    public static final int TARGET = 7;
    /**
     * {@link Records.Radar}: radar blips for the whole world (not view culled). Variant 0 for most blips; a section
     * of asteroid blips carries their size as its variant (1..15; C {@code Frame_radar} draws size + 1 pixels).
     */
    public static final int RADAR = 8;

    // ---- M2
    /** {@link Records.ItemObj}: floating items. */
    public static final int ITEM = 9;
    /** {@link Records.Mine}. */
    public static final int MINE = 10;
    /** {@link Records.Missile}: smart/heat/torpedo/nuke. */
    public static final int MISSILE = 11;
    /** {@link Records.Ball}. */
    public static final int BALL = 12;
    /** {@link Records.Laser}: laser pulses. */
    public static final int LASER = 13;
    /** {@link Records.Asteroid}. */
    public static final int ASTEROID = 14;
    /** {@link Records.Wreckage}. */
    public static final int WRECKAGE = 15;
    /** {@link Records.Ecm}: ECM flashes. */
    public static final int ECM = 16;
    /** {@link Records.Connector}: ball connectors and tractor/pressor beams. */
    public static final int CONNECTOR = 17;
    /** {@link Records.Transporter}: transporter beams. */
    public static final int TRANSPORTER = 18;
    /** {@link Records.SelfItems}: item counts of the receiving player (1 record). */
    public static final int SELF_ITEMS = 19;
    /** {@link Records.TreasureState}: treasure ball presence. */
    public static final int TREASURE = 20;
    /** {@link Records.TempWormhole}: hyperjump wormholes. */
    public static final int TEMP_WORMHOLE = 21;

    /** {@link Records.Timers}: the receiving player's HUD timers (1 record). */
    public static final int TIMERS = 22;
    /**
     * {@link Records.Predict}: the receiving player's own ship as the ship kernel sees it (protocol v2, owner only,
     * not while watching another player): the {@link com.fulcrologic.jxpilot.common.sim.ShipBody} core block, the
     * applied input's hash and the rare and kernel versions (0 or 1 record).
     */
    public static final int PREDICT = 23;
    /**
     * {@link Records.PredictRare}: the ShipBody rare block with its version and the frame offset (sent when it
     * changed lately).
     */
    public static final int PREDICT_RARE = 24;
    /** {@link Records.PredictKeys}: the ShipBody key masks, when they differ from the applied input. */
    public static final int PREDICT_KEYS = 25;
    /** {@link Records.PredictRound}: the own ship's roundDelay, with PREDICT when nonzero. */
    public static final int PREDICT_ROUND = 27;
    /**
     * {@link Records.Sound}: sound events the receiving player hears, with their volume (C
     * {@code sound_play_queued}: {@code PKT_AUDIO} after the frame's objects). Not view culled.
     */
    public static final int SOUND = 37;
    /** Highest assigned section type. */
    public static final int MAX_TYPE = 37;

    /** Bytes of the section header. */
    public static final int HEADER_SIZE = 5;

    private static final int[] SIZES = new int[256];

    static {
        SIZES[SELF] = Records.Self.SIZE;
        SIZES[SHIP] = Records.Ship.SIZE;
        SIZES[SHOT] = Records.Shot.SIZE;
        SIZES[BURST] = Records.Burst.SIZE;
        SIZES[CANNON] = Records.CannonState.SIZE;
        SIZES[FUEL] = Records.FuelState.SIZE;
        SIZES[TARGET] = Records.TargetState.SIZE;
        SIZES[RADAR] = Records.Radar.SIZE;
        SIZES[ITEM] = Records.ItemObj.SIZE;
        SIZES[MINE] = Records.Mine.SIZE;
        SIZES[MISSILE] = Records.Missile.SIZE;
        SIZES[BALL] = Records.Ball.SIZE;
        SIZES[LASER] = Records.Laser.SIZE;
        SIZES[ASTEROID] = Records.Asteroid.SIZE;
        SIZES[WRECKAGE] = Records.Wreckage.SIZE;
        SIZES[ECM] = Records.Ecm.SIZE;
        SIZES[CONNECTOR] = Records.Connector.SIZE;
        SIZES[TRANSPORTER] = Records.Transporter.SIZE;
        SIZES[SELF_ITEMS] = Records.SelfItems.SIZE;
        SIZES[TREASURE] = Records.TreasureState.SIZE;
        SIZES[TEMP_WORMHOLE] = Records.TempWormhole.SIZE;
        SIZES[TIMERS] = Records.Timers.SIZE;
        SIZES[SOUND] = Records.Sound.SIZE;
        SIZES[PREDICT] = Records.Predict.SIZE;
        SIZES[PREDICT_RARE] = Records.PredictRare.SIZE;
        SIZES[PREDICT_KEYS] = Records.PredictKeys.SIZE;
        SIZES[PREDICT_ROUND] = Records.PredictRound.SIZE;
    }

    /** Record size this implementation writes for a type, 0 if unknown. */
    public static int recordSize(int type) {
        return type >= 0 && type < 256 ? SIZES[type] : 0;
    }
}
