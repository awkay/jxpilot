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
package com.fulcrologic.jxpilot.common.sim;

import com.fulcrologic.jxpilot.common.Item;
import com.fulcrologic.jxpilot.common.math.ShipShape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ShipBody: position helpers (objpos.c), status/ability predicates and the single-tank Add_fuel. */
class ShipBodyTest {
    @Nested
    @DisplayName("position")
    class Position {
        @Test
        void clicksDerivePixelsByShiftAndBlocksByTruncation() {
            ShipBody p = new ShipBody();
            p.setClicks(35 * 64 + 63, -1);
            assertAll(
                () -> assertEquals(35 * 64 + 63, p.cx, "clicks are stored"),
                () -> assertEquals(35, p.px, "pixel x = clicks >> 6"),
                () -> assertEquals(-1, p.py, "negative clicks floor to pixel -1"),
                () -> assertEquals(1, p.bx, "block x = 35 / 35"),
                () -> assertEquals(0, p.by, "pixel -1 is in block 0: C pos.x / BLOCK_SZ truncates toward zero"));
        }

        @Test
        void initPixelsMakesItThePreviousPosition() {
            ShipBody p = new ShipBody();
            p.setClicks(0, 0);
            p.initPixels(100.75, 50.5);
            ShipBody q = new ShipBody();
            q.initPixels(4252.50001, 1.0000001);
            assertAll(
                () -> assertEquals(6448, p.cx, "100.75 px = 6448 clicks"),
                () -> assertEquals(3232, p.cy, "50.5 px = 3232 clicks"),
                () -> assertEquals(100, p.prevX, "previous x = new pixel x"),
                () -> assertEquals(50, p.prevY, "previous y = new pixel y"),
                () -> assertEquals(272160, q.cx, "the pixel argument is a C float: 4252.50001 rounds to 4252.5f first"),
                () -> assertEquals(64, q.cy, "1.0000001 rounds to the float 1.0 (a double would give 64 too)"));
        }

        @Test
        void rememberCopiesThePixelPosition() {
            ShipBody p = new ShipBody();
            p.initPixels(10, 20);
            p.setClicks(64 * 30, 64 * 40);
            p.remember();
            assertAll(
                () -> assertEquals(30, p.prevX, "previous x"),
                () -> assertEquals(40, p.prevY, "previous y"));
        }
    }

    @Nested
    @DisplayName("predicates")
    class Predicates {
        @Test
        void aliveMeansPlayingAndNotPausedOverOrKilled() {
            ShipBody p = new ShipBody();
            p.status = Obj.PLAYING | Obj.GRAVITY | Obj.THRUSTING;
            boolean playing = p.isAlive();
            p.status = Obj.PLAYING | Obj.PAUSE;
            boolean paused = p.isAlive();
            p.status = Obj.PLAYING | Obj.GAME_OVER;
            boolean over = p.isAlive();
            p.status = Obj.PLAYING | Obj.KILLED;
            boolean killed = p.isAlive();
            p.status = Obj.GRAVITY;
            assertAll(
                () -> assertTrue(playing, "PLAYING (other bits irrelevant) is alive"),
                () -> assertFalse(paused, "paused is not"),
                () -> assertFalse(over, "game over is not"),
                () -> assertFalse(killed, "killed is not"),
                () -> assertFalse(p.isAlive(), "not playing is not"));
        }

        @Test
        void fullyShieldedNeedsBothShieldBits() {
            ShipBody p = new ShipBody();
            p.used = Has.SHIELD;
            boolean one = p.fullyShielded();
            p.used = Has.EMERGENCY_SHIELD;
            boolean other = p.fullyShielded();
            p.used = Has.SHIELD | Has.EMERGENCY_SHIELD | Has.COMPASS;
            assertAll(
                () -> assertFalse(one, "shield alone is not full"),
                () -> assertFalse(other, "emergency shield alone is not full"),
                () -> assertTrue(p.fullyShielded(), "both are"));
        }

        @Test
        void emergencyThrustMeansMaximumAfterburners() {
            ShipBody p = new ShipBody();
            p.items[Item.AFTERBURNER.ordinal()] = 3;
            int items = p.afterburners();
            p.used = Has.EMERGENCY_THRUST;
            assertAll(
                () -> assertEquals(3, items, "the afterburner item count"),
                () -> assertEquals(15, p.afterburners(), "MAX_AFTERBURNER with emergency thrust"));
        }
    }

