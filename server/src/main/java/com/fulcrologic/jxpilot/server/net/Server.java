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
package com.fulcrologic.jxpilot.server.net;

import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.MapLocator;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.net.ConfigTransfer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * A running server: options, map, bound channels, the loop thread (ticking at the map's
 * framesPerSecond, one tick per C frame) and the interface rescan
 * thread. Thin orchestration over tested parts: {@link BindPlan}, {@link ServerNet},
 * {@link TickScheduler}, {@link UdpTransport}.
 */
public final class Server implements AutoCloseable {
    /** Effectful collaborators and tunables; defaults are the real ones. */
    public static final class Settings {
        public InterfaceSource interfaces = new JdkInterfaceSource();
        public Resolver resolver = new JdkResolver();
        public NetLog log = System.err::println;
        public LongSupplier clock = System::nanoTime;
        public long rescanMillis = 3000;
        /** The real simulation ({@link com.fulcrologic.jxpilot.server.host.SimGameHost}); tests may use the stub. */
        public GameHostFactory factory = new com.fulcrologic.jxpilot.server.host.SimGameHostFactory();
    }

    private final Settings s;
    private final UdpTransport transport;
    private final ServerNet net;
    private final int hz;
    private final List<BindSelector> selectors;
    private final AtomicReference<List<BindTarget>> desired = new AtomicReference<>();
    private final Thread loop;
    private final Thread rescan;
    private volatile boolean running = true;
    /** C {@code NoQuit}: keep running when the last human has left. */
    private final boolean noQuit;
    /** Loop start ({@link Settings#clock}), for the no-show timeout. */
    private long startNanos;
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

    private Server(Settings s, UdpTransport transport, ServerNet net, int hz, List<BindSelector> selectors,
                   boolean rescans, boolean noQuit) {
        this.s = s;
        this.noQuit = noQuit;
        this.hz = hz;
        this.transport = transport;
        this.net = net;
        this.selectors = selectors;
        this.loop = new Thread(this::runLoop, "jxpilot-server-loop");
        this.rescan = rescans ? new Thread(this::runRescan, "jxpilot-bind-rescan") : null;
    }

    /**
     * Parse {@code [--map NAME|FILE] [--port N] [--bind sel ...] [--bindWait] [config file]} (plus any
     * XPilot option), load the map, bind, and start the loop.
     * Sources are read in C {@code Parser}'s order: command line, the defaults file ({@link DefaultsFile}:
     * XPilot's lib/defaults unless {@code --defaults FILE|none}), the optional positional config file (also
     * origin DEFAULTS, so it beats lib/defaults), then the map. Precedence is C's: command line, then
     * {@code \override:} defaults, then the map, then the defaults.
     *
     * @throws IllegalArgumentException for bad arguments, a missing map or a failed bind
     * @throws IOException              for an unreadable defaults, config or map file
     */
    public static Server start(String[] args, Settings s) throws IOException {
        Options opts = readOptions(args);
        for (String w : opts.warnings()) {
            s.log.log("option warning: " + w);
        }
        WorldMap map = WorldMap.fromOptions(opts);
        int port = opts.getInt(Opt.CONTACT_PORT);
        if (port < 0 || port > 0xFFFF) {
            throw new IllegalArgumentException("port " + port + " out of range");
        }
        ConfigTransfer.Blob blob = ConfigTransfer.encode(opts.toConfigText(ClientMap.of(opts.mapData(), opts)));

        List<String> warnings = new java.util.ArrayList<>();
        List<BindSelector> selectors = BindPlan.resolveNames(
            BindSelector.parseAll(ServerArgs.bindList(opts)), s.interfaces.snapshot(), s.resolver, warnings);
        BindPlan.Choice choice = BindPlan.choose(selectors, s.interfaces.snapshot());
        warnings.addAll(choice.warnings());
        for (String w : warnings) {
            s.log.log("bind warning: " + w);
        }
        boolean bindWait = opts.getBool(Opt.BIND_WAIT);
        switch (BindPlan.decide(choice, bindWait)) {
            case FAIL -> throw new IllegalArgumentException(BindPlan.failureMessage(choice));
            case WAIT -> choice = waitForTargets(selectors, choice, s);
            case PROCEED -> {
                if (!choice.unmatched().isEmpty()) {
                    s.log.log("bindWait: " + choice.unmatched() + " match nothing yet; will bind when they appear");
                }
            }
        }

        UdpTransport transport = new UdpTransport(port, s.log, s.clock);
        if (transport.apply(BindPlan.diff(List.of(), choice.targets())) == 0 && !bindWait) {
            transport.close();
            throw new IllegalArgumentException("could not bind any address");
        }
        GameHost host = s.factory.create(opts, map);
        ServerNetConfig cfg = ServerNetConfig.of(opts, map);
        ServerNet net = new ServerNet(cfg, host, transport, s.log, blob);
        transport.setReceiver(net::onDatagram);
        boolean rescans = BindPlan.needsRescan(selectors, bindWait);
        Server server = new Server(s, transport, net, cfg.simHz, selectors, rescans, opts.getBool(Opt.NO_QUIT));
        s.log.log("serving map '" + map.name() + "' (" + map.width() + "x" + map.height() + " blocks) at "
            + cfg.simHz + " frames/s");
        server.loop.start();
        if (server.rescan != null) {
            server.rescan.setDaemon(true);
            server.rescan.start();
        }
        return server;
    }

