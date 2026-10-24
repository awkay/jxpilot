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
package com.fulcrologic.jxpilot.client.net;

import com.fulcrologic.jxpilot.common.lobby.LobbyEntry;
import com.fulcrologic.jxpilot.common.lobby.LobbyListAssembler;
import com.fulcrologic.jxpilot.common.lobby.LobbyOrder;
import com.fulcrologic.jxpilot.common.net.LobbyMsg;
import com.fulcrologic.jxpilot.common.net.PacketHeader;
import com.fulcrologic.jxpilot.common.net.PacketType;
import com.fulcrologic.jxpilot.common.net.Protocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The client side of the lobby protocol ({@code docs/PROTOCOL.md} "Lobby"): after a lobby server answered the
 * CONNECT with REJECT LOBBY it greets the server, keeps the map list up to date, and asks for the map the player
 * picks. Owned by {@link ClientConnection}, which feeds it the datagrams and the clock; the screen reads
 * {@link #entries()} and calls {@link #select}.
 * <p>
 * Everything is repeated until answered, as the datagrams can be lost: HELLO every 250 ms until INFO, then
 * LIST_REQUEST every second (it doubles as keep-alive, and the server answers it), SELECT every 500 ms until JOIN.
 * Single threaded, like the connection.
 */
public final class LobbyClient {
    static final long HELLO_RESEND_NANOS = 250_000_000L;
    static final long LIST_RESEND_NANOS = 1_000_000_000L;
    static final long SELECT_RESEND_NANOS = 500_000_000L;
    /** Longest server text kept for the screen. */
    static final int MAX_TEXT = 80;

    private final Transport transport;
    private final ByteBuffer tx = ByteBuffer.allocateDirect(Protocol.MAX_PACKET);
    private final LobbyMsg.Hello hello = new LobbyMsg.Hello();
    private final LobbyMsg.Info info = new LobbyMsg.Info();
    private final LobbyMsg.ListRequest request = new LobbyMsg.ListRequest();
    private final LobbyMsg.ListPage page = new LobbyMsg.ListPage();
    private final LobbyMsg.Select select = new LobbyMsg.Select();
    private final LobbyMsg.Join join = new LobbyMsg.Join();
    private final LobbyListAssembler assembler = new LobbyListAssembler();

    private boolean greeted;
    private long cookie;
    private String serverName = "";
    private List<LobbyEntry> entries = List.of();
    private int entriesVersion;
    private int selecting = -1;
    private String selectingName = "";
    private boolean joined;
    private String message = "";
    private long lastHello;
    private long lastRequest;
    private long lastSelect;
    private long ioErrors;
    private long malformed;

    /** @param transport the connection's socket, shared with the game connection that follows */
    public LobbyClient(Transport transport) {
        this.transport = transport;
    }

    /**
     * Start (or restart) the lobby dialogue: forget everything, send HELLO.
     * <p>
     * BEHAVIOR: the list, the cookie, a pending select and the message are cleared; one HELLO with {@code nonce}
     * goes out at once.
     */
    public void begin(long now, int nonce) {
        greeted = false;
        cookie = 0;
        serverName = "";
        entries = List.of();
        entriesVersion = 0;
        selecting = -1;
        joined = false;
        message = "";
        hello.nonce = nonce;
        sendHello(now);
    }

    /**
     * The maps in display order: active first, then alphabetical ({@link LobbyOrder}); empty until the list
     * arrived. The same list object is returned until the server's list changes ({@link #version()}).
     */
    public List<LobbyEntry> entries() {
        return entries;
    }

    /** Bumped each time {@link #entries()} is replaced. */
    public int version() {
        return entriesVersion;
    }

    /** The lobby's name from the server's INFO, "" before it. */
    public String serverName() {
        return serverName;
    }

    /** What the screen should say below the list: a start in progress, a refusal, or "". */
    public String message() {
        return message;
    }

    /** The map id being asked for, or -1. */
    public int selecting() {
        return selecting;
    }

    /**
     * BEHAVIOR: shows {@code text} below the list until the next choice (the game refused the player after the
     * lobby routed it: full, locked, name taken ...). Call it after {@link #begin}.
     */
    public void notice(String text) {
        message = text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
    }

    /** BEHAVIOR: true once, after the server answered a select with OK (the caller then connects). */
    public boolean takeJoined() {
        boolean j = joined;
        joined = false;
        return j;
    }

    /** Whether the server has answered the greeting (the list may still be on its way). */
    public boolean greeted() {
        return greeted;
    }

    /** Datagrams that could not be decoded. */
    public long malformed() {
        return malformed;
    }

    /** Transport errors on send. */
    public long ioErrors() {
        return ioErrors;
    }

    /**
     * The player picked a map.
     * <p>
     * BEHAVIOR: ignored (false) before the server answered the greeting, or while another select is waiting for
     * its answer; otherwise sends SELECT now and every 500 ms until JOIN, and clears the message.
     */
    public boolean select(int mapId, String name, long now) {
        if (!greeted || selecting >= 0) {
            return false;
        }
        selecting = mapId;
        selectingName = name;
        message = "Asking for " + name + "...";
        sendSelect(now);
        return true;
    }

    /**
     * Resend what is due.
     * <p>
     * BEHAVIOR: before INFO: HELLO every 250 ms. After it: SELECT every 500 ms while one is pending, else
     * LIST_REQUEST every second (never both: a LIST_REQUEST from an address the server already routes would send
     * the player back to the lobby).
     */
    public void tick(long now) {
        if (!greeted) {
            if (now - lastHello >= HELLO_RESEND_NANOS) {
                sendHello(now);
            }
        } else if (selecting >= 0) {
            if (now - lastSelect >= SELECT_RESEND_NANOS) {
                sendSelect(now);
            }
        } else if (now - lastRequest >= LIST_RESEND_NANOS) {
            sendRequest(now);
        }
    }

    /**
     * One lobby datagram (header already read).
     * <p>
     * BEHAVIOR: INFO with the HELLO's nonce stores the cookie and name and asks for the list at once; any other
     * INFO is ignored. LIST pages (after INFO) feed the assembler; a completed list replaces {@link #entries()}
     * (sorted) and is confirmed to the server at once. JOIN for the pending select: OK sets
     * {@link #takeJoined()}; STARTING shows the start in progress; any other result shows the server's reason and
     * allows another choice. Anything that does not decode counts as malformed.
     *
     * @return true when the datagram was a valid lobby datagram of this dialogue (it proves the server is there)
     */
    public boolean onPacket(int type, ByteBuffer body, long now) {
        try {
            switch (type) {
                case PacketType.LOBBY_INFO -> {
                    info.read(body);
                    if (info.nonce != hello.nonce) {
                        return false;
                    }
                    greeted = true;
                    cookie = info.cookie;
                    serverName = info.name.toString();
                    sendRequest(now);
                    return true;
                }
                case PacketType.LOBBY_LIST -> {
                    if (!greeted) {
                        return false;
                    }
                    page.read(body);
                    if (assembler.accept(page.version, page.page, page.pageCount, page.entries)) {
                        List<LobbyEntry> sorted = new ArrayList<>(assembler.entries());
                        LobbyOrder.sort(sorted);
                        entries = List.copyOf(sorted);
                        entriesVersion++;
                        sendRequest(now);
                    }
                    return true;
                }
                case PacketType.LOBBY_JOIN -> {
                    if (!greeted) {
                        return false;
                    }
                    join.read(body);
                    if (selecting < 0 || join.mapId != selecting) {
                        return true;
                    }
                    onJoin();
                    return true;
                }
                default -> {
                    return false;
                }
            }
        } catch (RuntimeException e) {
            malformed++;
            return false;
        }
    }

    private void onJoin() {
        switch (join.result) {
            case LobbyMsg.JOIN_OK -> {
                joined = true;
                selecting = -1;
                message = "Joining " + selectingName + "...";
            }
            case LobbyMsg.JOIN_STARTING -> message = "Starting " + selectingName + "...";
            default -> {
                selecting = -1;
                String why = join.text.length() > MAX_TEXT ? join.text.substring(0, MAX_TEXT) : join.text.toString();
                message = switch (join.result) {
                    case LobbyMsg.JOIN_UNKNOWN_MAP -> "The server has no map " + selectingName + ".";
                    default -> "Cannot start " + selectingName + (why.isEmpty() ? "." : ": " + why);
                };
            }
        }
    }

    // ------------------------------------------------------------------ sending

    private void sendHello(long now) {
        lastHello = now;
        begin(PacketType.LOBBY_HELLO);
        hello.write(tx);
        transmit();
    }

    private void sendRequest(long now) {
        lastRequest = now;
        request.cookie = cookie;
        request.haveVersion = assembler.version();
        begin(PacketType.LOBBY_LIST_REQUEST);
        request.write(tx);
        transmit();
    }

    private void sendSelect(long now) {
        lastSelect = now;
        select.cookie = cookie;
        select.mapId = selecting;
        begin(PacketType.LOBBY_SELECT);
        select.write(tx);
        transmit();
    }

    private void begin(int type) {
        tx.clear();
        PacketHeader.write(tx, type, 0, 0);
    }

    private void transmit() {
        tx.flip();
        try {
            transport.send(tx);
        } catch (IOException e) {
            ioErrors++;
        }
    }
}