    @Nested
    @DisplayName("addFuel (ship.c Add_fuel)")
    class AddFuel {
        @Test
        @DisplayName("the change is truncated to a C long, then clamped so the sum stays in [0, max]")
        void fuelIsClampedToTheTank() {
            ShipBody p = new ShipBody();
            p.fuelMax = 1000;
            p.fuel = 500;
            p.tankFuel[0] = 500;
            p.addFuel(250.5);
            double up = p.fuel;
            p.addFuel(600);
            double full = p.fuel;
            p.addFuel(-400.75);
            double down = p.fuel;
            p.addFuel(-2000);
            assertAll(
                () -> assertEquals(750.0, up, "250.5 is added as (long) 250"),
                () -> assertEquals(1000.0, full, "at most the maximum"),
                () -> assertEquals(600.0, down, "-400.75 is taken as (long) -400"),
                () -> assertEquals(0.0, p.fuel, "at least 0"),
                () -> assertEquals(0.0, p.tankFuel[0], "the main tank follows the sum"));
        }

        @Test
        @DisplayName("the change goes to the current tank only")
        void currentTank() {
            ShipBody p = new ShipBody();
            p.fuelMax = 3000;
            p.fuel = 1500;
            p.numTanks = 2;
            p.tankFuel[0] = 1000;
            p.tankFuel[1] = 300;
            p.tankFuel[2] = 200;
            p.currentTank = 2;
            p.addFuel(-150);
            p.addFuel(2000);
            assertAll(
                () -> assertEquals(3000.0, p.fuel, "sum: 1500 - 150, then up to the maximum 3000"),
                () -> assertEquals(1000.0, p.tankFuel[0], "the main tank is not touched"),
                () -> assertEquals(300.0, p.tankFuel[1], "tank 1 is not touched"),
                () -> assertEquals(200 - 150 + 1650.0, p.tankFuel[2], "tank 2 takes both changes (the second clamped)"));
        }
    }

    @Nested
    @DisplayName("hitArmor (C Player_hit_armor)")
    class Armor {
        @Test
        @DisplayName("one armor is used up; the last one (or fewer) removes HAS_ARMOR")
        void armor() {
            ShipBody p = new ShipBody();
            p.have = Has.ARMOR | Has.SHIELD;
            p.items[Item.ARMOR.ordinal()] = 2;
            p.hitArmor();
            int left = p.items[Item.ARMOR.ordinal()];
            int haveLeft = p.have;
            p.hitArmor();
            assertAll(
                () -> assertEquals(1, left, "one piece is used"),
                () -> assertEquals(Has.ARMOR | Has.SHIELD, haveLeft, "armor stays while pieces are left"),
                () -> assertEquals(0, p.items[Item.ARMOR.ordinal()], "the last piece is used"),
                () -> assertEquals(Has.SHIELD, p.have, "and the ability goes, other abilities kept"));
        }
    }

    /** A body with a distinct value in every field (fields not on the wire included). */
    /** The frame the wire tests encode at: 100 frames after full()'s shot. */
    static final long F = (1L << 40) + 100;

    static ShipBody full() {
        ShipBody b = new ShipBody();
        b.setClicks(123456, -654);
        b.prevX = 11;
        b.prevY = 12;
        b.vx = -1.25e-7f;
        b.vy = 3.5f;
        b.accX = 0.5f;
        b.accY = -0.75f;
        b.velocity = 3.5000002f;
        b.mass = 25.123f;
        b.emptyMass = 20.5f;
        b.status = 0x12345;
        b.have = 0x7654321;
        b.used = -0x2345;
        b.dir = 127;
        b.floatDir = 126.6f;
        b.turnVel = -0.0f;
        b.oldTurnVel = 4.2f;
        b.turnAcc = -35f;
        b.turnSpeed = 33f;
        b.turnResistance = 0.11f;
        b.power = 55.5f;
        b.powerS = 44.4f;
        b.turnSpeedS = 22.2f;
        b.turnResistanceS = 0.33f;
        b.fuel = 123456;
        b.fuelMax = 2600 * 256 * 3;
        b.tankFuel[1] = 7;
        b.numTanks = 2;
        b.currentTank = 1;
        b.shieldTime = 7000;
        b.stunned = 9;
        b.shots = 300;
        b.shotMax = 256;
        b.shotTime = 1L << 40;
        b.mods = 0x5a5a;
        b.team = 3;
        for (int i = 0; i < Item.COUNT; i++) {
            b.items[i] = 99 - i * 4;
        }
        b.wormHoleDest = 300;
        b.lastWallTouch = (1L << 33) + 5;
        b.frameLastBusy = 77;
        b.prevKeys = 0x8000_0000_0000_0001L;
        b.lastReceivedKeys = 0x7fff_ffff_ffff_fffeL;
        b.keysCleared = true;
        b.ship = new ShipGeom(ShipShape.defaultShip());
        return b;
    }