    /**
     * Every option source of {@link #start}, in C {@code Parser}'s order (edge: reads files).
     * <p>
     * BEHAVIOR: command line, then the defaults file ({@link DefaultsFile#apply}), then the positional config
     * file (origin DEFAULTS), then the password file named by {@code passwordFileName} (origin PASSWORD, C
     * {@code parsePasswordFile}; none without the option), then the map (origin MAP), then the {@code expand}
     * list; precedence per {@link Options#setValue}.
     * BEHAVIOR: the map is {@code mapFileName} (from {@code --map} or the config file) as {@link MapLocator} resolves
     * it: a bundled map by name, else a file. Without one it is {@link MapLocator#DEFAULT_MAP} (C's default map,
     * cmdline.c {@code Conf_default_map()}).
     *
     * @throws IllegalArgumentException for two config files (unknown options are warnings)
     * @throws java.io.FileNotFoundException when the map is neither bundled nor a file (the message lists the
     *                                       bundled maps)
     * @throws IOException when a named password file cannot be read (as a named defaults file)
     */
    public static Options readOptions(String[] args) throws IOException {
        Options opts = new Options();
        List<String> rest = opts.parseCommandLine(ServerArgs.joinRepeated(args, Opt.BIND));
        Path config = ServerArgs.configFile(rest, opts);
        DefaultsFile.apply(opts);
        if (config != null) {
            ConfigParser.parseFile(config, OptionOrigin.DEFAULTS, opts);
        }
        String passwordFile = opts.getString(Opt.PASSWORD_FILE_NAME);
        if (passwordFile != null && !passwordFile.isEmpty()) {
            try {
                ConfigParser.parseFile(Path.of(passwordFile), OptionOrigin.PASSWORD, opts);
            } catch (IOException e) {
                throw new IOException("cannot read password file " + passwordFile + " (" + e + ")", e);
            }
        }
        String mapFile = opts.getString(Opt.MAP_FILE_NAME);
        MapLocator.resolve(mapFile == null ? MapLocator.DEFAULT_MAP : mapFile).parseInto(opts);
        opts.finishParsing();
        return opts;
    }

    /** C's no-show timeout (server.c:286): 5 minutes. */
    static final long NO_SHOW_NANOS = 5L * 60 * 1_000_000_000L;

    /**
     * C {@code Main_loop}'s quit check (server.c:278-291).
     * <p>
     * BEHAVIOR: with noQuit, or while any client is connected (in the game or logging in): null (keep running).
     * BEHAVIOR: otherwise, once a human has been in the game: quit ("No human players left; quitting.").
     * BEHAVIOR: otherwise after 5 minutes without any human: quit with C's message.
     */
    static String quitReason(boolean noQuit, int connections, boolean humanEntered, long runningNanos) {
        if (noQuit || connections > 0) {
            return null;
        }
        if (humanEntered) {
            return "No human players left; quitting (start the server with -noQuit to keep it running).";
        }
        if (runningNanos > NO_SHOW_NANOS) {
            return "First player has yet to show his butt, I'm bored... Bye!";
        }
        return null;
    }

