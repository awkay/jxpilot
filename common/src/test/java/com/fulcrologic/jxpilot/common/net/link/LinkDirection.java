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

import java.nio.ByteBuffer;

/**
 * One direction of an emulated lossy link: datagrams go in with {@link #send} at a (virtual) time and come out
 * of {@link #receive} once their delivery time has come, impaired as the {@link LinkProfile} says. Pure: time is
 * a parameter, randomness a seeded {@link LinkRandom}. Packets live in preallocated slots in a min-heap ordered by
 * (delivery time, send order), so the steady state allocates nothing. Each datagram carries an int tag and an
 * address object through (the server side's channel and peer).
 * <p>
 * Every send consumes exactly {@link #DRAWS_PER_PACKET} random draws, whatever the profile, so two profiles with
 * the same seed see the same random sequence (turning on duplication does not change the jitter of the packets
 * that follow).
 */
public final class LinkDirection {
    /** Random draws per {@link #send}. */
    public static final int DRAWS_PER_PACKET = 7;
    /**
     * No packet is delayed longer than this (60 s): an uncapped heavy-tailed spike (shape well below 1) can draw
     * values beyond what a long holds.
     */
    public static final long MAX_DELAY_NANOS = 60_000_000_000L;

    private final LinkProfile profile;
    private final LinkRandom rnd;
    private final byte[][] data;
    private final int[] length;
    private final long[] due;
    private final long[] order;
    private final int[] tag;
    private final Object[] address;
    private final int[] heap;
    private int heapSize;
    private final int[] free;
    private int freeCount;
    private long nextOrder;
    private boolean bad;
    private long linkFreeAt = Long.MIN_VALUE;
    private long lastFifoDue = Long.MIN_VALUE;
    private int lastTag;
    private Object lastAddress;

    private long sent;
    private long sentBytes;
    private long delivered;
    private long lost;
    private long queueDrops;
    private long overflowDrops;
    private long oversize;
    private long duplicated;
    private long reordered;
    private long spikes;

    /**
     * @param profile   the impairments (read on every send, so it may be changed between sends)
     * @param seed      seeds the random draws
     * @param slots     packets that can be in flight at once (a duplicate takes a slot of its own)
     * @param slotBytes largest datagram carried; larger ones are dropped ({@link #oversize()})
     */
    public LinkDirection(LinkProfile profile, long seed, int slots, int slotBytes) {
        this.profile = profile;
        this.rnd = new LinkRandom(seed);
        data = new byte[slots][slotBytes];
        length = new int[slots];
        due = new long[slots];
        order = new long[slots];
        tag = new int[slots];
        address = new Object[slots];
        heap = new int[slots];
        free = new int[slots];
        for (int i = slots - 1; i >= 0; i--) {
            free[freeCount++] = i;
        }
    }

    /** The profile this direction reads (change it to change the link). */
    public LinkProfile profile() {
        return profile;
    }

    /**
     * A datagram, {@code packet}'s remaining bytes, is sent at {@code now}. The packet's position moves to its
     * limit, as a channel send does.
     * <p>
     * BEHAVIOR: Gilbert-Elliott loss: lost with probability lossGood in the good state, lossBad in the bad one
     * (the link starts good); then the state moves good→bad with goodToBad, bad→good with badToGood. A lost packet
     * counts in {@link #lost()}.
     * BEHAVIOR: with a bandwidth cap the packet (plus overheadBytes) is serialized after the previous one: it
     * leaves the bottleneck at max(now, end of the previous) + bytes / rate; a packet that would wait more than
     * queueLimit (when set) is dropped, counting {@link #queueDrops()}, and takes no link time. Without a cap it
     * leaves at once.
     * BEHAVIOR: delivery = leave time + clamp(delay + sigma * N(0,1) + spike, 0, {@link #MAX_DELAY_NANOS}), the
     * spike min(cap, Pareto(scale, shape)) with spikeProbability (cap 0 = uncapped), counting {@link #spikes()}.
     * BEHAVIOR: a reordered packet (reorderProbability) gets reorderDelay more and is exempt from FIFO, counting
     * {@link #reordered()}; otherwise with fifo the delivery is no earlier than the previous FIFO packet's.
     * BEHAVIOR: a duplicated packet (duplicateProbability) is delivered twice at the same time, counting
     * {@link #duplicated()}.
     * BEHAVIOR: a packet larger than the slots is dropped ({@link #oversize()}); with every slot in flight it is
     * dropped ({@link #overflowDrops()}), and so is a duplicate that finds no free slot.
     *
     * @return true when at least one copy is in flight
     */
    public boolean send(ByteBuffer packet, long now, int packetTag, Object packetAddress) {
        sent++;
        int bytes = packet.remaining();
        sentBytes += bytes;
        LinkProfile p = profile;
        boolean lose = rnd.chance(bad ? p.lossBad : p.lossGood);
        bad = rnd.chance(bad ? p.badToGood : p.goodToBad) != bad;
        double gauss = rnd.nextGaussian();
        boolean spike = rnd.chance(p.spikeProbability);
        double spikeSize = rnd.nextPareto(Math.max(1, p.spikeScaleNanos), p.spikeShape);
        boolean reorder = rnd.chance(p.reorderProbability);
        boolean dup = rnd.chance(p.duplicateProbability);
        if (lose) {
            lost++;
            packet.position(packet.limit());
            return false;
        }
        if (bytes > data[0].length) {
            oversize++;
            packet.position(packet.limit());
            return false;
        }
        long leave = now;
        if (p.bandwidthBytesPerSecond > 0) {
            long start = Math.max(now, linkFreeAt);
            if (p.queueLimitNanos > 0 && start - now > p.queueLimitNanos) {
                queueDrops++;
                packet.position(packet.limit());
                return false;
            }
            leave = start + (bytes + p.overheadBytes) * 1_000_000_000L / p.bandwidthBytesPerSecond;
            linkFreeAt = leave;
        }
        double delay = p.delayNanos + gauss * p.jitterSigmaNanos;
        if (spike) {
            spikes++;
            delay += p.spikeCapNanos > 0 ? Math.min(p.spikeCapNanos, spikeSize) : spikeSize;
        }
        long at = leave + Math.round(Math.min(MAX_DELAY_NANOS, Math.max(0, delay)));
        if (reorder) {
            reordered++;
            at += p.reorderDelayNanos;
        } else if (p.fifo) {
            at = Math.max(at, lastFifoDue);
            lastFifoDue = at;
        }
        int start = packet.position();
        if (!enqueue(packet, start, bytes, at, packetTag, packetAddress)) {
            packet.position(packet.limit());
            return false;
        }
        if (dup) {
            duplicated++;
            enqueue(packet, start, bytes, at, packetTag, packetAddress);
        }
        packet.position(packet.limit());
        return true;
    }

