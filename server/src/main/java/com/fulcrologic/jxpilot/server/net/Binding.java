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

import com.fulcrologic.jxpilot.common.config.Opt;
import com.fulcrologic.jxpilot.common.config.Options;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The server's sockets as its options ask for them: the bind selectors resolved against the interfaces, the
 * addresses chosen and bound, and the data to rescan them. Shared by {@link Server} and the lobby server, which
 * both listen on {@code contactPort} on the {@code bind} addresses.
 */
public final class Binding {
    private final UdpTransport transport;
    private final List<BindSelector> selectors;
    private final boolean bindWait;

    private Binding(UdpTransport transport, List<BindSelector> selectors, boolean bindWait) {
        this.transport = transport;
        this.selectors = selectors;
        this.bindWait = bindWait;
    }

    /**
     * Resolve {@code bind} (or {@code serverHost}) and open a channel per chosen address on {@code contactPort}.
     * <p>
     * BEHAVIOR: bind warnings are logged; a selector that matches nothing is an error unless bindWait, which waits
     * for an interface to appear ({@link Server.Settings#rescanMillis} apart); with bindWait and nothing bound yet
     * the transport stays open without channels for the rescans to fill.
     *
     * @throws IllegalArgumentException for a port out of range, nothing to bind, or no address that could be bound
     * @throws IOException              when the wait for an interface is interrupted
     */
    public static Binding open(Options opts, Server.Settings s) throws IOException {
        int port = opts.getInt(Opt.CONTACT_PORT);
        if (port < 0 || port > 0xFFFF) {
            throw new IllegalArgumentException("port " + port + " out of range");
        }
        List<String> warnings = new ArrayList<>();
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
        return new Binding(transport, selectors, bindWait);
    }

    /** The transport with the channels opened so far. */
    public UdpTransport transport() {
        return transport;
    }

    /** The resolved selectors. */
    List<BindSelector> selectors() {
        return selectors;
    }

    /** Whether the selectors can change what they match (interface names, CIDRs, 'all'), so they need rescans. */
    public boolean needsRescan() {
        return BindPlan.needsRescan(selectors, bindWait);
    }

    /**
     * What a rescan would change: the wanted targets now, or null when that is what is bound already (a bind that
     * failed earlier counts as a difference, so it is retried).
     */
    public List<BindTarget> rescan(Server.Settings s) throws IOException {
        List<BindTarget> now = BindPlan.choose(selectors, s.interfaces.snapshot()).targets();
        return BindPlan.diff(transport.boundTargets(), now).isEmpty() ? null : now;
    }

    /** Open, close and relabel channels so that {@code wanted} (from {@link #rescan}) is what is bound. */
    public void apply(List<BindTarget> wanted) {
        transport.apply(BindPlan.diff(transport.boundTargets(), wanted));
    }

    private static BindPlan.Choice waitForTargets(List<BindSelector> selectors, BindPlan.Choice choice,
                                                  Server.Settings s) throws IOException {
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
}
