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
package com.fulcrologic.jxpilot.client.app;

import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.Base;
import com.fulcrologic.jxpilot.common.map.WorldMap;
import com.fulcrologic.jxpilot.common.math.DebrisBurst;
import com.fulcrologic.jxpilot.common.math.XMath;
import com.fulcrologic.jxpilot.common.net.ConfigTransfer;
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
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A scripted JXPilot-protocol server for visual checks and allocation tests, built only from common's
 * codecs: it serves a real map file, answers one client's CONNECT, transfers the config, announces the
 * own player and four robots, and then every tick (the map's framesPerSecond, one snapshot per tick)
 * sends the own ship at the first base, four robots circling it (one friendly, one shielded, one
 * locked), streams of shots, an explosion every 40 ticks, radar every 3rd snapshot, own items, and a few
 * scripted messages and score changes. Deterministic in the tick number.
 */
final class ScriptedServer {
    /** Server side of a datagram link. */
    interface Port {
        boolean send(ByteBuffer b) throws IOException;

        int receive(ByteBuffer b) throws IOException;
    }

    static final int SESSION = 0x3456;
    static final int SELF = 1;
    static final int[] ROBOTS = {2, 3, 4, 5};
    static final String[] NAMES = {"Bonnie", "Clyde", "R2D2", "Uzi"};

    final Options options = new Options();
    final WorldMap map;
    final int fps;
    private final ConfigTransfer.Blob blob;
    private final ByteBuffer rx = ByteBuffer.allocate(2048);
    private final ByteBuffer tx = ByteBuffer.allocate(Protocol.MAX_PACKET);
    private final PacketHeader header = new PacketHeader();
    private final SnapshotWriter writer = new SnapshotWriter();
    private final Msg.Connect connectMsg = new Msg.Connect();
    private final DebrisBurst burst = new DebrisBurst();
    private final int[] itemCounts = new int[Item.COUNT];
    private final List<Msg.Payload> afterConfig = new ArrayList<>();
    private final double baseX;
    private final double baseY;
    private Port port;
    ReliableChannel channel = new ReliableChannel();
    private ConfigTransfer.Sender sender;
    boolean accepted;
    int nonce;
    int tick;
    int snapshots;
    int disconnects;
    String clientName = "";
    final List<String> chats = new ArrayList<>();

    ScriptedServer(Path mapFile, Port port) throws IOException {
        this.port = port;
        ConfigParser.parseFile(mapFile, OptionOrigin.MAP, options);
        map = WorldMap.fromOptions(options);
        fps = Math.max(1, options.framesPerSecond());
        blob = ConfigTransfer.encode(options.toConfigText());
        writer.setWorld(map.pixelWidth(), map.pixelHeight(), map.edgeWrap());
        Base b = map.bases().get(0);
        if (!map.fuels().isEmpty()) {
            baseX = map.fuels().get(0).x() + 40; // next to a fuel station, refuelling
            baseY = map.fuels().get(0).y();
        } else {
            baseX = b.x();
            baseY = b.y();
        }
        itemCounts[Item.AFTERBURNER.ordinal()] = 2;
        itemCounts[Item.MISSILE.ordinal()] = 4;
        itemCounts[Item.CLOAK.ordinal()] = 1;
    }

    ScriptedServer withPort(Port p) {
        port = p;
        return this;
    }

    /** Handle everything the client sent. */
    void poll(long now) throws IOException {
        while (true) {
            rx.clear();
            if (port.receive(rx) <= 0) {
                return;
            }
            rx.flip();
            if (!header.read(rx)) {
                continue;
            }
            switch (header.type()) {
                case PacketType.CONNECT -> onConnect();
                case PacketType.INPUT, PacketType.ACK -> channel.onAck(header.reliableAck(), now);
                case PacketType.RELIABLE -> {
                    channel.onAck(header.reliableAck(), now);
                    channel.read(rx, (type, p) -> {
                        if (type == ReliableType.CHAT) {
                            Msg.Text t = new Msg.Text();
                            t.read(p);
                            chats.add(t.text.toString());
                            message(Msg.Text.KIND_CHAT, t.text + " [" + clientName + "]");
                        }
                    });
                }
                case PacketType.DISCONNECT -> disconnects++;
                default -> { }
            }
        }
    }

    private void onConnect() throws IOException {
        connectMsg.read(rx);
        if (!accepted || connectMsg.nonce != nonce) {
            nonce = connectMsg.nonce;
            accepted = true;
            clientName = connectMsg.name.toString();
            channel = new ReliableChannel();
            queueJoin();
        }
        Msg.Accept a = new Msg.Accept();
        a.nonce = nonce;
        a.playerId = SELF;
        begin(PacketType.ACCEPT);
        a.write(tx);
        send();
    }

    private void queueJoin() {
        Msg.Welcome w = new Msg.Welcome();
        w.playerId = SELF;
        w.team = 0;
        w.simHz = fps;
        w.snapshotHz = fps;
        w.flags = map.edgeWrap() ? Msg.Welcome.FLAG_EDGE_WRAP : 0;
        w.gameFps = fps;
        w.serverName.append("scripted");
        blob.fill(w);
        w.send(channel);
        sender = new ConfigTransfer.Sender(blob);
        sender.pump(channel, 4);
        afterConfig.clear();
        later(join(SELF, 0, Msg.PlayerJoin.FLAG_SELF, clientName));
        for (int i = 0; i < ROBOTS.length; i++) {
            later(join(ROBOTS[i], i == 0 ? 0 : 1, Msg.PlayerJoin.FLAG_ROBOT, NAMES[i]));
        }
    }

    private void later(Msg.PlayerJoin j) {
        afterConfig.add(j);
    }

    private static Msg.PlayerJoin join(int id, int team, int flags, String name) {
        Msg.PlayerJoin j = new Msg.PlayerJoin();
        j.id = id;
        j.team = team;
        j.flags = flags;
        j.name.append(name);
        j.power = 45;
        return j;
    }

    void message(int kind, String text) {
        Msg.Text t = new Msg.Text();
        t.kind = kind;
        t.text.append(text);
        t.send(channel);
    }

    void score(int id, float score) {
        Msg.Score s = new Msg.Score();
        s.id = id;
        s.score = score;
        s.send(channel);
    }

    /** One server tick: config pump, scripted reliable events, snapshot, reliable flush. */
    void step(long now) throws IOException {
        tick++;
        if (sender != null) {
            sender.pump(channel, 4);
            if (sender.done() && !afterConfig.isEmpty()) {
                for (Msg.Payload p : afterConfig) {
                    ((Msg.PlayerJoin) p).send(channel);
                }
                afterConfig.clear();
                for (int i = 0; i < ROBOTS.length; i++) {
                    score(ROBOTS[i], 0);
                }
                score(SELF, 0);
                message(Msg.Text.KIND_SERVER, clientName + " has entered \"" + map.name() + "\", made by Bjørn.");
            }
        }
        script();
        if (accepted) {
            snapshot();
        }
        for (int i = 0; i < 16; i++) {
            begin(PacketType.RELIABLE);
            if (channel.write(tx, now) == 0) {
                break;
            }
            send();
        }
    }

    /** R7: a robot with a custom shape that leaves and rejoins every 7 s (join/leave in the measured window). */
    static final int REJOINER = ROBOTS[3];
    static final String REJOINER_SHAPE = "(SH: 15,0 -9,8 -9,-8 -3,0)(GU: 15,0)(EN: -9,0)";

    private void script() {
        if (tick > 6 * fps && tick % (7 * fps) == 0) {
            Msg.PlayerLeave l = new Msg.PlayerLeave();
            l.id = REJOINER;
            l.send(channel);
            Msg.PlayerJoin j = join(REJOINER, 1, Msg.PlayerJoin.FLAG_ROBOT, NAMES[3]);
            j.shape.append(REJOINER_SHAPE);
            j.send(channel);
            score(REJOINER, 0);
        }
        if (tick == 3 * fps) {
            message(Msg.Text.KIND_CHAT, "Clyde wants you for dessert. [Clyde]");
        }
        if (tick > 5 * fps && tick % (15 * fps) == 0) {
            message(Msg.Text.KIND_GAME, "Uzi was killed by a shot from Clyde.");
            score(ROBOTS[1], tick / (15f * fps));
        }
        if (tick == 4 * fps) {
            message(Msg.Text.KIND_GAME, "R2D2 was killed by a shot from Bonnie.");
            score(ROBOTS[0], 1);
            score(ROBOTS[2], -1);
        }
    }

    double robotX(int i) {
        return XMath.wrap(baseX + Math.cos(angle(i)) * radius(i), map.pixelWidth());
    }

    double robotY(int i) {
        return XMath.wrap(baseY + 60 + Math.sin(angle(i)) * radius(i), map.pixelHeight());
    }

    private double angle(int i) {
        return tick * 0.05 + i * Math.PI / 2;
    }

    private static double radius(int i) {
        return 140 + 40 * i;
    }

    private void snapshot() throws IOException {
        begin(PacketType.SNAPSHOT);
        writer.begin(tx, tick, 0, 0, 1, SnapshotWriter.FLAG_LAST_PART, baseX, baseY);
        writer.beginSection(Section.SELF);
        double heading = Math.PI / 2 + Math.sin(tick * 0.03) * 0.6;
        int selfFlags = ((tick / fps) % 3 == 0 ? Records.ShipFlags.SHIELD : Records.ShipFlags.THRUSTING)
            | Records.ShipFlags.REFUELING;
        // R7: the lock target changes every 3 s
        int lock = (tick / (3 * fps)) % 2 == 0 ? ROBOTS[1] : ROBOTS[2];
        Records.Self.put(writer, SELF, selfFlags, (float) baseX, (float) baseY, 0, 0, (float) heading, 800, 1000,
            lock, 0, 0, 0);
        writer.endSection();
        writer.beginSection(Section.SHIP);
        for (int i = 0; i < ROBOTS.length; i++) {
            int flags = Records.ShipFlags.THRUSTING | (i == 0 ? Records.ShipFlags.FRIENDLY : 0)
                | (i == 2 ? Records.ShipFlags.SHIELD : 0);
            double h = angle(i) + Math.PI / 2;
            double v = radius(i) * 0.05;
            Records.Ship.put(writer, ROBOTS[i], flags, robotX(i), robotY(i), h, -Math.sin(angle(i)) * v,
                Math.cos(angle(i)) * v);
        }
        writer.endSection();
        writer.beginSection(Section.SHOT, Records.ShotKind.variant(Records.ShotKind.SHOT, Records.ObjColor.ENEMY));
        for (int i = 1; i < ROBOTS.length; i++) {
            double dx = baseX - robotX(i);
            double dy = baseY - robotY(i);
            double d = Math.max(1, Math.hypot(dx, dy));
            for (int k = 0; k < 6; k++) {
                double s = ((tick + k * 5) % 30) * 12.0;
                Records.Shot.put(writer, robotX(i) + dx / d * s, robotY(i) + dy / d * s, 0, 0);
            }
        }
        writer.endSection();
        writer.beginSection(Section.SHOT, Records.ShotKind.variant(Records.ShotKind.SHOT, Records.ObjColor.TEAM));
        for (int k = 0; k < 4; k++) {
            Records.Shot.put(writer, robotX(0) + k * 15, robotY(0) - k * 10, 0, 0);
        }
        writer.endSection();
        if (tick % 40 == 10) {
            burst.x = (float) (baseX + 90);
            burst.y = (float) (baseY + 120);
            burst.vx = 0;
            burst.vy = 0;
            burst.seed = tick;
            burst.count = 60;
            burst.minDir = 0;
            burst.dirSpan = 128;
            burst.minSpeed = 1;
            burst.maxSpeed = 12;
            burst.minLife = 8;
            burst.maxLife = 30;
            Records.Burst.quantize(burst);
            writer.beginSection(Section.BURST);
            Records.Burst.put(writer, burst, Records.BurstKind.DEBRIS, Records.ObjColor.WHITE, 0);
            writer.endSection();
        }
        if (snapshots % 3 == 0) {
            writer.beginSection(Section.RADAR);
            Records.Radar.put(writer, baseX, baseY, Records.Radar.FLAG_SELF);
            for (int i = 0; i < ROBOTS.length; i++) {
                Records.Radar.put(writer, robotX(i), robotY(i), i == 0 ? Records.Radar.FLAG_FRIENDLY : 0);
            }
            writer.endSection();
        }
        writer.beginSection(Section.SELF_ITEMS);
        Records.SelfItems.put(writer, itemCounts);
        writer.endSection();
        objects();
        send();
        snapshots++;
    }

    /** One of every M2 object kind around the own ship; a missile and a ball move. */
    private void objects() {
        double m = (tick % 40) * 8;
        writer.beginSection(Section.ITEM);
        Records.ItemObj.put(writer, baseX - 120, baseY + 120, Item.MISSILE.ordinal());
        Records.ItemObj.put(writer, baseX - 80, baseY + 120, Item.CLOAK.ordinal());
        writer.endSection();
        writer.beginSection(Section.MINE);
        Records.Mine.put(writer, baseX - 160, baseY - 80, 0, ROBOTS[1]);
        Records.Mine.put(writer, baseX - 120, baseY - 80, Records.Mine.FLAG_FRIENDLY, ROBOTS[0]);
        writer.endSection();
        writer.beginSection(Section.MISSILE);
        Records.Missile.put(writer, 77, baseX + 200 - m, baseY - 140, Math.PI, Records.Missile.KIND_SMART, 15);
        writer.endSection();
        writer.beginSection(Section.BALL);
        Records.Ball.put(writer, 5, baseX + 60, baseY + 160 + Math.sin(tick * 0.1) * 20, 1, ROBOTS[0]);
        writer.endSection();
        writer.beginSection(Section.CONNECTOR);
        Records.Connector.put(writer, baseX + 60, baseY + 160 + Math.sin(tick * 0.1) * 20, robotX(0), robotY(0),
            Records.Connector.KIND_BALL);
        writer.endSection();
        writer.beginSection(Section.LASER);
        Records.Laser.put(writer, baseX + 120, baseY - 40, Math.PI / 4, 60, Records.ObjColor.RED);
        writer.endSection();
        writer.beginSection(Section.ASTEROID);
        Records.Asteroid.put(writer, baseX - 200, baseY + 40, 2, tick * 0.05);
        writer.endSection();
        writer.beginSection(Section.WRECKAGE);
        Records.Wreckage.put(writer, baseX - 200, baseY - 160, 1, 12, tick * 0.1);
        writer.endSection();
        if (tick % 28 < 6) {
            writer.beginSection(Section.ECM);
            Records.Ecm.put(writer, baseX + 200, baseY + 60, 40 + (tick % 28) * 10);
            writer.endSection();
        }
        writer.beginSection(Section.TIMERS);
        Records.Timers.put(writer, 0, 0, Math.max(0, 600 - tick / fps));
        writer.endSection();
    }

    private void begin(int type) {
        tx.clear();
        PacketHeader.write(tx, type, accepted ? SESSION : 0, channel.ack());
    }

    private void send() throws IOException {
        tx.flip();
        port.send(tx);
    }

    /** {@link Port} on a real UDP channel bound to 127.0.0.1 (one peer: the last sender). */
    static final class Udp implements Port, AutoCloseable {
        final DatagramChannel ch;
        SocketAddress peer;

        Udp() throws IOException {
            ch = DatagramChannel.open();
            ch.bind(new InetSocketAddress("127.0.0.1", 0));
            ch.configureBlocking(false);
        }

        int port() throws IOException {
            return ((InetSocketAddress) ch.getLocalAddress()).getPort();
        }

        @Override
        public boolean send(ByteBuffer b) throws IOException {
            if (peer != null) {
                ch.send(b, peer);
            }
            return true;
        }

        @Override
        public int receive(ByteBuffer b) throws IOException {
            int p = b.position();
            SocketAddress a = ch.receive(b);
            if (a == null) {
                return 0;
            }
            peer = a;
            return b.position() - p;
        }

        @Override
        public void close() throws IOException {
            ch.close();
        }
    }

    /** Serve on loopback UDP in a daemon thread at the map's tick rate; returns the bound port. */
    static int startUdp(Path mapFile) throws IOException {
        Udp udp = new Udp();
        ScriptedServer s = new ScriptedServer(mapFile, udp);
        Thread t = new Thread(() -> {
            long period = 1_000_000_000L / s.fps;
            long next = System.nanoTime();
            try {
                while (true) {
                    long now = System.nanoTime();
                    s.poll(now);
                    if (now >= next) {
                        s.step(now);
                        next += period;
                    }
                    Thread.sleep(1);
                }
            } catch (IOException | InterruptedException e) {
                System.err.println("scripted server stopped: " + e);
            }
        }, "scripted-server");
        t.setDaemon(true);
        t.start();
        return udp.port();
    }
}