    @Nested
    @DisplayName("copy, compare and the wire codec")
    class Wire {
        @Test
        @DisplayName("copyFrom copies every field and the hull, and the copy is independent")
        void copy() {
            ShipBody a = full();
            ShipBody b = new ShipBody();
            b.copyFrom(a);
            boolean same = b.sameState(a, F);
            int prevX = b.prevX;
            float accY = b.accY;
            long busy = b.frameLastBusy;
            double tank = b.tankFuel[1];
            ShipGeom hull = b.ship;
            a.items[3] = 1;
            a.tankFuel[1] = 0;
            assertAll(
                () -> assertTrue(same, "every wire field is copied"),
                () -> assertEquals(11, prevX, "and the previous position"),
                () -> assertEquals(-0.75f, accY, "the acceleration"),
                () -> assertEquals(77L, busy, "the busy frame"),
                () -> assertEquals(7.0, tank, "the tank fuel"),
                () -> assertTrue(hull == a.ship, "the same hull"),
                () -> assertEquals(99 - 12, b.items[3], "later changes to the original do not reach the copy's items"),
                () -> assertEquals(7.0, b.tankFuel[1], "nor its tanks"));
        }

        @Test
        @DisplayName("encode writes WIRE_SIZE bytes at the offset without moving the position; decode restores the state")
        void roundTrip() {
            ShipBody a = full();
            for (ByteOrder order : new ByteOrder[] {ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN}) {
                ByteBuffer buf = ByteBuffer.allocate(ShipBody.WIRE_SIZE + 20).order(order);
                buf.position(3);
                int n = a.encode(buf, 7, F);
                ShipBody b = new ShipBody();
                b.prevX = -9;
                b.accX = 9;
                b.frameLastBusy = 9;
                b.tankFuel[1] = 9;
                b.lastWallTouch = 9;
                b.numTanks = 9;
                ShipGeom hull = b.ship;
                int m = b.decode(buf, 7, F);
                assertAll(
                    () -> assertEquals(ShipBody.WIRE_SIZE, n, order + ": encode returns WIRE_SIZE"),
                    () -> assertEquals(ShipBody.WIRE_SIZE, m, order + ": decode returns WIRE_SIZE"),
                    () -> assertEquals(3, buf.position(), order + ": the buffer position does not move"),
                    () -> assertTrue(b.sameState(a, F), order + ": every wire field round-trips bit for bit"),
                    () -> assertEquals(a.px, b.px, order + ": pixels are derived from the clicks"),
                    () -> assertEquals(a.py, b.py, order + ": y too"),
                    () -> assertEquals(a.bx, b.bx, order + ": blocks too"),
                    () -> assertEquals(a.by, b.by, order + ": y block too"),
                    () -> assertEquals(-9, b.prevX, order + ": the previous position is not on the wire"),
                    () -> assertEquals(9f, b.accX, order + ": nor the acceleration"),
                    () -> assertEquals(9L, b.frameLastBusy, order + ": nor the busy frame"),
                    () -> assertEquals(9.0, b.tankFuel[1], order + ": nor the tank fuel"),
                    () -> assertEquals(9L, b.lastWallTouch, order + ": nor the last wall touch"),
                    () -> assertEquals(9, b.numTanks, order + ": nor the tank count"),
                    () -> assertTrue(hull == b.ship, order + ": nor the hull"),
                    () -> assertEquals(Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits(b.turnVel),
                        order + ": -0.0 keeps its sign"));
            }
        }

