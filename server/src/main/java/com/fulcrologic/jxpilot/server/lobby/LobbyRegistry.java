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

import com.fulcrologic.jxpilot.common.lobby.LobbyEntry;
import com.fulcrologic.jxpilot.common.lobby.LobbyOrder;
import com.fulcrologic.jxpilot.common.net.LobbyMsg;
import com.fulcrologic.jxpilot.server.net.NetLog;
import com.fulcrologic.jxpilot.server.net.NetTransport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Which maps are running: starts a map's game when a client asks for it and it is not running, lets later clients
 * join it, notices when a game has ended (its last human left) and describes all maps for the list.
 * <p>
 * The games themselves come from a {@link Launcher}, so this class is plain bookkeeping: it is tested with fake
 * games, and the real ones are {@link com.fulcrologic.jxpilot.server.net.Server}s. Starting a game takes a while
 * (the map is loaded and encoded), so it runs on {@code starter}, never on the lobby's socket thread.
 */
public final class LobbyRegistry {
    /** One running game of a map. */
    public interface Game {
        /** Human players in the game now. */
        int humans();

        /** False once the game ended by itself (its last human left, or it failed) or was closed. */
        boolean running();

        /** Stop the game and wait for it. Idempotent. */
        void close();
    }

    /** Starts the game of a map; it gets its datagrams from {@code transport}. */
    @FunctionalInterface
    public interface Launcher {
        Game start(LobbyCatalog.Offer offer, RoutedTransport transport) throws Exception;
    }

    /**
     * The answer to a select.
     *
     * @param result one of {@code LobbyMsg.JOIN_*}
     * @param text   why, for a failure
     * @param target where the client's datagrams must go, for {@link LobbyMsg#JOIN_OK}
     * @param gameId which map the target belongs to
     */
    public record Selection(int result, String text, RoutedTransport target, int gameId) { }

    private record Running(Game game, RoutedTransport transport) { }

    private final LobbyCatalog catalog;
    private final Launcher launcher;
    private final NetTransport shared;
    private final Executor starter;
    private final NetLog log;
    private final Map<Integer, Running> running = new HashMap<>();
    private final Set<Integer> starting = new HashSet<>();
    private final Map<Integer, String> failures = new HashMap<>();
    private List<LobbyEntry> current;
    private int version = 1;
    private boolean closed;

    /**
     * @param shared  the lobby's socket, where every game sends its replies
     * @param starter runs the game starts (a single thread in production; tests run them inline)
     */
    public LobbyRegistry(LobbyCatalog catalog, Launcher launcher, NetTransport shared, Executor starter, NetLog log) {
        this.catalog = catalog;
        this.launcher = launcher;
        this.shared = shared;
        this.starter = starter;
        this.log = log;
        this.current = describe();
    }

    /** The catalog this registry serves. */
    public LobbyCatalog catalog() {
        return catalog;
    }

    /**
     * The list as clients see it.
     * <p>
     * BEHAVIOR: one entry per offered map, active (a game is running) with its human count, or idle with 0; in
     * {@link LobbyOrder}: active maps first, then by name.
     */
    public synchronized List<LobbyEntry> list() {
        return describe();
    }

    private List<LobbyEntry> describe() {
        List<LobbyEntry> out = new ArrayList<>(catalog.offers().size());
        for (LobbyCatalog.Offer o : catalog.offers()) {
            Running r = running.get(o.id());
            out.add(r == null ? o.info() : o.info().withState(true, r.game().humans()));
        }
        LobbyOrder.sort(out);
        return out;
    }

    /**
     * Recompute the list and bump {@link #version()} when anything clients can see changed.
     * <p>
     * BEHAVIOR: a map becoming active or idle, or a change of its human count, changes the list; the same
     * state twice does not.
     *
     * @return whether the version changed
     */
    public synchronized boolean refresh() {
        List<LobbyEntry> now = describe();
        if (now.equals(current)) {
            return false;
        }
        current = now;
        version++;
        return true;
    }

