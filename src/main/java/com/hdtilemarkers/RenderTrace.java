package com.hdtilemarkers;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Singleton;
import net.runelite.api.Model;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.TileObject;
import net.runelite.client.callback.RenderCallback;

/**
 * Diagnostics: counts, per frame, how many of HD Tile Markers' models the client adds to
 * the scene and how many reach the renderer's draw call. Never blocks drawing.
 */
@Singleton
final class RenderTrace implements RenderCallback
{
    // The client may call the render callbacks from other threads than the one that writes the frame.
    private final Set<Model> ours = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private final Set<Model> added = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private final Set<Model> drawn = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private int submitted, lastSubmitted, lastAdded, lastDrawn;
    private final AtomicInteger calls = new AtomicInteger(), getModels = new AtomicInteger(), offThread = new AtomicInteger();
    private int lastCalls, lastGetModels, lastOffThread;
    private final Set<String> threads = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Called before HD Tile Markers writes a frame: the previous frame's counts become final. */
    void beginFrame()
    {
        lastSubmitted = submitted;
        lastAdded = added.size();
        lastDrawn = drawn.size();
        lastCalls = calls.getAndSet(0); lastGetModels = getModels.getAndSet(0); lastOffThread = offThread.getAndSet(0);
        submitted = 0;
        ours.clear();
        added.clear();
        drawn.clear();
    }

    void submitted(Model model)
    {
        ours.add(model);
        submitted++;
    }

    @Override
    public boolean addEntity(Renderable renderable, boolean ui)
    {
        Model m = model(renderable);
        if (m != null && ours.contains(m)) { added.add(m); }
        return true;
    }

    @Override
    public boolean drawObject(Scene scene, TileObject object)
    {
        calls.incrementAndGet();
        threads.add(Thread.currentThread().getName());
        Renderable r = object instanceof net.runelite.api.GameObject ? ((net.runelite.api.GameObject) object).getRenderable() : null;
        Model m = model(r);
        if (m != null && ours.contains(m)) { drawn.add(m); }
        return true;
    }

    private static Model model(Renderable r)
    {
        if (r == null) { return null; }
        if (r instanceof Model) { return (Model) r; }
        return null;
    }

    /** Called from HD Tile Markers' model getter, on whatever thread the client uses. */
    void modelRequested(boolean clientThread)
    {
        getModels.incrementAndGet();
        if (!clientThread) { offThread.incrementAndGet(); }
    }

    String summary()
    {
        return "trace " + lastSubmitted + " sent, " + lastAdded + " added, " + lastDrawn + " to GPU, " + lastCalls
            + " draws, getModel " + lastGetModels + " (" + lastOffThread + " off-thread) " + threads;
    }
}
