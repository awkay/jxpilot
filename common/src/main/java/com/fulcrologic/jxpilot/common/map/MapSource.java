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
package com.fulcrologic.jxpilot.common.map;

import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A map to load, as {@link MapLocator} resolved it: either a bundled map ({@link BundledMaps}) or a file.
 *
 * @param fileName the file name used in parser warnings and labels (e.g. {@code tourmination.xp})
 * @param file     the file on disk, or null for a bundled map
 */
public record MapSource(String fileName, Path file) {
    /** A bundled map, by its file name in {@link BundledMaps#files()}. */
    public static MapSource bundled(String fileName) {
        return new MapSource(fileName, null);
    }

    /** A map file on disk. */
    public static MapSource of(Path file) {
        return new MapSource(file.getFileName().toString(), file);
    }

    /** True for a bundled map. */
    public boolean isBundled() {
        return file == null;
    }

    /** The map's raw bytes (edge: reads the class path or the file). */
    public byte[] bytes() throws IOException {
        return file == null ? BundledMaps.bytes(fileName) : Files.readAllBytes(file);
    }

    /** Parse the map into {@code opts} as origin MAP, decoded as Latin-1 like every XPilot file. */
    public void parseInto(Options opts) throws IOException {
        ConfigParser.parseBytes(bytes(), fileName, OptionOrigin.MAP, opts);
    }

    /** BEHAVIOR: "bundled map NAME" for a bundled map, else the file's path. */
    public String describe() {
        return file == null ? "bundled map " + fileName : file.toString();
    }
}
