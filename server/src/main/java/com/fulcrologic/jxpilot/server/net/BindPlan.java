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

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure binding decisions: which concrete local addresses the {@code bind} selectors mean for a given
 * interface snapshot, what changed since the last rescan, and whether startup may proceed.
 * Nothing here touches the network; {@link InterfaceSource} and {@link Resolver} are passed in.
 */
public final class BindPlan {
    private BindPlan() {}

    /** Outcome of {@link #choose}. */
    public record Choice(List<BindTarget> targets, List<BindSelector> unmatched, List<String> warnings) {
        public Choice {
            targets = List.copyOf(targets);
            unmatched = List.copyOf(unmatched);
            warnings = List.copyOf(warnings);
        }
    }

    /** Channels to open and to close after a rescan. */
    public record Diff(List<BindTarget> open, List<BindTarget> close) {
        public Diff {
            open = List.copyOf(open);
            close = List.copyOf(close);
        }

        public boolean isEmpty() { return open.isEmpty() && close.isEmpty(); }
    }

    /** What startup should do, see {@link #decide}. */
    public enum Startup { PROCEED, WAIT, FAIL }

    /** Orders IPv4 before IPv6, then by address bytes, so logs and channel order are stable. */
    static final Comparator<BindTarget> ORDER = Comparator
        .comparingInt((BindTarget t) -> t.address() instanceof Inet4Address ? 0 : 1)
        .thenComparing((a, b) -> Arrays.compareUnsigned(a.address().getAddress(), b.address().getAddress()));

    /**
     * Startup resolution of names: run once, before the first {@link #choose}.
     * <p>
     * BEHAVIOR: a {@link BindSelector.Name} that is an interface in the snapshot becomes an
     * {@link BindSelector.Interface}; otherwise, if it resolves, a {@link BindSelector.Host} with its
     * addresses; otherwise an Interface (one that may appear later), with a warning.
     * BEHAVIOR: an explicit {@link BindSelector.Host} gets its resolved addresses (empty if it does not
     * resolve, so it matches nothing). Other selectors are returned unchanged, in order.
     */
    public static List<BindSelector> resolveNames(List<BindSelector> selectors, List<NetIf> snapshot,
                                                  Resolver resolver, List<String> warnings) {
        List<BindSelector> out = new ArrayList<>(selectors.size());
        for (BindSelector s : selectors) {
            switch (s) {
                case BindSelector.Name n -> {
                    if (hasInterface(snapshot, n.name())) {
                        out.add(new BindSelector.Interface(n.name()));
                    } else {
                        List<InetAddress> addrs = resolver.resolve(n.name());
                        if (!addrs.isEmpty()) {
                            out.add(new BindSelector.Host(n.name(), addrs));
                        } else {
                            warnings.add("bind '" + n.name() + "' is neither a current interface nor a resolvable "
                                + "host name; treating it as an interface name");
                            out.add(new BindSelector.Interface(n.name()));
                        }
                    }
                }
                case BindSelector.Host h -> out.add(new BindSelector.Host(h.name(), resolver.resolve(h.name())));
                default -> out.add(s);
            }
        }
        return out;
    }

