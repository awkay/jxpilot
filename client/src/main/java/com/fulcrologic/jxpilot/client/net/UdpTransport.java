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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;

/**
 * Non-blocking NIO {@link DatagramChannel} connected to the server. Connecting the channel filters
 * datagrams from other sources and lets {@code read}/{@code write} work without allocating socket
 * addresses. The server replies from the address the client contacted (see SPEC "Server network
 * binding"), which a connected socket requires.
 */
public final class UdpTransport implements Transport {
    private final DatagramChannel channel;
    private final InetSocketAddress server;
    private long portUnreachable;

    private UdpTransport(DatagramChannel channel, InetSocketAddress server) {
        this.channel = channel;
        this.server = server;
    }

    /**
     * Resolve {@code server}, open a channel of its address family, optionally bind it to {@code bind}
     * (null = any local address and port) and connect it.
     */
    public static UdpTransport open(Endpoint server, Endpoint bind) throws IOException {
        InetSocketAddress addr = new InetSocketAddress(InetAddress.getByName(server.host()), server.port());
        if (addr.isUnresolved()) {
            throw new IOException("cannot resolve " + server.host());
        }
        StandardProtocolFamily fam = addr.getAddress() instanceof java.net.Inet6Address
            ? StandardProtocolFamily.INET6 : StandardProtocolFamily.INET;
        DatagramChannel ch = DatagramChannel.open(fam);
        try {
            ch.setOption(StandardSocketOptions.SO_RCVBUF, 1 << 18);
            if (bind != null) {
                ch.bind(new InetSocketAddress(InetAddress.getByName(bind.host()), bind.port()));
            }
            ch.connect(addr);
            ch.configureBlocking(false);
        } catch (IOException | RuntimeException e) {
            ch.close();
            throw e;
        }
        return new UdpTransport(ch, addr);
    }

    /** The resolved server address. */
    public InetSocketAddress server() {
        return server;
    }

    /** Local address the channel is bound to. */
    public InetSocketAddress localAddress() {
        try {
            return (InetSocketAddress) channel.getLocalAddress();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * ICMP port-unreachable errors seen (server not running). They are rethrown: {@link ClientConnection}
     * treats every IOException as "not sent / nothing received" and pauses sending, so an outage does not
     * raise (and allocate) an exception every frame.
     */
    public long portUnreachableCount() {
        return portUnreachable;
    }

    @Override
    public boolean send(ByteBuffer packet) throws IOException {
        try {
            return channel.write(packet) > 0;
        } catch (PortUnreachableException e) {
            portUnreachable++;
            throw e;
        }
    }

    @Override
    public int receive(ByteBuffer buf) throws IOException {
        try {
            int n = channel.read(buf);
            return Math.max(n, 0);
        } catch (PortUnreachableException e) {
            portUnreachable++;
            throw e;
        }
    }

    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException ignored) {
            // closing; nothing to do
        }
    }
}
