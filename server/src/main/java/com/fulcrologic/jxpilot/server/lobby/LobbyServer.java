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
package com.fulcrologic.jxpilot.server.lobby;

import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.lobby.LobbyEntry;
import com.fulcrologic.jxpilot.common.map.BundledMaps;
import com.fulcrologic.jxpilot.common.map.MapLocator;
import com.fulcrologic.jxpilot.common.net.LobbyMsg;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.PacketHeader;
import com.fulcrologic.jxpilot.common.net.PacketType;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.server.net.Binding;
import com.fulcrologic.jxpilot.server.net.BindTarget;
import com.fulcrologic.jxpilot.server.net.DenyList;
import com.fulcrologic.jxpilot.server.net.NetLog;
import com.fulcrologic.jxpilot.server.net.NetTransport;
import com.fulcrologic.jxpilot.server.net.RateLimiter;
import com.fulcrologic.jxpilot.server.net.Server;
import com.fulcrologic.jxpilot.server.net.ServerArgs;
import com.fulcrologic.jxpilot.server.net.ServerNet;
import com.fulcrologic.jxpilot.server.net.SourceKeys;
import com.fulcrologic.jxpilot.server.net.UdpTransport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * An always-on lobby server (docs/LOBBY-SPEC.md): one UDP port, a list of maps, and a game per map started when
 * the first client asks for it and stopped when its last human leaves.
 * <p>
 * One thread owns the socket. A datagram from an address with a route goes to its map's game
 * ({@link RoutedTransport}), whose own loop thread runs the unchanged {@link ServerNet}; everything else is the
 * lobby protocol ({@link LobbyMsg}): a CONNECT from an address without a route is answered REJECT
 * {@link Msg.Reject#REASON_LOBBY}, which makes a lobby-aware client ask for the list and choose a map. A
 * choice sets the route, and the client's next CONNECT reaches the game as if it had contacted it directly.
 * Routes follow the source address and port, and expire when the address has been silent for
 * {@link #ROUTE_IDLE_NANOS}, so a client needs no extra state and a game needs no knowledge of the lobby.
 */
public final class LobbyServer implements AutoCloseable {
    /** A list a peer asked for is pushed again at most this often when it keeps asking with an old version. */
    static final long RESEND_GAP_NANOS = 200_000_000L;
    /** Housekeeping (reaping ended games, list changes, expiry) runs this often. */
    static final long TICK_NANOS = 250_000_000L;
    /** A peer that has not asked for the list for this long is forgotten (clients ask about once a second). */
    static final long PEER_IDLE_NANOS = 15_000_000_000L;
    /** A route without a datagram for this long is dropped (the game's own timeout is 10 s). */
    static final long ROUTE_IDLE_NANOS = 20_000_000_000L;
    /** Most addresses that can be on the list at once. */
    static final int MAX_PEERS = 1024;
    /** Longest the socket thread waits for a datagram. */
    private static final long POLL_NANOS = 50_000_000L;

    private static final class Route {
        final RoutedTransport target;
        final int gameId;
        long lastSeen;

        Route(RoutedTransport target, int gameId, long lastSeen) {
            this.target = target;
            this.gameId = gameId;
            this.lastSeen = lastSeen;
        }
    }

    private static final class Peer {
        final int channel;
        final SocketAddress addr;
        int sentVersion;
        long lastRequest;
        long lastSend;

        Peer(int channel, SocketAddress addr) {
            this.channel = channel;
            this.addr = addr;
        }
    }

    private final NetTransport transport;
    /** The sockets, null when the lobby runs over a transport that is not one (tests). */
    private final Binding binding;
    private final LobbyRegistry registry;
    private final String name;
    private final NetLog log;
    private final LongSupplier clock;
    private final long rescanNanos;
    private final Server.Settings settings;
    private final SourceKeys keys;
    private final RateLimiter limiter;
    private volatile DenyList deny;
    private final Thread loop;
    private final ExecutorService starter;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();
    private volatile boolean running = true;

    /** Routes and peers by the channel (local address) they arrived on; grown as channels appear. */
    private HashMap<SocketAddress, Route>[] routes = newMaps();
    private HashMap<SocketAddress, Peer>[] peers = newMaps();
    private int peerCount;
    private final PacketHeader header = new PacketHeader();
    private final ByteBuffer out = ByteBuffer.allocateDirect(Protocol.MAX_PACKET);
    private final LobbyMsg.Hello hello = new LobbyMsg.Hello();
    private final LobbyMsg.Info info = new LobbyMsg.Info();
    private final LobbyMsg.ListRequest listRequest = new LobbyMsg.ListRequest();
    private final LobbyMsg.Select select = new LobbyMsg.Select();
    private final LobbyMsg.Join join = new LobbyMsg.Join();
    private final Msg.Reject reject = new Msg.Reject();
    private long nextTick;
    private long nextRescan;
    private long malformed;
    private long rateLimited;
    private long stray;

    @SuppressWarnings("unchecked")
    private static <T> HashMap<SocketAddress, T>[] newMaps() {
        return new HashMap[8];
    }

    private LobbyServer(NetTransport transport, Binding binding, LobbyRegistry registry, ExecutorService starter,
                        String name, DenyList deny, Server.Settings settings, SourceKeys keys, RateLimiter limiter) {
        this.binding = binding;
        this.transport = transport;
        this.registry = registry;
        this.starter = starter;
        this.name = name;
        this.deny = deny;
        this.settings = settings;
        this.log = settings.log;
        this.clock = settings.clock;
        this.rescanNanos = settings.rescanMillis * 1_000_000L;
        this.keys = keys;
        this.limiter = limiter;
        this.loop = new Thread(this::runLoop, "jxpilot-lobby-loop");
    }

    // ------------------------------------------------------------------ starting

    /**
     * Whether the arguments ask for lobby mode: {@code --lobby} on the command line, or {@code lobby: yes} in the
     * positional config file.
     */
    public static boolean requested(String[] args) {
        try {
            Options o = new Options();
            List<String> rest = o.parseCommandLine(ServerArgs.joinRepeated(args, Opt.BIND));
            if (!o.getBool(Opt.LOBBY)) {
                Path config = ServerArgs.configFile(rest, o);
                if (config != null) {
                    ConfigParser.parseFile(config, OptionOrigin.DEFAULTS, o);
                }
            }
            return o.getBool(Opt.LOBBY);
        } catch (IOException | RuntimeException e) {
            return false; // the normal server reports the unreadable file
        }
    }

    /**
     * Read the options, build the catalog, bind and start serving.
     * <p>
     * Options come from the command line, the defaults file, the positional config file and the lobby config
     * ({@code lobbyConfig}); {@code map} is ignored. Each game reads the same sources again with its own section
     * of the lobby config and its map.
     *
     * @throws IllegalArgumentException for bad arguments, no maps to offer or a failed bind
     * @throws IOException              for an unreadable config or defaults file
     */
    public static LobbyServer start(String[] args, Server.Settings s) throws IOException {
        Options probe = new Options();
        List<String> rest = probe.parseCommandLine(ServerArgs.joinRepeated(args, Opt.BIND));
        Path positional = ServerArgs.configFile(rest, probe);
        if (positional != null) {
            ConfigParser.parseFile(positional, OptionOrigin.DEFAULTS, probe);
        }
        String lobbyConfig = probe.getString(Opt.LOBBY_CONFIG);
        LobbyConfig config = lobbyConfig == null || lobbyConfig.isEmpty() ? LobbyConfig.EMPTY
            : LobbyConfig.read(Path.of(lobbyConfig), w -> s.log.log("lobby config warning: " + w));
        Options base = Server.readOptions(args, new LobbyOptionSources(config, null, MapLocator.resolve(MapLocator.DEFAULT_MAP)));
        for (String w : base.warnings()) {
            s.log.log("option warning: " + w);
        }
        if (probe.isSet(Opt.MAP_FILE_NAME)) {
            s.log.log("lobby: the map option is ignored in lobby mode (the lobby offers maps; the clients choose)");
        }
        List<Path> dirs = new ArrayList<>();
        for (String d : base.getList(Opt.LOBBY_MAPS_DIR)) {
            dirs.add(Path.of(d));
        }
        LobbyCatalog catalog = LobbyCatalog.build(base.getBool(Opt.LOBBY_BUNDLED) ? BundledMaps.standard() : null, dirs,
            base.getList(Opt.LOBBY_EXCLUDE), w -> s.log.log("lobby: " + w));
        if (catalog.offers().isEmpty()) {
            throw new IllegalArgumentException("the lobby has no maps to offer (lobbyBundled, lobbyMapsDir, lobbyExclude)");
        }
        for (String section : config.sectionNames()) {
            boolean known = false;
            for (LobbyCatalog.Offer o : catalog.offers()) {
                known |= o.name().equalsIgnoreCase(section);
            }
            if (!known) {
                s.log.log("lobby config warning: section [" + section + "] names no map on offer");
            }
        }
        Binding binding = Binding.open(base, s);
        ExecutorService starter = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "jxpilot-lobby-starter");
            t.setDaemon(true);
            return t;
        });
        LobbyRegistry registry = new LobbyRegistry(catalog, gameLauncher(args, config, s), binding.transport(),
            starter, s.log);
        String lobbyName = base.getString(Opt.LOBBY_NAME);
        SecureRandom random = new SecureRandom();
        LobbyServer server = new LobbyServer(binding.transport(), binding, registry, starter, lobbyName,
            DenyList.parse(base.getString(Opt.DENY_HOSTS)), s, new SourceKeys(random.nextLong()),
            new RateLimiter(ServerNet.SOURCE_SLOTS, Math.max(1, base.getDouble(Opt.IP_PACKET_RATE)),
                Math.max(1, base.getDouble(Opt.IP_PACKET_BURST))));
        binding.transport().setReceiver(server::onDatagram);
        s.log.log("lobby '" + lobbyName + "' offering " + catalog.offers().size() + " maps");
        server.loop.start();
        return server;
    }

    private static LobbyRegistry.Launcher gameLauncher(String[] args, LobbyConfig config, Server.Settings lobby) {
        return (offer, transport) -> {
            Options opts = Server.readOptions(args, new LobbyOptionSources(config, offer.name(), offer.source()));
            Server.Settings child = new Server.Settings();
            child.interfaces = lobby.interfaces;
            child.resolver = lobby.resolver;
            child.clock = lobby.clock;
            child.rescanMillis = lobby.rescanMillis;
            child.factory = lobby.factory;
            child.log = line -> lobby.log.log("[" + offer.name() + "] " + line);
            Server server = Server.startHosted(opts, child, transport);
            return new LobbyRegistry.Game() {
                @Override
                public int humans() {
                    return server.humans();
                }

                @Override
                public boolean running() {
                    return !server.termination().isDone();
                }

                @Override
                public void close() {
                    try {
                        server.close();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
        };
    }

    /**
     * A lobby over any transport, registry and name, with no thread started: tests feed datagrams to
     * {@link #onDatagram} and call {@link #tick}.
     */
    static LobbyServer over(NetTransport transport, LobbyRegistry registry, String name, Server.Settings settings,
                            long keySecret) {
        LobbyServer s = new LobbyServer(transport, null, registry, null, name, DenyList.NONE, settings,
            new SourceKeys(keySecret), new RateLimiter(ServerNet.SOURCE_SLOTS, 400, 800));
        transport.setReceiver(s::onDatagram);
        return s;
    }

    // ------------------------------------------------------------------ the socket thread

    private void runLoop() {
        Throwable failure = null;
        try {
            long now = clock.getAsLong();
            nextTick = now + TICK_NANOS;
            nextRescan = now + rescanNanos;
            while (running) {
                transport.poll(POLL_NANOS);
                now = clock.getAsLong();
                if (now - nextTick >= 0) {
                    tick(now);
                    nextTick = now + TICK_NANOS;
                }
                if (binding != null && binding.needsRescan() && now - nextRescan >= 0) {
                    nextRescan = now + rescanNanos;
                    List<BindTarget> wanted = binding.rescan(settings);
                    if (wanted != null) {
                        binding.apply(wanted);
                    }
                }
            }
        } catch (Throwable e) {
            failure = e;
            log.log("lobby loop failed: " + e);
        } finally {
            running = false;
            if (failure == null) {
                terminated.complete(null);
            } else {
                terminated.completeExceptionally(failure);
            }
        }
    }

    /**
     * Housekeeping.
     * <p>
     * BEHAVIOR: routes to games that ended are dropped; the list is refreshed and, when it changed, sent to every
     * peer that does not have it; peers silent for {@link #PEER_IDLE_NANOS} and routes silent for
     * {@link #ROUTE_IDLE_NANOS} are forgotten.
     */
    void tick(long now) {
        List<Integer> ended = registry.reap();
        if (!ended.isEmpty()) {
            for (HashMap<SocketAddress, Route> m : routes) {
                if (m != null) {
                    m.values().removeIf(r -> ended.contains(r.gameId));
                }
            }
        }
        registry.refresh();
        int version = registry.version();
        for (HashMap<SocketAddress, Route> m : routes) {
            if (m != null) {
                m.values().removeIf(r -> now - r.lastSeen > ROUTE_IDLE_NANOS);
            }
        }
        for (HashMap<SocketAddress, Peer> m : peers) {
            if (m == null) {
                continue;
            }
            for (var it = m.values().iterator(); it.hasNext(); ) {
                Peer p = it.next();
                if (now - p.lastRequest > PEER_IDLE_NANOS) {
                    it.remove();
                    peerCount--;
                } else if (p.sentVersion != version) {
                    sendList(p, now);
                }
            }
        }
    }

    // ------------------------------------------------------------------ one datagram

    /**
     * Handle one datagram from the socket.
     * <p>
     * BEHAVIOR: a datagram from an address with a route, other than the lobby's own message types, goes to that
     * route's game untouched and refreshes the route.
     * BEHAVIOR: for everything else, a source in denyHosts is ignored, and a source over its packet budget is
     * dropped (counted in {@link #rateLimited()}).
     * BEHAVIOR: a CONNECT is answered REJECT {@link Msg.Reject#REASON_LOBBY}, its text cut so the answer is no
     * larger than the CONNECT; a CONNECT of another protocol version is answered REJECT VERSION.
     * BEHAVIOR: HELLO (at least {@link LobbyMsg#MIN_HELLO_SIZE} bytes) is answered INFO with the sender's cookie.
     * LIST_REQUEST, SELECT and anything else must carry the cookie of the sender's address, or they are dropped
     * (counted in {@link #stray()}).
     * BEHAVIOR: an address with a route that sends a valid LIST_REQUEST or SELECT is back in the lobby: its route
     * is dropped first.
     * BEHAVIOR: LIST_REQUEST makes the sender a peer and gets the list when its version is not the current one;
     * SELECT is answered with JOIN, and on OK the address is routed to the map's game.
     */
    void onDatagram(int channel, SocketAddress from, ByteBuffer data, long now) {
        int start = data.position();
        Route route = routes(channel).get(from);
        boolean valid = header.read(data);
        int type = valid ? header.type() : -1;
        if (route != null && !isLobbyType(type)) {
            data.position(start);
            route.lastSeen = now;
            route.target.offer(channel, from, data, now);
            return;
        }
        if (deny.denied(from)) {
            return;
        }
        if (!limiter.allow(keys.of(from), 1, now)) {
            rateLimited++;
            return;
        }
        try {
            if (!valid) {
                data.position(start);
                if (ServerNet.isOtherVersionConnect(data) && data.remaining() >= REJECT_OVERHEAD) {
                    sendReject(channel, from, data.getInt(start + PacketHeader.SIZE), Msg.Reject.REASON_VERSION,
                        "incompatible version", data.remaining());
                } else {
                    malformed++;
                }
                return;
            }
            int size = data.limit() - start;
            switch (type) {
                case PacketType.CONNECT -> onConnect(channel, from, data, size);
                case PacketType.LOBBY_HELLO -> onHello(channel, from, data, size);
                case PacketType.LOBBY_LIST_REQUEST -> {
                    listRequest.read(data);
                    if (verified(from, listRequest.cookie)) {
                        onListRequest(channel, from, now);
                    }
                }
                case PacketType.LOBBY_SELECT -> {
                    select.read(data);
                    if (verified(from, select.cookie)) {
                        onSelect(channel, from, now);
                    }
                }
                default -> stray++;
            }
        } catch (RuntimeException e) {
            malformed++;
        }
    }

    private static boolean isLobbyType(int type) {
        return type == PacketType.LOBBY_HELLO || type == PacketType.LOBBY_LIST_REQUEST
            || type == PacketType.LOBBY_SELECT;
    }

    /** REJECT bytes before its text: header, u32 nonce, u8 reason, u8 text length. */
    private static final int REJECT_OVERHEAD = PacketHeader.SIZE + 6;

    private boolean verified(SocketAddress from, long cookie) {
        if (cookie == keys.of(from)) {
            return true;
        }
        stray++;
        return false;
    }

    private void onConnect(int channel, SocketAddress from, ByteBuffer data, int size) {
        if (size < REJECT_OVERHEAD) {
            malformed++;
            return;
        }
        sendReject(channel, from, data.getInt(data.position()), Msg.Reject.REASON_LOBBY,
            "lobby: choose a map (this server needs a lobby-aware client)", size);
    }

    private void onHello(int channel, SocketAddress from, ByteBuffer data, int size) {
        if (size < LobbyMsg.MIN_HELLO_SIZE) {
            malformed++;
            return;
        }
        hello.read(data);
        info.nonce = hello.nonce;
        info.cookie = keys.of(from);
        info.name.setLength(0);
        info.name.append(name);
        begin(PacketType.LOBBY_INFO);
        info.write(out);
        transmit(channel, from);
    }

    private void onListRequest(int channel, SocketAddress from, long now) {
        routes(channel).remove(from); // back in the lobby
        HashMap<SocketAddress, Peer> m = peers(channel);
        Peer p = m.get(from);
        if (p == null) {
            if (peerCount >= MAX_PEERS) {
                return;
            }
            p = new Peer(channel, from);
            m.put(from, p);
            peerCount++;
        }
        p.lastRequest = now;
        int version = registry.version();
        if (listRequest.haveVersion == version) {
            p.sentVersion = version;
        } else if (now - p.lastSend >= RESEND_GAP_NANOS || p.lastSend == 0) {
            sendList(p, now);
        }
    }

    private void onSelect(int channel, SocketAddress from, long now) {
        routes(channel).remove(from);
        LobbyRegistry.Selection sel = registry.select(select.mapId);
        join.mapId = select.mapId;
        join.result = sel.result();
        join.text.setLength(0);
        join.text.append(sel.text());
        begin(PacketType.LOBBY_JOIN);
        join.write(out);
        transmit(channel, from);
        if (sel.result() == LobbyMsg.JOIN_OK) {
            routes(channel).put(from, new Route(sel.target(), sel.gameId(), now));
            Peer gone = peers(channel).remove(from);
            if (gone != null) {
                peerCount--;
            }
            log.log("lobby: " + from + " joins " + registry.catalog().offer(sel.gameId()).name());
        }
    }

    private void sendList(Peer p, long now) {
        int version = registry.version();
        List<LobbyEntry> list = registry.current();
        for (ByteBuffer page : LobbyMsg.ListPage.encode(version, list, LobbyMsg.MAX_PAGE_BODY)) {
            begin(PacketType.LOBBY_LIST);
            out.put(page);
            transmit(p.channel, p.addr);
        }
        p.sentVersion = version;
        p.lastSend = now;
    }

    private void sendReject(int channel, SocketAddress to, int nonce, int reason, String text, int maxBytes) {
        out.clear();
        PacketHeader.write(out, PacketType.REJECT, 0, 0);
        reject.nonce = nonce;
        reject.reason = reason;
        reject.text.setLength(0);
        reject.text.append(text);
        if (reject.text.length() > maxBytes - REJECT_OVERHEAD) {
            reject.text.setLength(Math.max(0, maxBytes - REJECT_OVERHEAD));
        }
        reject.write(out);
        out.flip();
        transport.send(channel, to, out);
    }

    private void begin(int type) {
        out.clear();
        PacketHeader.write(out, type, 0, 0);
    }

    private void transmit(int channel, SocketAddress to) {
        out.flip();
        transport.send(channel, to, out);
    }

    private HashMap<SocketAddress, Route> routes(int channel) {
        if (channel >= routes.length) {
            routes = java.util.Arrays.copyOf(routes, Math.max(channel + 1, routes.length * 2));
        }
        if (routes[channel] == null) {
            routes[channel] = new HashMap<>();
        }
        return routes[channel];
    }

    private HashMap<SocketAddress, Peer> peers(int channel) {
        if (channel >= peers.length) {
            peers = java.util.Arrays.copyOf(peers, Math.max(channel + 1, peers.length * 2));
        }
        if (peers[channel] == null) {
            peers[channel] = new HashMap<>();
        }
        return peers[channel];
    }

    // ------------------------------------------------------------------ inspection and stopping

    /** The registry (games, list). */
    public LobbyRegistry registry() {
        return registry;
    }

    /** Addresses that sent the lobby something it could not use (bad header, bad body). */
    public long malformed() {
        return malformed;
    }

    /** Datagrams dropped by the per-source budget. */
    public long rateLimited() {
        return rateLimited;
    }

    /** Lobby datagrams dropped for a missing or wrong cookie, and other types nobody asked for. */
    public long stray() {
        return stray;
    }

    /** The first bound address with its real port (when the port option was 0), or null. */
    public InetSocketAddress localAddress() {
        List<InetSocketAddress> all = localAddresses();
        return all.isEmpty() ? null : all.get(0);
    }

    /** Every address the lobby listens on, with real ports. */
    public List<InetSocketAddress> localAddresses() {
        List<InetSocketAddress> out = new ArrayList<>();
        if (binding != null) {
            UdpTransport udp = binding.transport();
            for (BindTarget t : udp.boundTargets()) {
                out.add(udp.localAddress(t));
            }
        }
        return out;
    }

    /** Completes when the socket thread has stopped (normally after {@link #close}, exceptionally if it died). */
    public CompletableFuture<Void> termination() {
        return terminated;
    }

    /**
     * Stop the lobby: the socket thread, then every game (its players get DISCONNECT), then the socket.
     * Idempotent.
     */
    @Override
    public void close() throws InterruptedException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        running = false;
        transport.wakeup();
        if (loop.isAlive()) {
            loop.join();
        }
        registry.closeAll();
        if (starter != null) {
            starter.shutdown();
        }
        transport.close();
    }
}
