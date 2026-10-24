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
package com.fulcrologic.jxpilot.client.net;

import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.math.DebrisBurst;
import com.fulcrologic.jxpilot.common.net.ConfigTransfer;
import com.fulcrologic.jxpilot.common.net.InputReader;
import com.fulcrologic.jxpilot.common.net.Msg;
import com.fulcrologic.jxpilot.common.net.PacketHeader;
import com.fulcrologic.jxpilot.common.net.PacketType;
import com.fulcrologic.jxpilot.common.net.Protocol;
import com.fulcrologic.jxpilot.common.net.Records;
import com.fulcrologic.jxpilot.common.net.ReliableChannel;
import com.fulcrologic.jxpilot.common.net.ReliableType;
import com.fulcrologic.jxpilot.common.net.Section;
import com.fulcrologic.jxpilot.common.net.SnapshotWriter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Scripted fake server built only from common's codecs (no server module): answers CONNECT, sends
 * WELCOME + the config as DATA_CHUNKs + PLAYER_JOINs reliably, and every 2nd 60 Hz tick a snapshot
 * with the own ship, one other ship moving +x across the wrap seam, two shots, a radar section every
 * 3rd snapshot and, on request, a debris burst. Records what the client sends (inputs, chat, READY).
 */
public final class FakeServer {
    /** Server side of a datagram link. */
    public interface Port {
        boolean send(ByteBuffer b) throws IOException;

        int receive(ByteBuffer b) throws IOException;
    }

    /** 20 x 10 blocks, wrapping: 700 x 350 px. One base, one cannon, one fuel station. */
    public static final String CONFIG = """
        mapName: Fake Arena
        mapWidth: 20
        mapHeight: 10
        edgeWrap: yes
        framesPerSecond: 14
        mapData: \\multiline: EndOfMapdata
        xxxxxxxxxxxxxxxxxxxx
        x                  x
        x   _          #   x
        x                  x
        x        d         x
        x                  x
        x                  x
        x                  x
        x                  x
        xxxxxxxxxxxxxxxxxxxx
        EndOfMapdata
        """;

    public static final int SESSION = 0x2345;
    public static final int PLAYER_ID = 7;
    public static final int OTHER_ID = 9;

    private Port port;
    public final Options options;
    public final WorldMap map;
    private final ConfigTransfer.Blob blob;
    private final ByteBuffer rx = ByteBuffer.allocate(2048);
    private final ByteBuffer tx = ByteBuffer.allocate(Protocol.MAX_PACKET);
    private final PacketHeader header = new PacketHeader();
    private final SnapshotWriter writer = new SnapshotWriter();
    private final com.fulcrologic.jxpilot.common.sim.ShipBody predictBody = new com.fulcrologic.jxpilot.common.sim.ShipBody();
    private final InputReader inputReader = new InputReader();
    public ReliableChannel channel = new ReliableChannel();
    private ConfigTransfer.Sender sender;

    public boolean accepted;
    public boolean ready;
    public int nonce;
    public int connects;
    public int accepts;
    public int inputPackets;
    /** The newest tick an INPUT carried an entry for. */
    public int lastInputTick;
    public long lastKeys;
    public final List<String> chats = new ArrayList<>();
    /** SETTINGS received, as "power/turnSpeed". */
    public final List<String> settings = new ArrayList<>();
    /** Every DISPLAY received, as "width x height". */
    public final List<String> displays = new ArrayList<>();
    public boolean reject;
    public boolean silent;
    public int disconnectsReceived;

    public int tick;
    public int snapshotsSent;
    public float otherX = 600;
    public final float otherY = 175;
    /** px per original frame */
    public final float otherVx = 30;
    public float frameFraction = 14f / 60f;
    public boolean otherThrusting = true;
    public int burstAtSnapshot = -1;

    public FakeServer(Port port) {
        this.port = port;
        options = new Options();
        ConfigParser.parse(CONFIG, "fake", OptionOrigin.MAP, options);
        map = WorldMap.fromOptions(options);
        blob = ConfigTransfer.encode(options.toConfigText());
        writer.setWorld(map.pixelWidth(), map.pixelHeight(), true);
    }

    /** Continue with another port (same session state); returns this. */
    public FakeServer withPort(Port p) {
        port = p;
        return this;
    }