        @Test
        @DisplayName("the canonical layout writes nothing outside its WIRE_SIZE bytes, and every byte matters but one")
        void layout() {
            ByteBuffer buf = ByteBuffer.allocate(ShipBody.WIRE_SIZE + 2);
            for (int i = 0; i < buf.capacity(); i++) {
                buf.put(i, (byte) 0x5c);
            }
            full().encode(buf, 1, F);
            ShipBody ref = new ShipBody();
            ref.decode(buf, 1, F);
            int sensitive = 0;
            for (int i = 1; i <= ShipBody.WIRE_SIZE; i++) {
                ByteBuffer c = ByteBuffer.allocate(buf.capacity());
                c.put(0, buf, 0, buf.capacity());
                c.put(i, (byte) (c.get(i) ^ 0x01));
                ShipBody d = new ShipBody();
                d.decode(c, 1, F);
                if (!d.sameState(ref, F)) {
                    sensitive++;
                }
            }
            int sens = sensitive;
            assertAll(
                () -> assertEquals((byte) 0x5c, buf.get(0), "the byte before is untouched"),
                () -> assertEquals((byte) 0x5c, buf.get(ShipBody.WIRE_SIZE + 1), "the byte after is untouched"),
                () -> assertEquals(ShipBody.WIRE_SIZE - 1, sens, "flipping the low bit of any byte changes the decoded"
                    + " state, except in the flags byte (the prevKeys flag: the canonical form always carries the keys"
                    + " block)"));
        }

        @Test
        @DisplayName("sameState is bit equality of the wire fields: each field matters, the others do not")
        void sameState() {
            ShipBody a = full();
            int differ = 0;
            int fields = 0;
            Runnable[] changes = {
                () -> { }, () -> a.cx++, () -> a.cy++, () -> a.vx = Math.nextUp(a.vx), () -> a.vy = -a.vy,
                () -> a.velocity++, () -> a.mass++, () -> a.emptyMass++, () -> a.status++, () -> a.have++,
                () -> a.used++, () -> a.dir--, () -> a.floatDir++, () -> a.turnVel = 0.0f, () -> a.oldTurnVel++,
                () -> a.turnAcc++, () -> a.turnSpeed++, () -> a.turnResistance++, () -> a.power++, () -> a.powerS++,
                () -> a.turnSpeedS++, () -> a.turnResistanceS++, () -> a.shieldTime++, () -> a.fuel++,
                () -> a.fuelMax++, () -> a.stunned++, () -> a.shots++,
                () -> a.shotMax++, () -> a.mods++, () -> a.shotTime++, () -> a.team++, () -> a.wormHoleDest++,
                () -> a.prevKeys++, () -> a.lastReceivedKeys++,
                () -> a.keysCleared = false, () -> a.items[0]++, () -> a.items[Item.COUNT - 1]++
            };
            for (int i = 1; i < changes.length; i++) {
                ShipBody b = full();
                a.copyFrom(b);
                changes[i].run();
                fields++;
                if (!a.sameState(b, F)) {
                    differ++;
                }
            }
            ShipBody x = full();
            ShipBody y = full();
            y.prevX++;
            y.accX++;
            y.frameLastBusy++;
            y.tankFuel[0]++;
            y.px++;
            y.lastWallTouch++;
            y.numTanks++;
            y.currentTank++;
            int all = fields;
            int seen = differ;
            assertAll(
                () -> assertEquals(all, seen, "a change to any wire field is seen"),
                () -> assertTrue(x.sameState(full(), F), "equal states are the same"),
                () -> assertTrue(x.sameState(y, F), "fields that are not on the wire are ignored"));
        }

