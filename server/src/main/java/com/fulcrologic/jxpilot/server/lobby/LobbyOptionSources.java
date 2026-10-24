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
package com.fulcrologic.jxpilot.server.lobby;

import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;
import com.fulcrologic.jxpilot.common.map.MapSource;
import com.fulcrologic.jxpilot.server.net.OptionHooks;

/**
 * The lobby's extra option sources for one map's game ({@link com.fulcrologic.jxpilot.server.net.Server#readOptions}):
 * the map's section of the lobby config right after the command line (its options carry {@code \override:}, so
 * they beat the defaults file, the lines before the first section and the map), the server-wide lines after the
 * positional config file, and the map to load.
 */
final class LobbyOptionSources implements OptionHooks {
    private final LobbyConfig config;
    private final String mapName;
    private final MapSource map;

    /**
     * @param mapName the section to apply, or null for none (the lobby's own options)
     * @param map     the map to load
     */
    LobbyOptionSources(LobbyConfig config, String mapName, MapSource map) {
        this.config = config;
        this.mapName = mapName;
        this.map = map;
    }

    @Override
    public void afterCommandLine(Options opts) {
        if (mapName != null) {
            String section = config.sectionFor(mapName);
            if (!section.isBlank()) {
                ConfigParser.parse(section, "lobby config [" + mapName + "]", OptionOrigin.DEFAULTS, opts);
            }
        }
    }

    @Override
    public void afterConfig(Options opts) {
        if (!config.top().isBlank()) {
            ConfigParser.parse(config.top(), "lobby config", OptionOrigin.DEFAULTS, opts);
        }
    }

    @Override
    public MapSource map() {
        return map;
    }
}