    private boolean enqueue(ByteBuffer packet, int start, int bytes, long at, int packetTag, Object packetAddress) {
        if (freeCount == 0) {
            overflowDrops++;
            return false;
        }
        int s = free[--freeCount];
        packet.get(start, data[s], 0, bytes);
        length[s] = bytes;
        due[s] = at;
        order[s] = nextOrder++;
        tag[s] = packetTag;
        address[s] = packetAddress;
        int i = heapSize++;
        heap[i] = s;
        siftUp(i);
        return true;
    }

    /** BEHAVIOR: the delivery time of the earliest packet in flight, {@link Long#MAX_VALUE} when none is. */
    public long nextDue() {
        return heapSize == 0 ? Long.MAX_VALUE : due[heap[0]];
    }

    /** Packets in flight. */
    public int inFlight() {
        return heapSize;
    }

    /**
     * Take the earliest packet due at {@code now}.
     * <p>
     * BEHAVIOR: when the earliest packet's delivery time is at or before {@code now}, its bytes are put into
     * {@code dst} (relative put; cut to dst's remaining space, as a datagram receive truncates), its tag and
     * address become {@link #lastTag()} and {@link #lastAddress()}, it counts in {@link #delivered()} and its
     * length is returned. Packets due at the same time come out in send order.
     * BEHAVIOR: otherwise nothing changes and the result is -1.
     */
    public int receive(ByteBuffer dst, long now) {
        if (heapSize == 0 || due[heap[0]] > now) {
            return -1;
        }
        int s = heap[0];
        heap[0] = heap[--heapSize];
        siftDown(0);
        int n = Math.min(length[s], dst.remaining());
        dst.put(data[s], 0, n);
        lastTag = tag[s];
        lastAddress = address[s];
        address[s] = null;
        free[freeCount++] = s;
        delivered++;
        return n;
    }

    /** Tag of the packet the last successful {@link #receive} returned. */
    public int lastTag() {
        return lastTag;
    }

    /** Address of the packet the last successful {@link #receive} returned. */
    public Object lastAddress() {
        return lastAddress;
    }

    private boolean before(int a, int b) {
        return due[a] < due[b] || due[a] == due[b] && order[a] < order[b];
    }

    private void siftUp(int i) {
        int s = heap[i];
        while (i > 0) {
            int parent = (i - 1) >>> 1;
            if (!before(s, heap[parent])) {
                break;
            }
            heap[i] = heap[parent];
            i = parent;
        }
        heap[i] = s;
    }

    private void siftDown(int i) {
        int s = heap[i];
        while (true) {
            int c = 2 * i + 1;
            if (c >= heapSize) {
                break;
            }
            if (c + 1 < heapSize && before(heap[c + 1], heap[c])) {
                c++;
            }
            if (!before(heap[c], s)) {
                break;
            }
            heap[i] = heap[c];
            i = c;
        }
        heap[i] = s;
    }

    /** Datagrams offered to {@link #send}. */
    public long sent() { return sent; }

    /** Bytes of the datagrams offered to {@link #send} (payload, before loss). */
    public long sentBytes() { return sentBytes; }

    /** Copies handed out by {@link #receive}. */
    public long delivered() { return delivered; }

    /** Datagrams lost by the Gilbert-Elliott model. */
    public long lost() { return lost; }

    /** Datagrams dropped by the bottleneck queue limit. */
    public long queueDrops() { return queueDrops; }

    /** Copies dropped because every slot was in flight. */
    public long overflowDrops() { return overflowDrops; }

    /** Datagrams larger than a slot. */
    public long oversize() { return oversize; }

    /** Datagrams sent twice. */
    public long duplicated() { return duplicated; }

    /** Datagrams held back to be reordered. */
    public long reordered() { return reordered; }

    /** Datagrams that got a delay spike. */
    public long spikes() { return spikes; }
}
