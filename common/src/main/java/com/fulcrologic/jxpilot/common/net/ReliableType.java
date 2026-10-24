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
 * Reliable message types (first byte of each reliable message). Payload codecs are the nested
 * classes of {@link Msg}. Server-to-client types are below 64, client-to-server types 64 and up.
 */
public final class ReliableType {
    private ReliableType() {}

    // ---- server -> client
    /** {@link Msg.Welcome}: session parameters and the size/CRC of the config (map) data. */
    public static final int WELCOME = 1;
    /** {@link Msg.DataChunk}: a piece of the deflated config text (options + mapData). */
    public static final int DATA_CHUNK = 2;
    /** {@link Msg.PlayerJoin}: a player (or robot) is in the game; also sent for existing players. */
    public static final int PLAYER_JOIN = 3;
    /** {@link Msg.PlayerLeave}. */
    public static final int PLAYER_LEAVE = 4;
    /** {@link Msg.PlayerTeam}: team change. */
    public static final int PLAYER_TEAM = 5;
    /** {@link Msg.Score}: score/lives/status of one player. */
    public static final int SCORE = 6;
    /** {@link Msg.TeamScore}. */
    public static final int TEAM_SCORE = 7;
    /** {@link Msg.Text}: chat or server message. */
    public static final int MESSAGE = 8;
    /** {@link Msg.BaseOwner}: which player owns (spawns at) a base. */
    public static final int BASE_OWNER = 9;
    /** {@link Msg.GameEvent}: discrete events (kills, round end...) for HUD/sound. */
    public static final int GAME_EVENT = 10;
    /** {@link Msg.PlayerThrust}: a player's power/afterburners changed (client-side thrust sparks). */
    public static final int PLAYER_THRUST = 11;
    /** {@link Msg.ScoreObject}: a score popup for the receiver's HUD (C score objects). */
    public static final int SCORE_OBJECT = 12;
    /**
     * server-commands block 31-33: {@link Msg.PlayerAlliance}: a player's alliance mark as the receiver sees
     * it (C {@code Send_score}'s alliance character).
     */
    public static final int PLAYER_ALLIANCE = 31;

    // ---- client -> server
    /** Empty body: client has the config/map and is ready to play. */
    public static final int READY = 64;
    /** {@link Msg.Text} (kind/from ignored): talk line, XPilot conventions ("name:text", "team:text"). */
    public static final int CHAT = 65;
    /** {@link Msg.Settings}: power, turn speed, turn resistance (normal and alternate). */
    public static final int SETTINGS = 66;
    /** {@link Msg.ChangeTeam}. */
    public static final int CHANGE_TEAM = 67;
    /** {@link Msg.Shape}: new ship shape string. */
    public static final int SHAPE = 68;
}
