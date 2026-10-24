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
import com.fulcrologic.jxpilot.common.map.BundledMaps;
import com.fulcrologic.jxpilot.common.net.LobbyMsg;
import com.fulcrologic.jxpilot.server.net.NetTransport;
import com.fulcrologic.jxpilot.server.net.UdpTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LobbyRegistry: start on demand, join, stop when empty")
class LobbyRegistryTest {
    static final class FakeGame implements LobbyRegistry.Game {
        final LobbyCatalog.Offer offer;
        final RoutedTransport transport;
        volatile int humans;
        volatile boolean running = true;
        int closes;

        FakeGame(LobbyCatalog.Offer offer, RoutedTransport transport) {
            this.offer = offer;
            this.transport = transport;
        }

        @Override
        public int humans() { return humans; }

        @Override
        public boolean running() { return running; }

        @Override
        public void close() {
            closes++;
            running = false;
        }
    }

    static final class Nowhere implements NetTransport {
        @Override
        public void setReceiver(UdpTransport.Receiver r) { }

        @Override
        public void poll(long timeoutNanos) { }

        @Override
        public void wakeup() { }

        @Override
        public void close() { }

        @Override
        public void send(int channel, SocketAddress to, ByteBuffer data) { }
    }

    LobbyCatalog catalog;
    LobbyRegistry registry;
    final List<FakeGame> started = new ArrayList<>();
    final List<String> log = new ArrayList<>();
    String failWith;
    final List<Runnable> pending = new ArrayList<>();
    boolean inline = true;

    int id(String name) {
        return catalog.offers().stream().filter(o -> o.name().equals(name)).findFirst().orElseThrow().id();
    }

    @BeforeEach
    void setUp() {
        catalog = LobbyCatalog.build(BundledMaps.standard(), List.of(), Set.of(), w -> { });
        registry = new LobbyRegistry(catalog, (offer, t) -> {
            if (failWith != null) {
                throw new IllegalStateException(failWith);
            }
            FakeGame g = new FakeGame(offer, t);
            started.add(g);
            return g;
        }, new Nowhere(), r -> {
            if (inline) {
                r.run();
            } else {
                pending.add(r);
            }
        }, log::add);
    }

    @Nested
    @DisplayName("list")
    class ListBuilding {
        @Test
        @DisplayName("with nothing running every map is idle with 0 humans, in name order")
        void idle() {
            List<LobbyEntry> l = registry.list();
            assertAll(
                () -> assertEquals(catalog.offers().size(), l.size(), "every map is listed"),
                () -> assertTrue(l.stream().noneMatch(LobbyEntry::active), "none active"),
                () -> assertTrue(l.stream().allMatch(e -> e.humans() == 0), "no humans"),
                () -> assertEquals(l.stream().map(LobbyEntry::name).sorted(String.CASE_INSENSITIVE_ORDER).toList(),
                    l.stream().map(LobbyEntry::name).toList(), "alphabetical"));
        }

        @Test
        @DisplayName("a running map is active with its humans and moves to the front; two running are alphabetical")
        void activeFirst() {
            registry.select(id("tourmination"));
            registry.select(id("doggy"));
            started.get(0).humans = 3;
            started.get(1).humans = 1;
            List<LobbyEntry> l = registry.list();
            assertAll(
                () -> assertEquals(List.of("doggy", "tourmination"), l.subList(0, 2).stream().map(LobbyEntry::name).toList(),
                    "the two running maps lead, alphabetical"),
                () -> assertTrue(l.get(0).active() && l.get(1).active(), "both active"),
                () -> assertEquals(1, l.get(0).humans(), "doggy has 1 human"),
                () -> assertEquals(3, l.get(1).humans(), "tourmination has 3 humans"),
                () -> assertTrue(l.subList(2, l.size()).stream().noneMatch(LobbyEntry::active), "the rest are idle"));
        }
    }