    /** Handle everything waiting from the client. */
    public void poll(long now) throws IOException {
        while (true) {
            rx.clear();
            int n = port.receive(rx);
            if (n <= 0) {
                return;
            }
            rx.flip();
            if (!header.read(rx)) {
                continue;
            }
            if (silent) {
                continue;
            }
            switch (header.type()) {
                case PacketType.CONNECT -> onConnect(rx, now);
                case PacketType.INPUT -> {
                    if (header.session() == SESSION) {
                        channel.onAck(header.reliableAck(), now);
                        inputPackets++;
                        if (inputReader.wrap(rx) && inputReader.newestTick() - lastInputTick >= 0) {
                            lastInputTick = inputReader.newestTick();
                            lastKeys = inputReader.keys(0);
                        }
                    }
                }
                case PacketType.RELIABLE -> {
                    channel.onAck(header.reliableAck(), now);
                    channel.read(rx, (type, p) -> {
                        if (type == ReliableType.READY) {
                            ready = true;
                        } else if (type == ReliableType.SETTINGS) {
                            Msg.Settings st = new Msg.Settings();
                            st.read(p);
                            settings.add(st.power + "/" + st.turnSpeed);
                        } else if (type == ReliableType.DISPLAY) {
                            Msg.Display d = new Msg.Display();
                            d.read(p);
                            displays.add(d.viewWidth + "x" + d.viewHeight);
                        } else if (type == ReliableType.CHAT) {
                            Msg.Text t = new Msg.Text();
                            t.read(p);
                            chats.add(t.text.toString());
                        }
                    });
                }
                case PacketType.ACK -> channel.onAck(header.reliableAck(), now);
                case PacketType.PING -> {
                    // as ServerNet: PONG echoes the 12-byte body
                    channel.onAck(header.reliableAck(), now);
                    begin(PacketType.PONG, SESSION);
                    for (int i = 0; i < 12 && rx.hasRemaining(); i++) {
                        tx.put(rx.get());
                    }
                    send();
                    pongs++;
                }
                case PacketType.DISCONNECT -> disconnectsReceived++;
                default -> { }
            }
        }
    }

    private void onConnect(ByteBuffer in, long now) throws IOException {
        connects++;
        Msg.Connect c = new Msg.Connect();
        c.read(in);
        if (reject) {
            Msg.Reject r = new Msg.Reject();
            r.nonce = c.nonce;
            r.reason = Msg.Reject.REASON_FULL;
            r.text.append("server full");
            begin(PacketType.REJECT, 0);
            r.write(tx);
            send();
            return;
        }
        if (!accepted || c.nonce != nonce) {
            nonce = c.nonce;
            accepted = true;
            ready = false;
            channel = new ReliableChannel();
            queueJoin(c);
        }
        Msg.Accept a = new Msg.Accept();
        a.nonce = c.nonce;
        a.playerId = PLAYER_ID;
        begin(PacketType.ACCEPT, SESSION);
        a.write(tx);
        send();
        accepts++;
    }

    private void queueJoin(Msg.Connect c) {
        Msg.Welcome w = new Msg.Welcome();
        w.playerId = PLAYER_ID;
        w.team = 0;
        w.simHz = 60;
        w.snapshotHz = 30;
        w.flags = Msg.Welcome.FLAG_EDGE_WRAP;
        w.gameFps = 14;
        w.serverName.append("fake");
        blob.fill(w);
        w.send(channel);
        sender = new ConfigTransfer.Sender(blob);
        sender.pump(channel, 4);
        Msg.PlayerJoin j = new Msg.PlayerJoin();
        j.id = PLAYER_ID;
        j.team = 0;
        j.flags = Msg.PlayerJoin.FLAG_SELF;
        j.name.append(c.name);
        j.power = 45;
        queueAfterConfig.add(j);
        Msg.PlayerJoin o = new Msg.PlayerJoin();
        o.id = OTHER_ID;
        o.team = 1;
        o.flags = Msg.PlayerJoin.FLAG_ROBOT;
        o.name.append("Robby");
        o.power = 50;
        o.afterburners = 4;
        queueAfterConfig.add(o);
    }

    private final List<Msg.PlayerJoin> queueAfterConfig = new ArrayList<>();

    /** Queue a server MESSAGE. */
    public void message(int kind, String text) {
        Msg.Text t = new Msg.Text();
        t.kind = kind;
        t.text.append(text);
        t.send(channel);
    }

    /** Queue a GAME_EVENT. */
    public void gameEvent(int kind, int a, int b) {
        Msg.GameEvent e = new Msg.GameEvent();
        e.kind = kind;
        e.a = a;
        e.b = b;
        e.send(channel);
    }

    /** Queue a SCORE_OBJECT (the server sends it before the SCORE it explains). */
    public void scoreObject(float points, int bx, int by, String label) {
        Msg.ScoreObject o = new Msg.ScoreObject();
        o.score = points;
        o.bx = bx;
        o.by = by;
        o.text.append(label);
        o.send(channel);
    }

