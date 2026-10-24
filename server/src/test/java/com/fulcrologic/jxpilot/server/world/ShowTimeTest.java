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
package com.fulcrologic.jxpilot.server.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** C {@code showtime()} (showtime.c). */
class ShowTimeTest {
    private static final DateTimeFormatter C = DateTimeFormatter.ofPattern("dd MMM HH:mm:ss", Locale.ENGLISH);

    private static String show(long millis, String zone) {
        return ShowTime.append(new StringBuilder(), millis, TimeZone.getTimeZone(zone)).toString();
    }

    private static String javaTime(long millis, String zone) {
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), TimeZone.getTimeZone(zone).toZoneId()).format(C);
    }

    @Test
    @DisplayName("\"DD Mon HH:MM:SS\" in the zone, including leap days, year ends, DST and times before 1970")
    void format() {
        long[] instants = {0L, -1L, 951_782_400_000L, 1_709_164_799_999L, 1_735_689_599_000L, 1_792_825_509_000L,
            1_710_054_000_000L, -2_208_988_800_000L, 4_102_444_800_000L};
        String[] zones = {"UTC", "GMT-05:00", "America/Chicago", "Asia/Kolkata", "Pacific/Chatham"};
        assertAll(
            () -> assertEquals("24 Oct 07:05:09", show(1_792_825_509_000L, "UTC"), "a plain date"),
            () -> assertEquals("31 Dec 23:59:59", show(-1000L, "UTC"), "the second before 1970"),
            () -> assertEquals("29 Feb 00:00:00", show(951_782_400_000L, "UTC"), "a leap day (2000)"),
            () -> {
                for (long t : instants) {
                    for (String z : zones) {
                        assertEquals(javaTime(t, z), show(t, z), "as java.time at " + t + " in " + z);
                    }
                }
            });
    }
}
