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
package com.fulcrologic.jxpilot.client.app;

import com.fulcrologic.jxpilot.client.game.options.ClientOptions;
import com.fulcrologic.jxpilot.client.gl.GlRenderer;
import com.fulcrologic.jxpilot.client.gl.UiAtlas;
import com.fulcrologic.jxpilot.client.map.StaticMap;
import com.fulcrologic.jxpilot.client.paint.PaintStyle;
import com.fulcrologic.jxpilot.client.render.Camera;
import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.client.render.TextureFiles;
import com.fulcrologic.jxpilot.client.render.XpmImage;
import com.fulcrologic.jxpilot.common.map.WorldMap;

import java.io.PrintStream;
import java.util.Objects;

import static com.fulcrologic.jxpilot.client.game.options.ClientOption.*;

/**
 * GL glue for the map's static look (client-ui-look): the static mesh ({@link StaticMap#build} for the live
 * {@link PaintStyle}), and with {@code texturedWalls} / {@code texturedDecor} the wall and decoration fills
 * drawn tiled with their textures ({@code wallTextureFile}, {@code decorTextureFile}, {@code texturePath};
 * {@link TextureFiles}); the ball texture ({@code texturedBalls}, {@code ballTextureFile}) goes into the UI
 * atlas. Everything is rebuilt when the map or the look changes, never per frame.
 */
final class MapLook {
    private final GlRenderer gl;
    private final Palette palette;
    private final PaintStyle style;
    private final ClientOptions options;
    private final PrintStream log;

    private WorldMap map;
    private int look = Integer.MIN_VALUE;
    private GlRenderer.Mesh mesh;
    private GlRenderer.Mesh wallTiles;
    private GlRenderer.Mesh decorTiles;
    private final Tile wall = new Tile();
    private final Tile decor = new Tile();
    private String ballFile;
    private String ballPath;

    /** One loaded tile texture and the options it was loaded for. */
    private static final class Tile {
        int texture;
        int width = 1;
        int height = 1;
        String file;
        String path;
    }

    MapLook(GlRenderer gl, Palette palette, PaintStyle style, ClientOptions options, PrintStream log) {
        this.gl = gl;
        this.palette = palette;
        this.style = style;
        this.options = options;
        this.log = log;
    }

    /** A new map (or none): the meshes are rebuilt by the next {@link #update}. */
    void setMap(WorldMap m) {
        deleteMeshes();
        map = m;
        look = Integer.MIN_VALUE;
    }

    /**
     * Per frame: load textures whose options changed and rebuild the meshes when the look changed.
     *
     * @return true when the meshes were rebuilt (the radar picture's colours may have changed too)
     */
    boolean update(UiAtlas atlas) {
        boolean texChanged = false;
        if (style.texturedWalls) {
            texChanged |= load(wall, options.text(WALL_TEXTURE_FILE));
        }
        if (style.texturedDecor) {
            texChanged |= load(decor, options.text(DECOR_TEXTURE_FILE));
        }
        if (style.texturedBalls && (!Objects.equals(ballFile, options.text(BALL_TEXTURE_FILE))
            || !Objects.equals(ballPath, options.text(TEXTURE_PATH)))) {
            ballFile = options.text(BALL_TEXTURE_FILE);
            ballPath = options.text(TEXTURE_PATH);
            XpmImage b = TextureFiles.load(ballFile, ballPath, TextureFiles.BALL, log);
            atlas.setBall(b.width, b.height, b.pixels);
        }
        if (map == null || look == style.mapLook && !texChanged) {
            return false;
        }
        look = style.mapLook;
        deleteMeshes();
        boolean tileWalls = style.texturedWalls && wall.texture != 0;
        boolean tileDecor = style.showDecor && style.texturedDecor && decor.texture != 0;
        mesh = gl.upload(StaticMap.build(map, palette, style, tileWalls, tileDecor));
        if (tileWalls) {
            wallTiles = gl.upload(StaticMap.wallTriangles(map));
        }
        if (tileDecor) {
            decorTiles = gl.upload(StaticMap.decorTriangles(map));
        }
        return true;
    }

    /** Load a tile texture when its file or the texture path changed; true when it was (re)loaded. */
    private boolean load(Tile t, String file) {
        String path = options.text(TEXTURE_PATH);
        if (t.texture != 0 && Objects.equals(t.file, file) && Objects.equals(t.path, path)) {
            return false;
        }
        GlRenderer.deleteTexture(t.texture);
        XpmImage img = TextureFiles.load(file, path, TextureFiles.ROCK, log);
        t.texture = GlRenderer.tileTexture(img.width, img.height, img.pixels);
        t.width = img.width;
        t.height = img.height;
        t.file = file;
        t.path = path;
        return true;
    }

    /**
     * Draw the static look for every visible world copy: tiled wall and decoration fills (C {@code FillTiled},
     * the tile anchored to the world and unscaled: one texel per window pixel at any zoom, rows counted down from
     * the top of a view {@code viewExtentH} world pixels high, as C's {@code XSetTSOrigin}), then the mesh.
     */
    void draw(Camera cam, float[] xf, double zoom, int viewExtentH) {
        if (mesh == null) {
            return;
        }
        for (int k = 0; k < cam.offsetCount(); k++) {
            cam.worldToNdc(k, xf);
            gl.setTransform(xf);
            if (wallTiles != null) {
                gl.drawTiled(wallTiles, wall.texture, (float) (zoom / wall.width), (float) (-zoom / wall.height),
                    0, (float) (viewExtentH * zoom / wall.height));
            }
            if (decorTiles != null) {
                gl.drawTiled(decorTiles, decor.texture, (float) (zoom / decor.width), (float) (-zoom / decor.height),
                    0, (float) (viewExtentH * zoom / decor.height));
            }
            gl.draw(mesh);
        }
    }

    private void deleteMeshes() {
        gl.delete(mesh);
        gl.delete(wallTiles);
        gl.delete(decorTiles);
        mesh = null;
        wallTiles = null;
        decorTiles = null;
    }

    void dispose() {
        deleteMeshes();
        GlRenderer.deleteTexture(wall.texture);
        GlRenderer.deleteTexture(decor.texture);
    }
}