    /** Queue a SCORE. */
    public void score(int id, float score) {
        Msg.Score s = new Msg.Score();
        s.id = id;
        s.score = score;
        s.send(channel);
    }

    /** Advance one 60 Hz tick: move the ship, send a snapshot every 2nd tick, pump config, flush reliable. */
    public void step(long now) throws IOException {
        if (silent) {
            return;
        }
        tick++;
        otherX = (float) com.fulcrologic.jxpilot.common.math.XMath.wrap(otherX + otherVx * frameFraction,
            map.pixelWidth());
        if (sender != null) {
            sender.pump(channel, 4);
            if (sender.done() && !queueAfterConfig.isEmpty()) {
                for (Msg.PlayerJoin j : queueAfterConfig) {
                    j.send(channel);
                }
                queueAfterConfig.clear();
            }
        }
        if (accepted && tick % 2 == 0) {
            sendSnapshot();
        }
        for (int i = 0; i < 16; i++) {
            begin(PacketType.RELIABLE, SESSION);
            if (channel.write(tx, now) == 0) {
                break;
            }
            send();
        }
    }

    private void sendSnapshot() throws IOException {
        begin(PacketType.SNAPSHOT, SESSION);
        writer.begin(tx, tick, Math.min(lastInputTick, tick), 0, 1, SnapshotWriter.FLAG_LAST_PART, 350, 175);
        writer.beginSection(Section.SELF);
        Records.Self.put(writer, PLAYER_ID, 0, 100, 100, 1, 0, 0, 900, 1000, Protocol.NO_ID, 0, 0, 0);
        writer.endSection();
        // protocol v2: the owner's PREDICT core every snapshot, the rare block every 14th, the keys every 2nd
        predictBody.setClicks(6400 + 64 * (tick % 50), 6400);
        predictBody.vx = 1;
        predictBody.prevKeys = lastKeys;
        predictBody.lastReceivedKeys = snapshotsSent % 2 == 0 ? lastKeys : ~lastKeys;
        writer.beginSection(Section.PREDICT);
        Records.Predict.put(writer, predictBody, tick + 1, 0, lastKeys, 1);
        writer.endSection();
        if (snapshotsSent % 14 == 0) {
            writer.beginSection(Section.PREDICT_RARE);
            Records.PredictRare.put(writer, predictBody, 1);
            writer.endSection();
        }
        if (predictBody.keysDiffer(lastKeys)) {
            writer.beginSection(Section.PREDICT_KEYS);
            Records.PredictKeys.put(writer, predictBody);
            writer.endSection();
        }
        writer.beginSection(Section.OWN_SHOTS);
        Records.OwnShot.put(writer, 200, 150, 2, 0);
        Records.OwnShot.put(writer, 210, 150, 2, 1);
        writer.endSection();
        writer.beginSection(Section.SHIP);
        Records.Ship.put(writer, OTHER_ID, otherThrusting ? Records.ShipFlags.THRUSTING : 0, otherX, otherY, 0,
            otherVx, 0);
        writer.endSection();
        writer.beginSection(Section.SHOT, Records.ShotKind.variant(Records.ShotKind.SHOT, Records.ObjColor.ENEMY));
        Records.Shot.put(writer, 200, 150, 10, 0);
        Records.Shot.put(writer, 300, 160, -5, 3);
        writer.endSection();
        if (snapshotsSent % 3 == 0) {
            writer.beginSection(Section.RADAR);
            Records.Radar.put(writer, 100, 100, Records.Radar.FLAG_SELF);
            Records.Radar.put(writer, otherX, otherY, 0);
            writer.endSection();
        }
        if (snapshotsSent == burstAtSnapshot) {
            writer.beginSection(Section.BURST);
            Records.Burst.put(writer, burst(), Records.BurstKind.DEBRIS, Records.ObjColor.WHITE, 1);
            writer.endSection();
        }
        send();
        snapshotsSent++;
    }

    /** The debris burst sent at {@link #burstAtSnapshot} (already quantized). */
    public static DebrisBurst burst() {
        DebrisBurst d = new DebrisBurst();
        d.x = 350;
        d.y = 175;
        d.seed = 12345;
        d.count = 20;
        d.minDir = 0;
        d.dirSpan = 128;
        d.minSpeed = 2;
        d.maxSpeed = 8;
        d.minLife = 10;
        d.maxLife = 30;
        Records.Burst.quantize(d);
        return d;
    }

    /** PONGs sent (answers to the client's RTT probes). */
    public int pongs;

    private void begin(int type, int session) {
        tx.clear();
        PacketHeader.write(tx, type, session, channel.ack());
    }

    private void send() throws IOException {
        tx.flip();
        port.send(tx);
    }
}
