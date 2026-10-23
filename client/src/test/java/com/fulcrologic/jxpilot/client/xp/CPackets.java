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
package com.fulcrologic.jxpilot.client.xp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Test-side port of the C server's packet encoders: {@code Packet_printf} ({@code common/net.c}) and
 * the {@code Send_*} functions of {@code server/netserver.c} (and {@code Compress_map}), for version
 * 0x4501 clients unless a method says otherwise. Every method appends to one buffer; {@link #bytes()}
 * returns what was written. Allocates freely (tests only).
 */
final class CPackets {
    final ByteBuffer b = ByteBuffer.allocate(256 * 1024);

    CPackets reset() {
        b.clear();
        return this;
    }

    byte[] bytes() {
        byte[] a = new byte[b.position()];
        b.get(0, a);
        return a;
    }

    int size() {
        return b.position();
    }

    // ---------------------------------------------------------------- Packet_printf
    CPackets c(int v) {
        b.put((byte) v);
        return this;
    }

    CPackets hd(int v) {
        b.putShort((short) v);
        return this;
    }

    CPackets d(int v) {
        b.putInt(v);
        return this;
    }

    /** {@code %s} / {@code %S}: the string and its NUL. */
    CPackets s(String v) {
        b.put(v.getBytes(StandardCharsets.ISO_8859_1));
        b.put((byte) 0);
        return this;
    }

    CPackets raw(byte[] a) {
        b.put(a);
        return this;
    }

    // ---------------------------------------------------------------- contact replies
    /** {@code %u%c%c} (+ {@code %hu} when arg &gt;= 0). */
    CPackets contactReply(int magic, int to, int status, int arg) {
        d(magic).c(to).c(status);
        if (arg >= 0) {
            hd(arg);
        }
        return this;
    }

    // ---------------------------------------------------------------- frame packets (connp->w)
    CPackets start(int loops, int keyAck) {
        return c(XpProto.PKT_START).d(loops).d(keyAck);
    }

    CPackets end(int loops) {
        return c(XpProto.PKT_END).d(loops);
    }

    /** Send_self (version &gt;= 0x4203). */
    @SuppressWarnings("checkstyle:ParameterNumber")
    CPackets self(int x, int y, int vx, int vy, int dir, int power, int turnspeed, int turnres, int lockId,
                  int lockDist, int lockDir, int check, int curTank, int fuelSum, int fuelMax, int viewW, int viewH,
                  int debrisColors, int stat, int autopilot) {
        return c(XpProto.PKT_SELF).hd(x).hd(y).hd(vx).hd(vy).c(dir).c(power).c(turnspeed).c(turnres)
            .hd(lockId).hd(lockDist).c(lockDir).c(check).c(curTank).hd(fuelSum).hd(fuelMax)
            .hd(viewW).hd(viewH).c(debrisColors).c(stat).c(autopilot);
    }

    /** A plain PKT_SELF at (x, y) with a 768x768 view. */
    CPackets self(int x, int y) {
        return self(x, y, 0, 0, 32, 45, 35, 30, -1, 0, 0, 0, 0, 1000, 2600, 768, 768, 0, XpProto.STATUS_PLAYING, 0);
    }

    /** Send_self_items: mask of owned items, then one count per set bit. */
    CPackets selfItems(int[] items) {
        int mask = 0;
        for (int i = 0; i < items.length; i++) {
            if (items[i] > 0) {
                mask |= 1 << i;
            }
        }
        if (mask == 0) {
            return this;
        }
        c(XpProto.PKT_SELF_ITEMS).d(mask);
        for (int i = 0; i < items.length; i++) {
            if (items[i] > 0) {
                c(items[i]);
            }
        }
        return this;
    }

    CPackets modifiers(String m) {
        return c(XpProto.PKT_MODIFIERS).s(m);
    }

    CPackets ship(int x, int y, int id, int dir, boolean shield, boolean cloak, boolean eshield, boolean phased,
                  boolean deflector) {
        int f = (shield ? 1 : 0) | (cloak ? 2 : 0) | (eshield ? 4 : 0) | (phased ? 8 : 0) | (deflector ? 16 : 0);
        return c(XpProto.PKT_SHIP).hd(x).hd(y).hd(id).c(dir).c(f);
    }

    CPackets ship(int x, int y, int id, int dir) {
        return ship(x, y, id, dir, false, false, false, false, false);
    }

    CPackets refuel(int x0, int y0, int x1, int y1) {
        return c(XpProto.PKT_REFUEL).hd(x0).hd(y0).hd(x1).hd(y1);
    }

    CPackets connector(int x0, int y0, int x1, int y1, int tractor) {
        return c(XpProto.PKT_CONNECTOR).hd(x0).hd(y0).hd(x1).hd(y1).c(tractor);
    }

    CPackets laser(int color, int x, int y, int len, int dir) {
        return c(XpProto.PKT_LASER).c(color).hd(x).hd(y).hd(len).c(dir);
    }

    CPackets missile(int x, int y, int len, int dir) {
        return c(XpProto.PKT_MISSILE).hd(x).hd(y).c(len).c(dir);
    }

    CPackets ball(int x, int y, int id) {
        return c(XpProto.PKT_BALL).hd(x).hd(y).hd(id);
    }

    CPackets mine(int x, int y, int teammine, int id) {
        return c(XpProto.PKT_MINE).hd(x).hd(y).c(teammine).hd(id);
    }

    CPackets item(int x, int y, int type) {
        return c(XpProto.PKT_ITEM).hd(x).hd(y).c(type);
    }

    CPackets ecm(int x, int y, int size) {
        return c(XpProto.PKT_ECM).hd(x).hd(y).hd(size);
    }

    CPackets trans(int x1, int y1, int x2, int y2) {
        return c(XpProto.PKT_TRANS).hd(x1).hd(y1).hd(x2).hd(y2);
    }

    CPackets paused(int x, int y, int count) {
        return c(XpProto.PKT_PAUSED).hd(x).hd(y).hd(count);
    }

    CPackets radar(int x, int y, int size) {
        return c(XpProto.PKT_RADAR).hd(x).hd(y).c(size);
    }

    /** Frame_radar_buffer_send for 4.4.1+ clients: blips as (radar x, radar y, size) with C's packing. */
    CPackets fastRadar(int... xys) {
        int n = xys.length / 3;
        c(XpProto.PKT_FASTRADAR).c(n);
        for (int i = 0; i < n; i++) {
            int rx = xys[3 * i];
            int ry = xys[3 * i + 1];
            int size = xys[3 * i + 2];
            c(rx).c(ry & 0xFF);
            int last = (ry >> 2) & 0xC0;
            if ((size & 0x80) != 0) {
                last |= 0x20;
            }
            last |= size & 0x07;
            c(last);
        }
        return this;
    }

    CPackets damaged(int d) {
        return c(XpProto.PKT_DAMAGED).c(d);
    }

    CPackets destruct(int count) {
        return c(XpProto.PKT_DESTRUCT).hd(count);
    }

    CPackets countMax(int type, int count, int max) {
        return c(type).hd(count).hd(max);
    }

    CPackets timeLeft(int sec) {
        return c(XpProto.PKT_TIME_LEFT).d(sec);
    }

    CPackets eyes(int id) {
        return c(XpProto.PKT_EYES).hd(id);
    }

    CPackets audio(int type, int vol) {
        return c(XpProto.PKT_AUDIO).c(type).c(vol);
    }

    CPackets loseItem(int i) {
        return c(XpProto.PKT_LOSEITEM).c(i);
    }

    CPackets wreckage(int x, int y, int type, int size, int rot) {
        return c(XpProto.PKT_WRECKAGE).hd(x).hd(y).c(type).c(size).c(rot);
    }

    CPackets asteroid(int x, int y, int type, int size, int rot) {
        return c(XpProto.PKT_ASTEROID).hd(x).hd(y).c(((type & 0x0F) << 4) | (size & 0x0F)).c(rot);
    }

    CPackets wormhole(int x, int y) {
        return c(XpProto.PKT_WORMHOLE).hd(x).hd(y);
    }

    /** Send_debris: PKT_DEBRIS + type, n, n (x, y) pairs. */
    CPackets debris(int type, int... xy) {
        c(XpProto.PKT_DEBRIS + type).c(xy.length / 2);
        for (int v : xy) {
            c(v);
        }
        return this;
    }

    /** Send_fastshot: PKT_FASTSHOT, type, n, pairs. */
    CPackets fastshot(int type, int... xy) {
        c(XpProto.PKT_FASTSHOT).c(type).c(xy.length / 2);
        for (int v : xy) {
            c(v);
        }
        return this;
    }

    /** Send_fuel: fuel is fixed point, sent &gt;&gt; FUEL_SCALE_BITS. */
    CPackets fuel(int num, int fuelFixed) {
        return c(XpProto.PKT_FUEL).hd(num).hd(fuelFixed >> XpProto.FUEL_SCALE_BITS);
    }

    CPackets cannon(int num, int deadTime) {
        return c(XpProto.PKT_CANNON).hd(num).hd(deadTime);
    }

    CPackets target(int num, int deadTime, int damage) {
        return c(XpProto.PKT_TARGET).hd(num).hd(deadTime).hd(damage);
    }

    /** Send_reliable: {@code %c%hd%ld%ld} then the data. */
    CPackets reliable(int rel, int loops, byte[] data) {
        return c(XpProto.PKT_RELIABLE).hd(data.length).d(rel).d(loops).raw(data);
    }

    CPackets quit(String reason) {
        return c(XpProto.PKT_QUIT).s(reason);
    }

    // ---------------------------------------------------------------- reliable stream (connp->c)
    CPackets reply(int to, int result) {
        return c(XpProto.PKT_REPLY).c(to).c(result);
    }

    CPackets magic(int m) {
        return c(XpProto.PKT_MAGIC).d(m);
    }

    CPackets message(String m) {
        return c(XpProto.PKT_MESSAGE).s(m);
    }

    /** Send_player (version &gt; 0x3200: shape and extension). */
    CPackets player(int id, int team, int mychar, String name, String real, String host, String shape, String ext) {
        return c(XpProto.PKT_PLAYER).hd(id).c(team).c(mychar).s(name).s(real).s(host).s(shape).s(ext);
    }

    /** Send_score for 0x4500+ clients: score * 100 rounded away from zero. */
    CPackets score(int id, double score, int life, int mychar, int allchar) {
        int sc = (int) (score * 100 + (score > 0 ? 0.5 : -0.5));
        return c(XpProto.PKT_SCORE).hd(id).d(sc).hd(life).c(mychar).c(allchar);
    }

    /** Send_score for clients before 0x4500. */
    CPackets scoreOld(int id, double score, int life, int mychar) {
        return c(XpProto.PKT_SCORE).hd(id).hd((int) (score + (score > 0 ? 0.5 : -0.5))).hd(life).c(mychar);
    }

    CPackets teamScore(int team, double score) {
        return c(XpProto.PKT_TEAM_SCORE).hd(team).d((int) (score * 100 + (score > 0 ? 0.5 : -0.5)));
    }

    CPackets timing(int id, int check, int round) {
        return c(XpProto.PKT_TIMING).hd(id).hd(round * XpProto.MAX_CHECKS + check);
    }

    CPackets base(int id, int num) {
        return c(XpProto.PKT_BASE).hd(id).hd(num);
    }

    CPackets leave(int id) {
        return c(XpProto.PKT_LEAVE).hd(id);
    }

    CPackets war(int robot, int killer) {
        return c(XpProto.PKT_WAR).hd(robot).hd(killer);
    }

    CPackets seek(int p, int r, int s) {
        return c(XpProto.PKT_SEEK).hd(p).hd(r).hd(s);
    }

    CPackets talkAck(int seq) {
        return c(XpProto.PKT_TALK_ACK).d(seq);
    }

    CPackets string(int type, int a1, int a2) {
        return c(XpProto.PKT_STRING).c(type).hd(a1).hd(a2);
    }

    CPackets scoreObject(double score, int x, int y, String text) {
        return c(XpProto.PKT_SCORE_OBJECT).d((int) (score * 100 + (score > 0 ? 0.5 : -0.5))).hd(x).hd(y).s(text);
    }

    CPackets motd(int offset, byte[] data, int size) {
        return c(XpProto.PKT_MOTD).d(offset).hd(data.length).d(size).raw(data);
    }

    /** Handle_setup header. */
    CPackets setupHeader(int len, int mode, int lives, int x, int y, int fps, int order, String name, String author) {
        return d(len).d(mode).hd(lives).hd(x).hd(y).hd(fps).hd(order).s(name).s(author);
    }

    // ---------------------------------------------------------------- maps

    /** The setup code of an .xp map char (inverse of map.c + Init_setup; timing decides checkpoints). */
    static int setupCode(char ch, boolean teamPlay, boolean timing) {
        if (ch >= '0' && ch <= '9') {
            return XpProto.SETUP_BASE_UP + (teamPlay ? ch - '0' : 0);
        }
        if (ch >= 'A' && ch <= 'Z') {
            return timing ? XpProto.SETUP_CHECK + ch - 'A' : XpProto.SETUP_SPACE;
        }
        return switch (ch) {
            case 'x' -> XpProto.SETUP_FILLED;
            case '#' -> XpProto.SETUP_FUEL;
            case 'a' -> XpProto.SETUP_REC_RU;
            case 'q' -> XpProto.SETUP_REC_RD;
            case 's' -> XpProto.SETUP_REC_LU;
            case 'w' -> XpProto.SETUP_REC_LD;
            case 'r' -> XpProto.SETUP_CANNON_UP;
            case 'f' -> XpProto.SETUP_CANNON_RIGHT;
            case 'c' -> XpProto.SETUP_CANNON_DOWN;
            case 'd' -> XpProto.SETUP_CANNON_LEFT;
            case '*', '^' -> XpProto.SETUP_TREASURE;
            case '!' -> XpProto.SETUP_TARGET;
            case '%' -> XpProto.SETUP_ITEM_CONCENTRATOR;
            case '&' -> XpProto.SETUP_ASTEROID_CONCENTRATOR;
            case '_' -> XpProto.SETUP_BASE_UP;
            case '+' -> XpProto.SETUP_POS_GRAV;
            case '-' -> XpProto.SETUP_NEG_GRAV;
            case '>' -> XpProto.SETUP_CWISE_GRAV;
            case '<' -> XpProto.SETUP_ACWISE_GRAV;
            case 'i' -> XpProto.SETUP_UP_GRAV;
            case 'm' -> XpProto.SETUP_DOWN_GRAV;
            case 'k' -> XpProto.SETUP_RIGHT_GRAV;
            case 'j' -> XpProto.SETUP_LEFT_GRAV;
            case '@' -> XpProto.SETUP_WORM_NORMAL;
            case '(' -> XpProto.SETUP_WORM_IN;
            case ')' -> XpProto.SETUP_WORM_OUT;
            case 'b' -> XpProto.SETUP_DECOR_FILLED;
            case 'g' -> XpProto.SETUP_DECOR_RU;
            case 't' -> XpProto.SETUP_DECOR_RD;
            case 'h' -> XpProto.SETUP_DECOR_LU;
            case 'y' -> XpProto.SETUP_DECOR_LD;
            default -> XpProto.SETUP_SPACE;
        };
    }

    /** Init_setup's block array (index x * h + y, y up) from map rows (first row = top). */
    static byte[] setupBlocks(String[] rows, int w, int h, boolean teamPlay, boolean timing) {
        byte[] out = new byte[w * h];
        for (int r = 0; r < h; r++) {
            String row = r < rows.length ? rows[r] : "";
            int y = h - 1 - r;
            for (int x = 0; x < w; x++) {
                char ch = x < row.length() ? row.charAt(x) : ' ';
                out[x * h + y] = (byte) setupCode(ch, teamPlay, timing);
            }
        }
        return out;
    }

    /** Port of netserver.c Compress_map (in place in C; here into a new array). */
    static byte[] compress(byte[] map) {
        byte[] m = map.clone();
        int size = m.length;
        int j = 0;
        for (int i = 0; i < size; i++, j++) {
            if (i + 1 < size && m[i] == m[i + 1]) {
                int k;
                for (k = 2; i + k < size; k++) {
                    if (m[i] != m[i + k]) {
                        break;
                    }
                    if (k == 255) {
                        break;
                    }
                }
                m[j] = (byte) (m[i] | XpProto.SETUP_COMPRESSED);
                m[++j] = (byte) k;
                i += k - 1;
            } else {
                m[j] = m[i];
            }
        }
        byte[] out = new byte[j];
        System.arraycopy(m, 0, out, 0, j);
        return out;
    }
}