    /** The version of the list as of the last {@link #refresh()}; starts at 1, so 0 can mean "none". */
    public synchronized int version() {
        return version;
    }

    /** The list as of the last {@link #refresh()}. */
    public synchronized List<LobbyEntry> current() {
        return current;
    }

    /**
     * A client chose a map.
     * <p>
     * BEHAVIOR: a map id the catalog does not have is {@link LobbyMsg#JOIN_UNKNOWN_MAP}.
     * BEHAVIOR: a map whose game is running is {@link LobbyMsg#JOIN_OK} with that game's transport; nothing is
     * started.
     * BEHAVIOR: an idle map's game is started (on the starter) and the answer is {@link LobbyMsg#JOIN_STARTING};
     * asking again while it starts is the same answer and starts nothing more.
     * BEHAVIOR: a game that could not be started answers {@link LobbyMsg#JOIN_FAILED} with the reason once; asking
     * again tries again.
     */
    public Selection select(int mapId) {
        LobbyCatalog.Offer offer = catalog.offer(mapId);
        if (offer == null) {
            return new Selection(LobbyMsg.JOIN_UNKNOWN_MAP, "no such map", null, mapId);
        }
        synchronized (this) {
            Running r = running.get(mapId);
            if (r != null) {
                return new Selection(LobbyMsg.JOIN_OK, "", r.transport(), mapId);
            }
            String failure = failures.remove(mapId);
            if (failure != null) {
                return new Selection(LobbyMsg.JOIN_FAILED, failure, null, mapId);
            }
            if (closed || !starting.add(mapId)) {
                return new Selection(LobbyMsg.JOIN_STARTING, "", null, mapId);
            }
        }
        log.log("lobby: starting " + offer.name());
        starter.execute(() -> start(offer));
        return new Selection(LobbyMsg.JOIN_STARTING, "", null, mapId);
    }

    private void start(LobbyCatalog.Offer offer) {
        RoutedTransport t = new RoutedTransport(shared);
        Game game = null;
        String failure = null;
        try {
            game = launcher.start(offer, t);
        } catch (Exception | Error e) {
            failure = e.getMessage() != null ? e.getMessage() : e.toString();
            log.log("lobby: cannot start " + offer.name() + ": " + failure);
        }
        boolean stopNow;
        synchronized (this) {
            starting.remove(offer.id());
            stopNow = closed && game != null;
            if (game != null && !closed) {
                running.put(offer.id(), new Running(game, t));
            } else if (game == null) {
                failures.put(offer.id(), failure);
            }
        }
        if (stopNow) {
            game.close();
        }
    }

    /**
     * Forget the games that ended.
     * <p>
     * BEHAVIOR: a game that is no longer running (its last human left) is removed, closed, and its map is idle
     * again, so the next select starts a new game; running games stay.
     *
     * @return the ids of the maps whose games were removed
     */
    public List<Integer> reap() {
        List<Integer> ended = new ArrayList<>();
        List<Running> gone = new ArrayList<>();
        synchronized (this) {
            running.entrySet().removeIf(e -> {
                if (e.getValue().game().running()) {
                    return false;
                }
                ended.add(e.getKey());
                gone.add(e.getValue());
                return true;
            });
        }
        for (int i = 0; i < gone.size(); i++) {
            gone.get(i).transport().close();
            gone.get(i).game().close();
            log.log("lobby: " + catalog.offer(ended.get(i)).name() + " ended");
        }
        return ended;
    }

    /**
     * Stop every game (the lobby is shutting down); starts still in progress stop their game when they finish.
     */
    public void closeAll() {
        List<Running> all;
        synchronized (this) {
            closed = true;
            all = new ArrayList<>(running.values());
            running.clear();
        }
        for (Running r : all) {
            r.game().close();
            r.transport().close();
        }
    }

    /** How many games run now. */
    public synchronized int runningCount() {
        return running.size();
    }
}