        @Test
        @DisplayName("the three blocks: 63 + 59 + 16 bytes; key flags against the applied keys; the keys block on mismatch")
        void blocks() {
            ShipBody a = full();
            long applied = a.prevKeys;
            ByteBuffer core = ByteBuffer.allocate(ShipBody.CORE_SIZE);
            int n = a.encodeCore(core, 0, F, applied);
            int flags = core.get(ShipBody.CORE_FLAGS);
            core.put(ShipBody.CORE_FLAGS, (byte) (flags | ~ShipBody.FLAGS_MASK));
            boolean differ = a.keysDiffer(applied);
            ShipBody b = new ShipBody();
            b.prevKeys = 5;
            b.lastReceivedKeys = 6;
            int coreRead = b.decodeCore(core, 0, F, applied);
            long keptReceived = b.lastReceivedKeys;
            ByteBuffer keys = ByteBuffer.allocate(ShipBody.KEYS_SIZE);
            int k = a.encodeKeys(keys, 0);
            b.decodeKeys(keys, 0);
            ByteBuffer rare = ByteBuffer.allocate(ShipBody.RARE_SIZE);
            int r = a.encodeRare(rare, 0);
            b.decodeRare(rare, 0);
            ShipBody same = full();
            same.lastReceivedKeys = same.prevKeys;
            ShipBody other = full();
            other.keysCleared = false;
            ByteBuffer core2 = ByteBuffer.allocate(ShipBody.CORE_SIZE);
            other.encodeCore(core2, 0, F, 99);
            ShipBody d = new ShipBody();
            d.prevKeys = 5;
            d.keysCleared = true;
            d.decodeCore(core2, 0, F, 99);
            int rareRead = d.decodeRare(rare, 0);
            int keysRead = d.decodeKeys(keys, 0);
            assertAll(
                () -> assertEquals(63, n, "core 63 bytes"),
                () -> assertEquals(59, r, "rare 59 bytes"),
                () -> assertEquals(16, k, "keys 16 bytes"),
                () -> assertEquals(ShipBody.FLAG_PREV_KEYS | ShipBody.FLAG_KEYS_CLEARED, flags,
                    "prevKeys equals the applied keys, lastReceivedKeys does not; keysCleared"),
                () -> assertTrue(differ, "so the keys block must follow"),
                () -> assertFalse(same.keysDiffer(same.prevKeys), "both equal: no keys block"),
                () -> assertEquals(ShipBody.CORE_SIZE, coreRead, "decodeCore returns its size"),
                () -> assertEquals(applied, b.prevKeys, "a flagged mask is the applied keys"),
                () -> assertEquals(6L, keptReceived, "an unflagged one waits for the keys block"),
                () -> assertTrue(b.sameState(a, F),
                    "core + rare + keys restore the state (flag bits beyond FLAGS_MASK are ignored)"),
                () -> assertEquals(ShipBody.RARE_SIZE, rareRead, "decodeRare returns its size"),
                () -> assertEquals(ShipBody.KEYS_SIZE, keysRead, "decodeKeys returns its size"));
            ShipBody e = new ShipBody();
            e.prevKeys = 5;
            e.keysCleared = true;
            e.decodeCore(core2, 0, F, 99);
            assertAll(
                () -> assertEquals(5L, e.prevKeys, "a clear prevKeys flag leaves prevKeys to the keys block"),
                () -> assertFalse(e.keysCleared, "keysCleared false is read as false"));
        }

        @Test
        @DisplayName("narrow fields: shot age saturates, u16 counts clamp, clicks are signed 24-bit, items clamp to a byte, fuel is u24")
        void narrow() {
            ShipBody a = full();
            a.shotTime = F - 70_000;
            a.shieldTime = 70_000;
            a.stunned = -3;
            a.items[2] = 300;
            a.setClicks(-(1 << 23), (1 << 23) - 1);
            a.mods = 70_000;
            a.fuel = 20_000_000;
            a.fuelMax = ShipBody.MAX_FUEL;
            ByteBuffer buf = ByteBuffer.allocate(ShipBody.WIRE_SIZE);
            a.encode(buf, 0, F);
            ShipBody b = new ShipBody();
            b.decode(buf, 0, F);
            ShipBody neg = full();
            neg.fuel = -5;
            neg.fuelMax = 3;
            ByteBuffer buf2 = ByteBuffer.allocate(ShipBody.WIRE_SIZE);
            neg.encode(buf2, 0, F);
            ShipBody c = new ShipBody();
            c.decode(buf2, 0, F);
            ShipBody old = full();
            old.shotTime = F - 80_000;
            ShipBody older = full();
            older.shotTime = F - 70_000;
            ShipBody future = full();
            future.shotTime = F + 5;
            ShipBody now = full();
            now.shotTime = F;
            assertAll(
                () -> assertEquals(F - ShipBody.MAX_SHOT_AGE, b.shotTime, "a shot 70000 frames ago reads 65535 ago"),
                () -> assertTrue(old.sameState(older, F), "two shots older than the cap are the same state"),
                () -> assertTrue(future.sameState(now, F), "a shot time after frames reads as age 0"),
                () -> assertEquals(65535, b.shieldTime, "shieldTime clamps to 65535"),
                () -> assertEquals(0, b.stunned, "a negative count clamps to 0"),
                () -> assertEquals(255, b.items[2], "an item count clamps to 255"),
                () -> assertEquals(-(1 << 23), b.cx, "the smallest i24 click"),
                () -> assertEquals((1 << 23) - 1, b.cy, "the largest"),
                () -> assertEquals(ShipBody.MAX_FUEL, b.fuel, "fuel above the u24 range clamps to MAX_FUEL"),
                () -> assertEquals(ShipBody.MAX_FUEL, b.fuelMax, "fuelMax keeps the largest u24"),
                () -> assertEquals(65535, b.mods, "mods clamps to u16"),
                () -> assertEquals(0.0, c.fuel, "negative fuel reads 0"),
                () -> assertEquals(3.0, c.fuelMax, "a small fuelMax is exact"));
        }
    }
}