    @Nested
    @DisplayName("version")
    class Versions {
        @Test
        @DisplayName("starts at 1; refresh changes it only when something visible changed")
        void bumps() {
            int v0 = registry.version();
            boolean same = registry.refresh();
            registry.select(id("doggy"));
            boolean started1 = registry.refresh();
            int v1 = registry.version();
            boolean again = registry.refresh();
            started.get(0).humans = 2;
            boolean humans = registry.refresh();
            int v2 = registry.version();
            started.get(0).running = false;
            registry.reap();
            boolean ended = registry.refresh();
            assertAll(
                () -> assertEquals(1, v0, "the first version is 1 (0 means 'none' on the wire)"),
                () -> assertFalse(same, "nothing changed: same version"),
                () -> assertTrue(started1, "a map becoming active changes the list"),
                () -> assertEquals(v0 + 1, v1, "by one"),
                () -> assertFalse(again, "the same state again does not"),
                () -> assertTrue(humans, "a human joining changes it"),
                () -> assertEquals(v1 + 1, v2, "by one"),
                () -> assertTrue(ended, "a map becoming idle changes it"),
                () -> assertEquals(v2 + 1, registry.version(), "by one"));
        }

        @Test
        @DisplayName("current() is the list as of the last refresh, not the live one")
        void currentIsSnapshot() {
            registry.select(id("doggy"));
            assertTrue(registry.current().stream().noneMatch(LobbyEntry::active), "not refreshed yet: still idle");
            registry.refresh();
            assertTrue(registry.current().get(0).active(), "after refresh doggy leads and is active");
        }
    }

    @Nested
    @DisplayName("select")
    class Select {
        @Test
        @DisplayName("an unknown map id is UNKNOWN_MAP and starts nothing")
        void unknown() {
            LobbyRegistry.Selection s = registry.select(60000);
            assertAll(
                () -> assertEquals(LobbyMsg.JOIN_UNKNOWN_MAP, s.result(), "result"),
                () -> assertNull(s.target(), "no target"),
                () -> assertTrue(started.isEmpty(), "nothing started"));
        }

        @Test
        @DisplayName("an idle map is started and answered STARTING; the next select joins that game")
        void startOnDemand() {
            inline = false;
            LobbyRegistry.Selection first = registry.select(id("doggy"));
            boolean startedBeforeRun = !started.isEmpty();
            pending.forEach(Runnable::run);
            LobbyRegistry.Selection second = registry.select(id("doggy"));
            assertAll(
                () -> assertEquals(LobbyMsg.JOIN_STARTING, first.result(), "first answer: starting"),
                () -> assertFalse(startedBeforeRun, "the start runs on the starter, not in the select call"),
                () -> assertEquals(1, started.size(), "one game started"),
                () -> assertEquals(id("doggy"), started.get(0).offer.id(), "the right map"),
                () -> assertEquals(LobbyMsg.JOIN_OK, second.result(), "then OK"),
                () -> assertSame(started.get(0).transport, second.target(), "routed to the started game"),
                () -> assertEquals(id("doggy"), second.gameId(), "and says which map"));
        }

        @Test
        @DisplayName("asking while the game is still starting starts nothing more")
        void startingTwice() {
            inline = false;
            registry.select(id("doggy"));
            LobbyRegistry.Selection again = registry.select(id("doggy"));
            assertAll(
                () -> assertEquals(LobbyMsg.JOIN_STARTING, again.result(), "still starting"),
                () -> assertEquals(1, pending.size(), "only one start was queued"));
        }

        @Test
        @DisplayName("a running map is joined without starting another game, by any number of clients")
        void joinRunning() {
            registry.select(id("doggy"));
            LobbyRegistry.Selection a = registry.select(id("doggy"));
            LobbyRegistry.Selection b = registry.select(id("doggy"));
            assertAll(
                () -> assertEquals(1, started.size(), "still one game"),
                () -> assertSame(a.target(), b.target(), "both go to the same game"),
                () -> assertEquals(1, registry.runningCount(), "one running"));
        }

        @Test
        @DisplayName("different maps run side by side as separate games")
        void twoMaps() {
            registry.select(id("doggy"));
            registry.select(id("tourmination"));
            assertAll(
                () -> assertEquals(2, started.size(), "two games"),
                () -> assertTrue(registry.select(id("doggy")).target() != registry.select(id("tourmination")).target(),
                    "with their own transports"));
        }

        @Test
        @DisplayName("a game that fails to start answers FAILED once with the reason; the next select tries again")
        void failure() {
            failWith = "no memory";
            registry.select(id("doggy"));
            LobbyRegistry.Selection failed = registry.select(id("doggy"));
            failWith = null;
            LobbyRegistry.Selection retry = registry.select(id("doggy"));
            LobbyRegistry.Selection ok = registry.select(id("doggy"));
            assertAll(
                () -> assertEquals(LobbyMsg.JOIN_FAILED, failed.result(), "failed"),
                () -> assertEquals("no memory", failed.text(), "with the reason"),
                () -> assertEquals(LobbyMsg.JOIN_STARTING, retry.result(), "then a new attempt"),
                () -> assertEquals(LobbyMsg.JOIN_OK, ok.result(), "which works"),
                () -> assertTrue(log.stream().anyMatch(l -> l.contains("cannot start doggy")), "and was logged"));
        }
    }

