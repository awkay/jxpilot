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

import java.util.TimeZone;

/**
 * C {@code showtime()} (showtime.c): the local time as {@code "DD Mon HH:MM:SS"} for server log lines, without
 * allocating (robots log their start while the game runs).
 */
public final class ShowTime {
    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct",
        "Nov", "Dec"};

    private ShowTime() {}

    /**
     * Append the local time of {@code millis} (epoch milliseconds) in {@code zone}.
     * <p>
     * BEHAVIOR: two-digit day, the English month abbreviation, two-digit hours, minutes and seconds, as C's
     * {@code "%02d %s %02d:%02d:%02d"}.
     */
    public static StringBuilder append(StringBuilder sb, long millis, TimeZone zone) {
        long local = millis + zone.getOffset(millis);
        long days = Math.floorDiv(local, 86_400_000L);
        int secs = (int) (Math.floorMod(local, 86_400_000L) / 1000);
        // civil-from-days (H. Hinnant): day and month of the proleptic Gregorian calendar
        long z = days + 719_468;
        long era = Math.floorDiv(z, 146_097);
        long doe = z - era * 146_097;
        long yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
        long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        long mp = (5 * doy + 2) / 153;
        int day = (int) (doy - (153 * mp + 2) / 5 + 1);
        int month = (int) (mp < 10 ? mp + 3 : mp - 9);
        two(sb, day).append(' ').append(MONTHS[month - 1]).append(' ');
        two(sb, secs / 3600).append(':');
        two(sb, secs / 60 % 60).append(':');
        return two(sb, secs % 60);
    }

    private static StringBuilder two(StringBuilder sb, int v) {
        return sb.append((char) ('0' + v / 10)).append((char) ('0' + v % 10));
    }
}
