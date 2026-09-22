package com.hdtilemarkers;

import java.awt.Color;
import net.runelite.api.JagexColor;
import net.runelite.api.Model;

/**
 * Makes carrier model faces render as one flat color. Lit colors, unlit colors
 * and vertex normals are all set, because renderers differ in which they read:
 * the vanilla GPU uses faceColors1-3, 117 HD may use the unlit colors and
 * lights faces from the vertex normals.
 */
final class FlatModel
{
    private FlatModel() { }

    static int hsl(Color color) { return JagexColor.rgbToHSL(color.getRGB(), 1.0) & 0xFFFF; }

    static void paint(Model m, int face, int hsl, int alpha)
    {
        m.getFaceColors1()[face] = hsl;
        m.getFaceColors2()[face] = hsl;
        // -1 draws the face flat with faceColors1; -2 hides it.
        m.getFaceColors3()[face] = alpha == 0 ? -2 : -1;
        byte[] transparencies = m.getFaceTransparencies();
        if (transparencies != null) { transparencies[face] = (byte) (255 - alpha); }
        short[] unlit = m.getUnlitFaceColors();
        if (unlit != null) { unlit[face] = (short) hsl; }
    }

    static void hide(Model m, int face)
    {
        m.getFaceIndices1()[face] = 0;
        m.getFaceIndices2()[face] = 0;
        m.getFaceIndices3()[face] = 0;
        m.getFaceColors3()[face] = -2;
        byte[] transparencies = m.getFaceTransparencies();
        if (transparencies != null) { transparencies[face] = (byte) 255; }
    }

    /** Points every vertex normal in one model-space direction (y is down). */
    static void normals(Model m, float x, float y, float z)
    {
        int[] nx = m.getVertexNormalsX(), ny = m.getVertexNormalsY(), nz = m.getVertexNormalsZ();
        if (nx == null || ny == null || nz == null) { return; }
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        if (!(length > 0)) { return; }
        int ix = Math.round(x / length * 256), iy = Math.round(y / length * 256), iz = Math.round(z / length * 256);
        for (int i = 0; i < nx.length; i++) { nx[i] = ix; ny[i] = iy; nz[i] = iz; }
    }

    /** Recomputes the cached extremes after vertices changed in place; the bounds cylinder stays fixed. */
    static void bounds(Model m)
    {
        // Extremes may be cached per orientation; switch away and back.
        m.calculateExtreme(1);
        m.calculateExtreme(0);
    }
}
