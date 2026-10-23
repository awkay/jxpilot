package com.fulcrologic.jxpilot.client.map;

import com.fulcrologic.jxpilot.common.Const;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FullMapStateTest {
    @Test
    void everythingIsAliveAndFull() {
        FullMapState s = new FullMapState();
        s.setLoops(42);
        assertAll(
            () -> assertEquals(Const.MAX_STATION_FUEL, s.fuel(3), "full fuel"),
            () -> assertTrue(s.cannonAlive(1), "cannons alive"),
            () -> assertTrue(s.targetAlive(1), "targets alive"),
            () -> assertEquals(Const.TARGET_DAMAGE, s.targetDamage(1), "targets undamaged"),
            () -> assertEquals(Const.TEAM_NOT_SET, s.selfTeam(), "no team"),
            () -> assertEquals(0, s.nextCheckpoint(), "first checkpoint next"),
            () -> assertEquals(42, s.loops(), "frame counter as set"));
    }
}