    /**
     * Concrete addresses for the selectors on this snapshot.
     * <p>
     * BEHAVIOR: only up interfaces count. {@code all} takes every address except IPv6 link-local;
     * {@code loopback} takes loopback addresses (interface flag or address) except link-local; an
     * interface selector takes its interface's addresses except link-local; a CIDR takes every address
     * inside it (link-local included when the CIDR covers it); a literal or host takes the matching
     * local addresses.
     * BEHAVIOR: a wildcard literal yields the wildcard itself (interface {@code *}) with a warning, and
     * when present only wildcard targets are kept (a wildcard and a specific address cannot share a
     * port), with a warning naming the dropped ones.
     * BEHAVIOR: an address matched twice appears once, with the first interface carrying it; targets are
     * IPv4 first then by address bytes.
     * BEHAVIOR: a selector matching nothing is listed in {@code unmatched}.
     */
    public static Choice choose(List<BindSelector> selectors, List<NetIf> snapshot) {
        Map<InetAddress, BindTarget> found = new LinkedHashMap<>();
        List<BindSelector> unmatched = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<BindTarget> wildcards = new ArrayList<>();
        for (BindSelector s : selectors) {
            if (s instanceof BindSelector.Literal l && l.wildcard()) {
                wildcards.add(new BindTarget(l.address(), "*"));
                warnings.add("binding wildcard " + l + ": on a multi-homed host replies may leave from a "
                    + "different source address than the client contacted");
                continue;
            }
            boolean any = false;
            for (NetIf nif : snapshot) {
                if (!nif.up()) {
                    continue;
                }
                for (InetAddress a : nif.addresses()) {
                    if (matches(s, nif, a)) {
                        found.putIfAbsent(a, new BindTarget(a, nif.name()));
                        any = true;
                    }
                }
            }
            if (!any) {
                unmatched.add(s);
            }
        }
        List<BindTarget> targets;
        if (!wildcards.isEmpty()) {
            if (!found.isEmpty()) {
                warnings.add("wildcard bind replaces the specific addresses " + addresses(found.values()));
            }
            targets = new ArrayList<>(new LinkedHashSet<>(wildcards));
        } else {
            targets = new ArrayList<>(found.values());
        }
        targets.sort(ORDER);
        return new Choice(targets, unmatched, warnings);
    }

    /**
     * BEHAVIOR: whether selector {@code s} takes address {@code a} of interface {@code nif} (see
     * {@link #choose}); a wildcard literal never matches an interface address.
     */
    static boolean matches(BindSelector s, NetIf nif, InetAddress a) {
        boolean linkLocal = a instanceof Inet6Address && a.isLinkLocalAddress();
        return switch (s) {
            case BindSelector.All x -> !linkLocal;
            case BindSelector.Loopback x -> !linkLocal && (nif.loopback() || a.isLoopbackAddress());
            case BindSelector.Interface i -> !linkLocal && nif.name().equals(i.name());
            case BindSelector.Network n -> n.cidr().contains(a);
            case BindSelector.Literal l -> !l.wildcard() && l.address().equals(a);
            case BindSelector.Host h -> h.addresses().contains(a);
            case BindSelector.Name n -> false;
        };
    }

    /**
     * BEHAVIOR: {@code open} = desired targets not currently bound, {@code close} = bound targets no
     * longer desired, each in the order of its source list; equal sets give an empty diff.
     */
    public static Diff diff(List<BindTarget> current, List<BindTarget> desired) {
        Set<BindTarget> cur = new LinkedHashSet<>(current);
        Set<BindTarget> want = new LinkedHashSet<>(desired);
        List<BindTarget> open = new ArrayList<>();
        for (BindTarget t : desired) {
            if (!cur.contains(t)) {
                open.add(t);
            }
        }
        List<BindTarget> close = new ArrayList<>();
        for (BindTarget t : current) {
            if (!want.contains(t)) {
                close.add(t);
            }
        }
        return new Diff(open, close);
    }

    /**
     * BEHAVIOR: FAIL when a selector matched nothing and {@code bindWait} is off; WAIT when nothing at all
     * was matched and {@code bindWait} is on; PROCEED otherwise (with bindWait, unmatched selectors bind
     * later through rescans).
     */
    public static Startup decide(Choice c, boolean bindWait) {
        if (!bindWait) {
            return c.unmatched().isEmpty() ? Startup.PROCEED : Startup.FAIL;
        }
        return c.targets().isEmpty() ? Startup.WAIT : Startup.PROCEED;
    }

    /** The startup error for {@link Startup#FAIL}, naming the selectors that matched nothing. */
    public static String failureMessage(Choice c) {
        return "bind selector(s) " + c.unmatched() + " match no local address (use bindWait to wait for them)";
    }

    private static boolean hasInterface(List<NetIf> snapshot, String name) {
        for (NetIf n : snapshot) {
            if (n.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> addresses(Iterable<BindTarget> ts) {
        List<String> out = new ArrayList<>();
        for (BindTarget t : ts) {
            out.add(t.address().getHostAddress());
        }
        return out;
    }
}
