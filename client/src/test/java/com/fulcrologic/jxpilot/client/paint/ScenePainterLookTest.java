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
package com.fulcrologic.jxpilot.client.paint;

import com.fulcrologic.jxpilot.client.render.Palette;
import com.fulcrologic.jxpilot.client.render.VertexBatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.fulcrologic.jxpilot.client.paint.PaintTests.*;
import static com.fulcrologic.jxpilot.client.paint.ScenePainterTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** The live look options as {@link ScenePainter} applies them ({@link PaintStyle}). */
class ScenePainterLookTest {
    static VertexBatch draw(PaintStyle st, SceneData s) {
        ScenePainter p = new ScenePainter(P, st);
        VertexBatch b = batch();
        p.draw(b, TEXT, cam(10000, 10000, false, 500, 500), s);
        return b;
    }

    static PaintStyle style() {
        PaintStyle st = new PaintStyle();
        st.sparkRand = Particles.MAX_SPARK_RAND;
        return st;
    }

    @Test
    void shotAndSparkSizesComeFromTheStyle() {
        PaintStyle st = style();
        st.shotSize = 6;
        st.sparkSize = 4;
        VertexBatch b = draw(st, scene());
        int team = 3 + 40 + 12 + 12 + 2;
        List<String> arms = ScenePainter.diagonal(1, 3)
            ? List.of("149,153-155,147", "155,153-149,147")
            : List.of("149,150-155,150", "152,153-152,147");
        assertAll(
            () -> assertEquals(4f, b.pointSize(0), "sparkSize 4"),
            () -> assertEquals(6f, b.pointSize(1), "shotSize 6"),
            () -> assertEquals(arms, segs(b).subList(team, team + 2), "team shot arms of shotSize / 2 = 3"));
    }

    @Test
    void nastyShotsDrawEveryFastShotAsArms() {
        PaintStyle st = style();
        st.nastyShots = true;
        VertexBatch b = draw(st, scene());
        int team = 3 + 40 + 12 + 12 + 2;
        assertAll(
            () -> assertEquals(1, b.pointCount(), "only the particle is a point"),
            () -> assertEquals(P.color(Palette.WHITE), b.segColor(team), "the white fast shot's arms first, in white"),
            () -> assertEquals(P.color(Palette.WHITE), b.segColor(team + 1), "two arms"),
            () -> assertEquals(P.color(Palette.BLUE), b.segColor(team + 2), "then the blue team shot"),
            () -> assertEquals("34,34-66,34", segs(b).get(team + 4), "then the paused frame"));
    }

    @Test
    void sparkProbabilityComesFromTheStyle() {
        PaintStyle st = style();
        st.sparkRand = 0;
        VertexBatch none = draw(st, scene());
        ScenePainter p = new ScenePainter(P, st);
        p.setSparkProb(1);
        assertAll(
            () -> assertEquals(1, none.pointCount(), "spark_rand 0: the particle is never drawn, the shot is"),
            () -> assertEquals(128, st.sparkRand, "setSparkProb writes the shared style"));
    }

    @Test
    void markingLightsAndShipNamesFollowTheirOptions() {
        PaintStyle st = style();
        st.markingLights = false;
        st.showShipName = false;
        SceneData s = scene();
        s.loops = 0;
        VertexBatch b = draw(st, s);
        int t = VertexBatch.TRIANGLES;
        int l = VertexBatch.LINES;
        int p = VertexBatch.POINTS;
        assertAll(
            () -> assertEquals(List.of(t, l, p, l, t, l), modes(b), "loops 0 would light ship 0, but lights are off"),
            () -> assertEquals(List.of("P", "boss"), Drawn.texts(b), "no ship names"));
    }

    @Test
    void texturedBallsAreFilledDiscsInsteadOfArcs() {
        PaintStyle st = style();
        VertexBatch plain = draw(st, scene());
        st.texturedBalls = true;
        VertexBatch tex = draw(st, scene());
        assertAll(
            () -> assertEquals(plain.segmentCount() - 24, tex.segmentCount(), "the ball's 24-chord circle is gone"),
            () -> assertEquals(12 + 3 * 24, tex.commandLength(0), "mine block, item quad, then the 24-triangle disc"),
            () -> assertEquals(P.color(Palette.WHITE), tex.color(12), "untinted"),
            () -> assertEquals(100f, tex.x(12), 1e-4f, "fanned from the ball centre (world 400, view x 100)"));
    }
}
