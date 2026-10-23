package com.fulcrologic.jxpilot.common.map;

import com.fulcrologic.jxpilot.common.config.ConfigParser;
import com.fulcrologic.jxpilot.common.config.OptionOrigin;
import com.fulcrologic.jxpilot.common.config.Options;

/** Builds small maps from rows for tests. */
final class TestMaps {
    private TestMaps() {}

    static String text(int w, int h, String extra, String... rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("mapWidth: ").append(w).append('\n');
        sb.append("mapHeight: ").append(h).append('\n');
        sb.append(extra).append('\n');
        sb.append("mapData: \\multiline: EndOfMapdata\n");
        for (String r : rows) {
            sb.append(r).append('\n');
        }
        sb.append("EndOfMapdata\n");
        return sb.toString();
    }

    static WorldMap build(int w, int h, String extra, String... rows) {
        Options o = new Options();
        ConfigParser.parse(text(w, h, extra, rows), "test", OptionOrigin.MAP, o);
        return WorldMap.fromOptions(o);
    }

    static WorldMap build(String extra, String... rows) {
        return build(rows[0].length(), rows.length, extra, rows);
    }
}
