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

import com.fulcrologic.jxpilot.common.net.Msg;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/**
 * The server's message of the day, C {@code Get_motd} (netserver.c:3021-3090): {@code motdFileName}'s text, at
 * most {@link Msg.Motd#MAX_SIZE} bytes, re-read when the file changes (edge: file I/O, once per login).
 */
final class MotdFile {
    private final Path file;
    private byte[] text = new byte[0];
    private FileTime readAt;
    private long readSize = -1;

    /** @param file the MOTD file, or null for none */
    MotdFile(Path file) {
        this.file = file;
    }

    /**
     * The current text.
     * <p>
     * BEHAVIOR: empty without a file, for a missing, unreadable or empty file; the first MAX_SIZE bytes
     * otherwise; the file is read again when its modification time or size changed.
     */
    byte[] text() {
        if (file == null) {
            return text;
        }
        try {
            FileTime m = Files.getLastModifiedTime(file);
            long size = Files.size(file);
            if (m.equals(readAt) && size == readSize) {
                return text;
            }
            try (InputStream in = Files.newInputStream(file)) {
                text = in.readNBytes(Msg.Motd.MAX_SIZE);
            }
            readAt = m;
            readSize = size;
        } catch (IOException e) {
            text = new byte[0];
            readAt = null;
            readSize = -1;
        }
        return text;
    }
}
