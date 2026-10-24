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
package com.fulcrologic.jxpilot.client.latency;

import com.fulcrologic.jxpilot.common.net.link.LinkProfile;

/**
 * One cell of the link matrix (docs/latency-fixes.md §7.4): round trip time, jitter and loss. Each direction
 * gets half the RTT and the same jitter and loss, from its own random stream.
 */
record Scenario(int rttMs, Jitter jitter, Loss loss) {
    /** Per-direction delay variation. */
    enum Jitter {
        NONE("0"),
        /** Normal jitter, sigma 15 ms per direction (FIFO: a late packet holds back the ones behind it). */
        NORMAL_15("s15"),
        /** 5% of packets get a Pareto spike (scale 20 ms, shape 1.5, mean 60 ms, capped at 300 ms). */
        PARETO("spk");

        final String label;

        Jitter(String label) {
            this.label = label;
        }
    }

    /** Per-direction loss. */
    enum Loss {
        NONE("0"),
        /** 1% independent loss. */
        UNIFORM_1("1%u"),
        /** 5% Gilbert loss in bursts of mean length 3 packets. */
        BURSTY_5("5%b");

        final String label;

        Loss(String label) {
            this.label = label;
        }
    }

    /** BEHAVIOR: the profile of one direction: RTT/2 delay plus the jitter and loss of this cell. */
    LinkProfile direction() {
        LinkProfile p = new LinkProfile().delayMs(rttMs / 2.0);
        switch (jitter) {
            case NORMAL_15 -> p.normalJitterMs(15);
            case PARETO -> p.paretoSpikes(0.05, 20, 1.5, 300);
            case NONE -> { }
        }
        switch (loss) {
            case UNIFORM_1 -> p.uniformLoss(0.01);
            case BURSTY_5 -> p.burstyLoss(0.05, 3);
            case NONE -> { }
        }
        return p;
    }

    /** BEHAVIOR: "RTT/jitter/loss", e.g. {@code 200/s15/5%b}. */
    String label() {
        return rttMs + "/" + jitter.label + "/" + loss.label;
    }
}
