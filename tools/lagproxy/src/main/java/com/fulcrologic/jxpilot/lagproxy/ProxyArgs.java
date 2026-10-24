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
package com.fulcrologic.jxpilot.lagproxy;

import com.fulcrologic.jxpilot.common.net.link.LinkProfile;

import java.net.InetSocketAddress;

/**
 * The lag proxy's command line (see README.md). Impairment flags set both directions; prefixed with {@code up-}
 * (client to server) or {@code down-} (server to client) they set one. Pure: addresses are left unresolved.
 *
 * @param listen        where clients send to
 * @param server        the real server
 * @param up            client-to-server impairments
 * @param down          server-to-client impairments
 * @param seed          seeds both directions' random streams
 * @param statsSeconds  how often counters are printed (0: never)
 * @param idleSeconds   a client silent this long loses its upstream socket
 * @param spinMs        the proxy polls instead of sleeping this long before each delivery (0: always sleep)
 */
public record ProxyArgs(InetSocketAddress listen, InetSocketAddress server, LinkProfile up, LinkProfile down,
                        long seed, int statsSeconds, int idleSeconds, double spinMs) {
    /** Default --spin-ms: covers select's millisecond rounding and macOS timer slack. */
    public static final double DEFAULT_SPIN_MS = 2.5;
    /** Queue limit of a --rate given without one: a cap must not silently fill every slot. */
    public static final double DEFAULT_QUEUE_MS = 500;

    public static final String USAGE = String.join("\n",
        "usage: LagProxy --listen [HOST:]PORT --server HOST:PORT [impairments] [--seed N] [--stats S] [--idle S]"
            + " [--spin-ms MS]",
        "impairments (prefix up- or down- for one direction, e.g. --down-loss 2%):",
        "  --rtt MS                 one-way delay MS/2 each way (no prefix)",
        "  --delay MS               one-way delay",
        "  --jitter MS              normal jitter, standard deviation MS",
        "  --spikes P,MS[,SHAPE[,CAP]]  Pareto delay spikes on a share P of packets (scale MS, shape 1.5, cap 0 = none)",
        "  --loss P                 independent loss (0.01 or 1%)",
        "  --bursty P,LEN           Gilbert loss: long-run share P in bursts of mean LEN packets",
        "  --reorder P,MS           hold a share P of packets back by MS",
        "  --dup P                  duplicate a share P of packets",
        "  --rate BYTES[,QUEUE_MS]  bottleneck in bytes/s, drop-tail after QUEUE_MS of queue (default 500)",
        "  --no-fifo                let jitter reorder packets");

    /**
     * Parse {@code args}.
     * <p>
     * BEHAVIOR: --listen and --server are required; defaults are a perfect link, seed 1, stats every 10 s, idle
     * timeout 60 s, spin {@link #DEFAULT_SPIN_MS} ms.
     * BEHAVIOR: a direction with a rate but no queue limit gets {@link #DEFAULT_QUEUE_MS}.
     * BEHAVIOR: every impairment flag sets both directions, or one with an {@code up-}/{@code down-} prefix; later
     * flags override earlier ones; --rtt sets each direction's delay to half its value.
     * BEHAVIOR: probabilities are fractions (0.05) or percentages (5%) and must lie in [0, 1].
     * BEHAVIOR: an unknown flag, a missing value, a malformed number or address, or a missing --listen/--server
     * throws IllegalArgumentException whose message names the problem.
     */
    public static ProxyArgs parse(String[] args) {
        InetSocketAddress listen = null;
        InetSocketAddress server = null;
        LinkProfile up = new LinkProfile();
        LinkProfile down = new LinkProfile();
        long seed = 1;
        int stats = 10;
        int idle = 60;
        double spin = DEFAULT_SPIN_MS;
        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            if (!flag.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument " + flag);
            }
            String name = flag.substring(2);
            if (name.equals("no-fifo") || name.equals("up-no-fifo") || name.equals("down-no-fifo")) {
                for (LinkProfile p : targets(name, up, down)) {
                    p.fifo(false);
                }
                continue;
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("missing value for " + flag);
            }
            String v = args[++i];
            switch (name) {
                case "listen" -> listen = address(flag, v, true);
                case "server" -> server = address(flag, v, false);
                case "seed" -> seed = longValue(flag, v);
                case "stats" -> stats = nonNegative(flag, v);
                case "idle" -> idle = nonNegative(flag, v);
                case "spin-ms" -> spin = number(flag, v);
                case "rtt" -> {
                    double ms = number(flag, v);
                    up.delayMs(ms / 2);
                    down.delayMs(ms / 2);
                }
                default -> {
                    LinkProfile[] ps = targets(name, up, down);
                    String base = name.startsWith("up-") ? name.substring(3)
                        : name.startsWith("down-") ? name.substring(5) : name;
                    for (LinkProfile p : ps) {
                        impairment(p, base, flag, v);
                    }
                }
            }
        }
        if (listen == null) {
            throw new IllegalArgumentException("missing --listen");
        }
        if (server == null) {
            throw new IllegalArgumentException("missing --server");
        }
        for (LinkProfile p : new LinkProfile[] {up, down}) {
            if (p.bandwidthBytesPerSecond > 0 && p.queueLimitNanos == 0) {
                p.queueLimitNanos = Math.round(DEFAULT_QUEUE_MS * 1_000_000);
            }
        }
        return new ProxyArgs(listen, server, up, down, seed, stats, idle, spin);
    }

    private static LinkProfile[] targets(String name, LinkProfile up, LinkProfile down) {
        if (name.startsWith("up-")) {
            return new LinkProfile[] {up};
        }
        if (name.startsWith("down-")) {
            return new LinkProfile[] {down};
        }
        return new LinkProfile[] {up, down};
    }

    private static void impairment(LinkProfile p, String name, String flag, String v) {
        switch (name) {
            case "delay" -> p.delayMs(number(flag, v));
            case "jitter" -> p.normalJitterMs(number(flag, v));
            case "spikes" -> {
                String[] f = parts(flag, v, 2, 4);
                p.paretoSpikes(probability(flag, f[0]), number(flag, f[1]),
                    f.length > 2 ? number(flag, f[2]) : 1.5, f.length > 3 ? number(flag, f[3]) : 0);
            }
            case "loss" -> p.uniformLoss(probability(flag, v));
            case "bursty" -> {
                String[] f = parts(flag, v, 2, 2);
                double avg = probability(flag, f[0]);
                double len = number(flag, f[1]);
                if (avg >= 1 || len < 1) {
                    throw new IllegalArgumentException(flag + " needs a share below 1 and a burst length of at least 1");
                }
                p.burstyLoss(avg, len);
            }
            case "reorder" -> {
                String[] f = parts(flag, v, 2, 2);
                p.reorder(probability(flag, f[0]), number(flag, f[1]));
            }
            case "dup" -> p.duplicate(probability(flag, v));
            case "rate" -> {
                String[] f = parts(flag, v, 1, 2);
                p.bandwidth(longValue(flag, f[0]), f.length > 1 ? number(flag, f[1]) : 0);
            }
            default -> throw new IllegalArgumentException("unknown flag " + flag);
        }
    }

    private static String[] parts(String flag, String v, int min, int max) {
        String[] f = v.split(",", -1);
        if (f.length < min || f.length > max) {
            throw new IllegalArgumentException(flag + " takes " + min + (max > min ? " to " + max : "")
                + " comma-separated values, got " + v);
        }
        return f;
    }

    /** BEHAVIOR: "5%" is 0.05, "0.05" is 0.05; outside [0, 1] or not a number throws. */
    static double probability(String flag, String v) {
        boolean percent = v.endsWith("%");
        double p = number(flag, percent ? v.substring(0, v.length() - 1) : v) / (percent ? 100 : 1);
        if (p < 0 || p > 1) {
            throw new IllegalArgumentException(flag + " needs a probability in [0, 1] (or 0..100%), got " + v);
        }
        return p;
    }

    /** BEHAVIOR: a finite non-negative decimal number, else IllegalArgumentException. */
    static double number(String flag, String v) {
        double d;
        try {
            d = Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " needs a number, got " + v);
        }
        if (!(d >= 0) || Double.isInfinite(d)) {
            throw new IllegalArgumentException(flag + " needs a non-negative number, got " + v);
        }
        return d;
    }

    private static long longValue(String flag, String v) {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " needs an integer, got " + v);
        }
    }

    private static int nonNegative(String flag, String v) {
        long n = longValue(flag, v);
        if (n < 0 || n > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(flag + " needs a non-negative integer, got " + v);
        }
        return (int) n;
    }

    /**
     * BEHAVIOR: {@code host:port} or {@code [v6]:port}, unresolved; the port must be 1..65535. A listening address
     * ({@code listen}) may also be a bare port, meaning every local IPv4 address, and port 0, meaning any free port.
     */
    static InetSocketAddress address(String flag, String v, boolean listen) {
        String host;
        String port;
        if (v.startsWith("[")) {
            int end = v.indexOf("]:");
            if (end < 0) {
                throw new IllegalArgumentException(flag + " needs [ADDRESS]:PORT, got " + v);
            }
            host = v.substring(1, end);
            port = v.substring(end + 2);
        } else {
            int c = v.lastIndexOf(':');
            if (c < 0) {
                if (!listen) {
                    throw new IllegalArgumentException(flag + " needs HOST:PORT, got " + v);
                }
                host = "0.0.0.0";
                port = v;
            } else {
                host = v.substring(0, c);
                port = v.substring(c + 1);
            }
        }
        int p;
        try {
            p = Integer.parseInt(port);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " needs a port number, got " + v);
        }
        if (p < (listen ? 0 : 1) || p > 65535 || host.isEmpty()) {
            throw new IllegalArgumentException(flag + " needs a host and a port in " + (listen ? 0 : 1) + "..65535, got "
                + v);
        }
        return InetSocketAddress.createUnresolved(host, p);
    }
}
