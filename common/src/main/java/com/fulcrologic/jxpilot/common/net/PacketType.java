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

/** Datagram types (byte 3 of the {@link PacketHeader}). */
public final class PacketType {
    private PacketType() {}

    /** C to S: join request ({@link Msg.Connect}); resent until answered. */
    public static final int CONNECT = 1;
    /** S to C: join accepted ({@link Msg.Accept}); header carries the session id. */
    public static final int ACCEPT = 2;
    /** S to C: join refused ({@link Msg.Reject}). */
    public static final int REJECT = 3;
    /** Either way: reliable channel frames ({@link ReliableChannel}). */
    public static final int RELIABLE = 4;
    /** C to S: control input with redundancy ({@link InputHistory}/{@link InputReader}). */
    public static final int INPUT = 5;
    /** S to C: world snapshot ({@link SnapshotWriter}/{@link SnapshotReader}). */
    public static final int SNAPSHOT = 6;
    /** Either way: leaving ({@link Msg.Disconnect}); send a few times, unreliable. */
    public static final int DISCONNECT = 7;
    /** Either way: header only, keeps the session alive and carries the reliable ack. */
    public static final int ACK = 8;
    /** Either way: RTT probe ({@link Msg.Ping}). */
    public static final int PING = 9;
    /** Either way: RTT probe reply ({@link Msg.Ping}, echoed). */
    public static final int PONG = 10;
    /** C to S: lobby hello ({@link LobbyMsg.Hello}), padded to {@link LobbyMsg#MIN_HELLO_SIZE} bytes. */
    public static final int LOBBY_HELLO = 11;
    /** S to C: lobby greeting with the address cookie ({@link LobbyMsg.Info}). */
    public static final int LOBBY_INFO = 12;
    /** C to S: map list request and keep-alive ({@link LobbyMsg.ListRequest}). */
    public static final int LOBBY_LIST_REQUEST = 13;
    /** S to C: one page of the map list ({@link LobbyMsg.ListPage}). */
    public static final int LOBBY_LIST = 14;
    /** C to S: play this map ({@link LobbyMsg.Select}). */
    public static final int LOBBY_SELECT = 15;
    /** S to C: answer to a select ({@link LobbyMsg.Join}). */
    public static final int LOBBY_JOIN = 16;

    /** Name for logs. */
    public static String name(int t) {
        return switch (t) {
            case CONNECT -> "CONNECT";
            case ACCEPT -> "ACCEPT";
            case REJECT -> "REJECT";
            case RELIABLE -> "RELIABLE";
            case INPUT -> "INPUT";
            case SNAPSHOT -> "SNAPSHOT";
            case DISCONNECT -> "DISCONNECT";
            case ACK -> "ACK";
            case PING -> "PING";
            case PONG -> "PONG";
            case LOBBY_HELLO -> "LOBBY_HELLO";
            case LOBBY_INFO -> "LOBBY_INFO";
            case LOBBY_LIST_REQUEST -> "LOBBY_LIST_REQUEST";
            case LOBBY_LIST -> "LOBBY_LIST";
            case LOBBY_SELECT -> "LOBBY_SELECT";
            case LOBBY_JOIN -> "LOBBY_JOIN";
            default -> "UNKNOWN";
        };
    }
}
