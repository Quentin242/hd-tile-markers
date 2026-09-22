package com.hdtilemarkers;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.gameval.ItemID;

/**
 * Allocates models whose arrays HD Tile Markers owns and rewrites. The public API cannot
 * allocate an empty model, so copies of a small cache mesh are merged and all
 * of their geometry is replaced; no item is ever displayed.
 */
@Singleton
final class CarrierModels
{
    private final Client client;
    private ModelData seed;

    @Inject
    CarrierModels(Client client) { this.client = client; }

    /*
     * Renderer limits per model (GPU plugin ModelUploader: 6500 vertices, 4000 faces per priority).
     * A larger model is skipped whole, with everything in it, so carriers stay below them.
     */
    static final int MAX_VERTICES = 6400, MAX_FACES = 3900;

    private boolean seeded()
    {
        if (seed == null)
        {
            seed = client.loadModelData(client.getItemDefinition(ItemID.BRONZE_DAGGER).getInventoryModel());
        }
        return seed != null && seed.getVerticesCount() > 0 && seed.getFaceCount() > 0;
    }

    private int copies(int vertices, int faces)
    {
        return Math.max(-Math.floorDiv(-vertices, seed.getVerticesCount()), -Math.floorDiv(-faces, seed.getFaceCount()));
    }

    /** Copies of the seed model that stay within the renderer limits (six extra vertices fix the bounds). */
    private int maxCopies()
    {
        return Math.max(2, Math.min((MAX_VERTICES - 6) / seed.getVerticesCount(), MAX_FACES / seed.getFaceCount()));
    }

    /**
     * Whether a scene object with this much geometry still fits a carrier with room to spare:
     * carriers are made with twice the room, and must stay within the renderer limits.
     */
    boolean fits(int vertices, int faces)
    {
        if (!seeded()) { return vertices <= MAX_VERTICES / 2 && faces <= MAX_FACES / 2; }
        return 2 * copies(vertices + 6, faces) <= maxCopies();
    }

    /**
     * A model with at least the given capacity, every face hidden, and bounds
     * fixed to the given radius around its origin, or null.
     *
     * The client computes a model's bounds only once, so they are fixed here
     * from temporary extreme vertices. Later geometry must stay within the
     * radius: the GPU plugin sorts faces relative to those bounds.
     */
    Model create(int vertices, int faces, int radius, boolean transparent)
    {
        if (!seeded()) { return null; }
        int copies = Math.min(maxCopies(), Math.max(2, copies(vertices, faces)));
        ModelData[] parts = new ModelData[copies];
        for (int i = 0; i < copies; i++)
        {
            // Merging shares vertices with equal positions, so identical copies
            // would not add vertex capacity. Offset each copy on owned arrays.
            parts[i] = seed.shallowCopy().cloneVertices().translate(i * 2048, 0, 0);
        }
        ModelData data = client.mergeModels(parts).cloneVertices().cloneColors();
        // The GPU plugin draws any model with a transparency array in its sorted alpha pass;
        // opaque carriers have none and are drawn like other dynamic models.
        if (transparent) { data = data.cloneTransparencies(true); }
        else if (data.getFaceTransparencies() != null) { return null; }
        if (data.getFaceTextures() != null) { data = data.cloneTextures(); }
        // Never mutate cache-owned arrays.
        if (data.getFaceIndices1() == seed.getFaceIndices1() || data.getFaceIndices2() == seed.getFaceIndices2()
            || data.getFaceIndices3() == seed.getFaceIndices3() || data.getVerticesX() == seed.getVerticesX()) { return null; }
        if (data.getFaceTextures() != null) { java.util.Arrays.fill(data.getFaceTextures(), (short) -1); }
        Model model = data.light();
        if (model == null || model.getVerticesCount() < vertices || model.getFaceCount() < faces
            || transparent != (model.getFaceTransparencies() != null)) { return null; }
        if (model.getVerticesCount() < 6) { return null; }
        for (int f = 0; f < model.getFaceCount(); f++) { FlatModel.hide(model, f); }
        float[] x = model.getVerticesX(), y = model.getVerticesY(), z = model.getVerticesZ();
        java.util.Arrays.fill(x, 0);
        java.util.Arrays.fill(y, 0);
        java.util.Arrays.fill(z, 0);
        x[0] = radius; x[1] = -radius; y[2] = radius; y[3] = -radius; z[4] = radius; z[5] = -radius;
        model.calculateBoundsCylinder();
        // If the bounds were already fixed earlier, geometry could leave them: refuse, use 2D.
        return model.getRadius() >= radius ? model : null;
    }

    void reset() { seed = null; }
}