    @Nested
    @DisplayName("stop when the last human leaves")
    class Stopping {
        @Test
        @DisplayName("an ended game is reaped, reported, closed and its map is idle; the next select starts a fresh game")
        void reapAndRestart() {
            registry.select(id("doggy"));
            FakeGame first = started.get(0);
            first.running = false; // the game's loop quit: its last human left
            List<Integer> ended = registry.reap();
            LobbyRegistry.Selection again = registry.select(id("doggy"));
            LobbyRegistry.Selection joined = registry.select(id("doggy"));
            assertAll(
                () -> assertEquals(List.of(id("doggy")), ended, "the ended map is reported"),
                () -> assertEquals(1, first.closes, "the game was closed"),
                () -> assertEquals(1, registry.runningCount(), "only the new game runs"),
                () -> assertEquals(2, started.size(), "a new game was started"),
                () -> assertTrue(started.get(1) != first, "a different instance"),
                () -> assertEquals(LobbyMsg.JOIN_STARTING, again.result(), "the select that restarts it says starting"),
                () -> assertSame(started.get(1).transport, joined.target(), "and the next one joins the fresh game"),
                () -> assertTrue(log.stream().anyMatch(l -> l.contains("doggy ended")), "logged"));
        }

        @Test
        @DisplayName("a select that arrives after a game ended but before the next reap starts a fresh game, never routes to the dead one")
        void selectOnEndedGame() {
            registry.select(id("doggy"));
            FakeGame dead = started.get(0);
            dead.running = false;
            LobbyRegistry.Selection s = registry.select(id("doggy"));
            LobbyRegistry.Selection joined = registry.select(id("doggy"));
            assertAll(
                () -> assertEquals(LobbyMsg.JOIN_STARTING, s.result(), "a new game is being started"),
                () -> assertEquals(1, dead.closes, "the dead game was closed"),
                () -> assertEquals(2, started.size(), "a second game was started"),
                () -> assertSame(started.get(1).transport, joined.target(), "and that is the one joined"),
                () -> assertTrue(joined.target() != dead.transport, "not the dead game's"),
                () -> assertTrue(registry.reap().isEmpty(), "nothing left for reap to remove"));
        }

        @Test
        @DisplayName("running games are not touched by reap; only the ended one goes")
        void reapSelective() {
            registry.select(id("doggy"));
            registry.select(id("tourmination"));
            started.get(0).running = false;
            List<Integer> ended = registry.reap();
            assertAll(
                () -> assertEquals(List.of(started.get(0).offer.id()), ended, "just the first"),
                () -> assertEquals(1, registry.runningCount(), "the other still runs"),
                () -> assertEquals(0, started.get(1).closes, "and is not closed"));
        }

        @Test
        @DisplayName("reap with nothing ended reports nothing")
        void reapNothing() {
            registry.select(id("doggy"));
            assertTrue(registry.reap().isEmpty(), "nothing ended");
        }

        @Test
        @DisplayName("closeAll stops every game and refuses to start more; a start in progress is stopped when it finishes")
        void closeAll() {
            registry.select(id("doggy"));
            inline = false;
            registry.select(id("tourmination"));
            registry.closeAll();
            pending.forEach(Runnable::run);
            LobbyRegistry.Selection after = registry.select(id("default"));
            assertAll(
                () -> assertEquals(1, started.get(0).closes, "the running game was closed"),
                () -> assertEquals(2, started.size(), "the second start did run"),
                () -> assertEquals(1, started.get(1).closes, "and its game was closed as soon as it was up"),
                () -> assertEquals(0, registry.runningCount(), "nothing runs"),
                () -> assertEquals(LobbyMsg.JOIN_STARTING, after.result(), "no new game is started after close"),
                () -> assertEquals(2, started.size(), "(still two)"));
        }
    }

    @Test
    @DisplayName("the catalog is the one given")
    void catalog() {
        assertNotNull(registry.catalog().offer(0), "offers exist");
        assertSame(catalog, registry.catalog(), "same object");
    }
}
