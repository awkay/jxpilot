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
package com.fulcrologic.jxpilot.server.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.util.function.Supplier;

/**
 * C {@code Server_log_admin_message} (server.c:731-765): chat addressed to {@code god:} is appended to the
 * {@code adminMessageFileName} file (edge: file I/O, only when a player talks to god).
 */
final class AdminLog {
    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct",
        "Nov", "Dec"};

    private final Path file;
    private final int sizeLimit;
    private final Supplier<ZonedDateTime> clock;

    /** @param file the log file, or null for none; @param sizeLimit adminMessageFileSizeLimit */
    AdminLog(Path file, int sizeLimit, Supplier<ZonedDateTime> clock) {
        this.file = file;
        this.sizeLimit = sizeLimit;
        this.clock = clock;
    }

    /**
     * Log one message.
     * <p>
     * BEHAVIOR: only into a file that already exists and is writable, with a positive size limit, while
     * size + 80 is below the limit and the room left (limit - size - 80) exceeds the message length.
     * BEHAVIOR: the entry is {@code "DD Mon HH:MM:SS[name]{@()|}:\n\ttext\n"} (C writes real name, host, address
     * and display between the braces; JXPilot knows none of them). Returns whether it was written.
     * BEHAVIOR: without a file name nothing is allocated (chat stays allocation free).
     *
     * @param line the chat line; the message is {@code line[start..]}
     */
    boolean log(CharSequence name, CharSequence line, int start) {
        if (file == null || sizeLimit <= 0 || !Files.isRegularFile(file) || !Files.isWritable(file)) {
            return false;
        }
        try {
            long size = Files.size(file);
            if (size + 80 >= sizeLimit || sizeLimit - size - 80 <= line.length() - start) {
                return false;
            }
            String entry = showtime(clock.get()) + "[" + name + "]{@()|}:\n\t" + line.subSequence(start, line.length())
                + "\n";
            Files.writeString(file, entry, StandardCharsets.ISO_8859_1, StandardOpenOption.APPEND);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** BEHAVIOR: C {@code showtime}: {@code "%02d %s %02d:%02d:%02d"} of day, month name, hour, minute, second. */
    static String showtime(ZonedDateTime t) {
        return String.format(java.util.Locale.ROOT, "%02d %s %02d:%02d:%02d", t.getDayOfMonth(),
            MONTHS[t.getMonthValue() - 1], t.getHour(), t.getMinute(), t.getSecond());
    }
}
