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
package com.fulcrologic.jxpilot.common.net.link;

/**
 * The impairments of one direction of an emulated link (docs/latency-fixes.md §7): base delay, normal jitter,
 * Pareto delay spikes, Gilbert-Elliott bursty loss, reordering, duplication and a bandwidth cap with a drop-tail
 * queue. All times are nanoseconds. Mutable settings with fluent setters; a fresh profile is a perfect link
 * (no delay, no loss).
 * <p>
 * Jitter and spikes keep the path FIFO by default (a packet never overtakes an earlier one, as on a real path
 * where a delay spike is a queue), unless {@link #fifo} is off; {@link #reorderProbability} reorders on purpose.
 */
public final class LinkProfile {
    /** One-way propagation delay. */
    public long delayNanos;
    /** Standard deviation of the normal jitter added to every packet (the total delay is clamped at 0). */
    public long jitterSigmaNanos;
    /** Probability of a Pareto delay spike on a packet. */
    public double spikeProbability;
    /** Pareto scale (the smallest spike). */
    public long spikeScaleNanos;
    /** Pareto shape (smaller is heavier tailed; above 1 the mean is finite: scale * shape / (shape - 1)). */
    public double spikeShape = 1.5;
    /** Spikes are capped here. */
    public long spikeCapNanos;
    /** Gilbert-Elliott: per-packet probability of going from the good to the bad state. */
    public double goodToBad;
    /** Gilbert-Elliott: per-packet probability of going from the bad back to the good state. */
    public double badToGood = 1;
    /** Loss probability in the good state (uniform loss alone: this with goodToBad 0). */
    public double lossGood;
    /** Loss probability in the bad state. */
    public double lossBad;
    /** Probability that a packet is held back by {@link #reorderDelayNanos}, letting later packets pass it. */
    public double reorderProbability;
    /** Extra delay of a reordered packet. */
    public long reorderDelayNanos;
    /** Probability that a packet is delivered twice. */
    public double duplicateProbability;
    /** Bottleneck rate in bytes per second; 0 = unlimited. */
    public long bandwidthBytesPerSecond;
    /** Per-packet bytes added at the bottleneck (IPv4 + UDP headers). */
    public int overheadBytes = 28;
    /** A packet that would wait longer than this in the bottleneck queue is dropped; 0 = unlimited queue. */
    public long queueLimitNanos;
    /** Jitter and spikes never reorder (a packet is delivered no earlier than the one sent before it). */
    public boolean fifo = true;

    /** BEHAVIOR: a perfect link: no delay, no jitter, no loss, no reordering, no duplication, no cap. */
    public LinkProfile() {
    }

    static long ms(double ms) {
        return Math.round(ms * 1_000_000.0);
    }

    /** BEHAVIOR: sets the one-way delay to {@code ms} milliseconds. */
    public LinkProfile delayMs(double ms) {
        delayNanos = ms(ms);
        return this;
    }

    /** BEHAVIOR: sets the normal jitter's standard deviation to {@code sigmaMs} milliseconds. */
    public LinkProfile normalJitterMs(double sigmaMs) {
        jitterSigmaNanos = ms(sigmaMs);
        return this;
    }

    /** BEHAVIOR: sets Pareto spikes: probability, scale, shape and cap (milliseconds). */
    public LinkProfile paretoSpikes(double probability, double scaleMs, double shape, double capMs) {
        spikeProbability = probability;
        spikeScaleNanos = ms(scaleMs);
        spikeShape = shape;
        spikeCapNanos = ms(capMs);
        return this;
    }

    /** BEHAVIOR: independent loss with probability {@code p} (Gilbert-Elliott with no bad state). */
    public LinkProfile uniformLoss(double p) {
        goodToBad = 0;
        badToGood = 1;
        lossGood = p;
        lossBad = 0;
        return this;
    }

    /**
     * Bursty loss with long-run rate {@code average} and mean burst length {@code meanBurst} packets: the
     * Gilbert model (every packet in the bad state lost, none in the good one).
     * <p>
     * BEHAVIOR: badToGood = 1/meanBurst, goodToBad = average * badToGood / (1 - average), lossBad 1, lossGood 0,
     * so the stationary bad-state share (the loss rate) is {@code average}.
     */
    public LinkProfile burstyLoss(double average, double meanBurst) {
        badToGood = 1 / meanBurst;
        goodToBad = average * badToGood / (1 - average);
        lossGood = 0;
        lossBad = 1;
        return this;
    }

    /** BEHAVIOR: sets the Gilbert-Elliott parameters as given. */
    public LinkProfile gilbertElliott(double goodToBad, double badToGood, double lossGood, double lossBad) {
        this.goodToBad = goodToBad;
        this.badToGood = badToGood;
        this.lossGood = lossGood;
        this.lossBad = lossBad;
        return this;
    }

    /** BEHAVIOR: sets the reorder probability and the held-back packet's extra delay (milliseconds). */
    public LinkProfile reorder(double probability, double delayMs) {
        reorderProbability = probability;
        reorderDelayNanos = ms(delayMs);
        return this;
    }

    /** BEHAVIOR: sets the duplication probability. */
    public LinkProfile duplicate(double probability) {
        duplicateProbability = probability;
        return this;
    }

    /** BEHAVIOR: sets the bottleneck rate (bytes/s, 0 = unlimited) and the queue limit (milliseconds, 0 = none). */
    public LinkProfile bandwidth(long bytesPerSecond, double queueLimitMs) {
        bandwidthBytesPerSecond = bytesPerSecond;
        queueLimitNanos = ms(queueLimitMs);
        return this;
    }

    /** BEHAVIOR: sets {@link #fifo}. */
    public LinkProfile fifo(boolean on) {
        fifo = on;
        return this;
    }

    /** BEHAVIOR: copies every setting of {@code o} into this profile; returns this. */
    public LinkProfile copyFrom(LinkProfile o) {
        delayNanos = o.delayNanos;
        jitterSigmaNanos = o.jitterSigmaNanos;
        spikeProbability = o.spikeProbability;
        spikeScaleNanos = o.spikeScaleNanos;
        spikeShape = o.spikeShape;
        spikeCapNanos = o.spikeCapNanos;
        goodToBad = o.goodToBad;
        badToGood = o.badToGood;
        lossGood = o.lossGood;
        lossBad = o.lossBad;
        reorderProbability = o.reorderProbability;
        reorderDelayNanos = o.reorderDelayNanos;
        duplicateProbability = o.duplicateProbability;
        bandwidthBytesPerSecond = o.bandwidthBytesPerSecond;
        overheadBytes = o.overheadBytes;
        queueLimitNanos = o.queueLimitNanos;
        fifo = o.fifo;
        return this;
    }
}