    private static BindPlan.Choice waitForTargets(List<BindSelector> selectors, BindPlan.Choice choice, Settings s)
        throws IOException {
        s.log.log("bindWait: nothing matches " + choice.unmatched() + " yet; waiting for an interface");
        while (choice.targets().isEmpty()) {
            try {
                Thread.sleep(s.rescanMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for a bind address", e);
            }
            choice = BindPlan.choose(selectors, s.interfaces.snapshot());
        }
        return choice;
    }

    private void runLoop() {
        Throwable failure = null;
        TickScheduler sched = new TickScheduler(hz, TickScheduler.catchUpTicks(hz), s.clock.getAsLong());
        startNanos = s.clock.getAsLong();
        try {
            while (running) {
                List<BindTarget> d = desired.getAndSet(null);
                if (d != null) {
                    transport.apply(BindPlan.diff(transport.boundTargets(), d));
                }
                transport.poll(sched.nanosUntilNext(s.clock.getAsLong()));
                int n = sched.due(s.clock.getAsLong());
                for (int i = 0; i < n && running; i++) {
                    net.tick(s.clock.getAsLong());
                    if (net.shutdownDue()) {
                        // C End_game: the loop stops and every peer gets DISCONNECT (shutdown)
                        s.log.log("game over: shutting down");
                        running = false;
                    }
                }
                String quit = quitReason(noQuit, net.connections(), net.humanEntered(),
                    s.clock.getAsLong() - startNanos);
                if (quit != null) {
                    s.log.log(quit);
                    running = false;
                }
            }
        } catch (Throwable e) {
            failure = e;
            s.log.log("server loop failed: " + e);
        } finally {
            try {
                net.shutdown();
                transport.close();
            } catch (RuntimeException e) {
                s.log.log("shutdown failed: " + e);
            }
            if (failure == null) {
                terminated.complete(null);
            } else {
                terminated.completeExceptionally(failure);
            }
        }
    }

    /**
     * Every rescan compares the wanted targets with what is actually bound, so a bind that failed (the
     * address was not ready yet, or the port was busy) is retried on the next rescan.
     */
    private void runRescan() {
        while (running) {
            try {
                Thread.sleep(s.rescanMillis);
                List<BindTarget> now = BindPlan.choose(selectors, s.interfaces.snapshot()).targets();
                if (!BindPlan.diff(transport.boundTargets(), now).isEmpty()) {
                    desired.set(now);
                }
            } catch (InterruptedException e) {
                return;
            } catch (IOException | RuntimeException e) {
                s.log.log("interface rescan failed: " + e);
            }
        }
    }

    /**
     * Completes when the loop thread has stopped: normally after {@link #close()} or when the server quits (the
     * last human left, or none came for 5 minutes, without -noQuit: {@link #quitReason}), exceptionally with
     * the cause when the loop died (the server then serves nothing; {@code ServerMain} exits non-zero).
     */
    public CompletableFuture<Void> termination() {
        return terminated;
    }

    /**
     * The connection layer, for diagnostics and tests. It belongs to the loop thread: read its counters
     * ({@link ServerNet#malformed()} etc.) only after {@link #close()} has returned.
     */
    public ServerNet net() {
        return net;
    }

    /** Addresses bound right now. */
    public List<BindTarget> bound() {
        return transport.boundTargets();
    }

    /** Actual socket address of a bound target (with its real port), or null. */
    public InetSocketAddress localAddress(BindTarget t) {
        return transport.localAddress(t);
    }

    /** Stop the loop (peers get DISCONNECT), close all channels and wait for the threads. Idempotent. */
    @Override
    public void close() throws InterruptedException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        running = false;
        if (rescan != null) {
            rescan.interrupt();
            rescan.join();
        }
        transport.wakeup();
        loop.join();
    }
}
