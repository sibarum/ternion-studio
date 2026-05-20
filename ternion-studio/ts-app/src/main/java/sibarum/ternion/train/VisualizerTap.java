package sibarum.ternion.train;

import sibarum.dasum.gui.vis.pointcloud.PointCloudSnapshot;
import sibarum.dasum.gui.vis.pointcloud.PointCloudStates;
import sibarum.mcc.value.MatrixValue;
import sibarum.mcc.value.NumberValue;
import sibarum.mcc.value.TensorValue;
import sibarum.mcc.value.Value;
import sibarum.ternion.designer.VisualizerNodes;

/**
 * Builds {@link PointCloudSnapshot}s from the forward / backward values
 * captured at a {@link VisualizerNodes.Entry visualizer node} during a
 * training step, and publishes them to the entry's viewport.
 *
 * <p>Semantics (single-example training; mcc-core has no batch axis):
 * <ul>
 *   <li>Position for the new sample = first three components of the
 *       captured tensor, padded with zeros when the tensor has fewer
 *       than three elements.</li>
 *   <li>Two parallel trajectories per visualizer: <b>forward</b> values
 *       (cool blue→cyan ramp) and <b>gradient</b> values
 *       (warm red→orange ramp). When both are available, both
 *       trajectories share one snapshot — distinguishable by colour.</li>
 *   <li>Each call appends one point per available signal to the
 *       visualizer's ring-buffered history and re-publishes the full
 *       history as a fresh snapshot. History capacity is
 *       {@link #HISTORY_CAPACITY}; old points fall off the back.</li>
 * </ul>
 *
 * <p>The renderer's snapshot identity-dedup means each step uploads
 * exactly once even at high publish rates; the only per-step cost on
 * the worker is the array allocation here.
 */
public final class VisualizerTap {

    public static final int HISTORY_CAPACITY = 1024;
    /** ~60Hz publish ceiling — matches {@code TrainingController}'s UI
     *  throttle so the renderer sees the same wake cadence regardless of
     *  whether visualizers are present. Worker-side computation still runs
     *  every step; only the snapshot allocation + dasum invalidation is
     *  throttled. */
    private static final long PUBLISH_INTERVAL_NS = 16_000_000L;

    private VisualizerTap() {}

    /**
     * Sample {@code forward} (and optionally {@code gradient}) for the
     * given entry, append to its histories, and publish a fresh snapshot.
     * Called from the training worker once per step per visualizer.
     *
     * @param entry      the visualizer to publish to
     * @param forward    the forward value at the visualizer's output
     *                   (i.e. {@code cgNode.producedValue()})
     * @param gradient   the gradient flowing back into the visualizer
     *                   (may be {@code null} when the output isn't wired)
     */
    public static void publish(VisualizerNodes.Entry entry, Value forward, Value gradient) {
        if (entry == null || forward == null) return;

        double[] fPayload = flatten(forward);
        double[] gPayload = (gradient != null) ? flatten(gradient) : null;

        History h = historyFor(entry);
        h.pushForward(
            componentAt(fPayload, 0),
            componentAt(fPayload, 1),
            componentAt(fPayload, 2));
        if (gPayload != null) {
            h.pushGrad(
                componentAt(gPayload, 0),
                componentAt(gPayload, 1),
                componentAt(gPayload, 2));
        }

        // Throttle publishes — the History keeps absorbing samples but we
        // only build + publish a snapshot at ~60Hz. Keeps renderer wake
        // rate predictable on fast graphs (1000+ steps/sec).
        long now = System.nanoTime();
        if (now - h.lastPublishNs < PUBLISH_INTERVAL_NS) return;
        h.lastPublishNs = now;

        PointCloudSnapshot snap = h.toSnapshot();
        // PointCloudStates is the only main↔worker bridge here — its
        // AtomicReference + Invalidator pattern is documented thread-safe.
        // Anything else that wants to mutate UI sidecars from the worker
        // (TextStates, DynamicChildren, ...) is OFF-LIMITS: those are
        // backed by unsynchronized IdentityHashMaps and a single concurrent
        // write can permanently corrupt the renderer.
        PointCloudStates.publish(entry.viewport, snap);
    }

    /** Per-Entry history sidecar — two parallel ring buffers, one for
     *  forward samples and one for gradient samples, created lazily on
     *  first publish. Sizes can diverge in single-wired modes (input
     *  wired only → no gradient stream). */
    static final class History {
        // Forward trajectory.
        final float[] fxs = new float[HISTORY_CAPACITY];
        final float[] fys = new float[HISTORY_CAPACITY];
        final float[] fzs = new float[HISTORY_CAPACITY];
        int fWritePos = 0;
        int fCount = 0;

        // Gradient trajectory.
        final float[] gxs = new float[HISTORY_CAPACITY];
        final float[] gys = new float[HISTORY_CAPACITY];
        final float[] gzs = new float[HISTORY_CAPACITY];
        int gWritePos = 0;
        int gCount = 0;

        long lastPublishNs = 0L;

        void pushForward(float x, float y, float z) {
            int slot = fWritePos % HISTORY_CAPACITY;
            fxs[slot] = x;
            fys[slot] = y;
            fzs[slot] = z;
            fWritePos++;
            if (fCount < HISTORY_CAPACITY) fCount++;
        }

        void pushGrad(float x, float y, float z) {
            int slot = gWritePos % HISTORY_CAPACITY;
            gxs[slot] = x;
            gys[slot] = y;
            gzs[slot] = z;
            gWritePos++;
            if (gCount < HISTORY_CAPACITY) gCount++;
        }

        /**
         * Materialise both buffered histories as a single chronological
         * {@link PointCloudSnapshot}. Forward trajectory uses a cool
         * pale→saturated ramp; gradient trajectory uses a warm one. Points
         * mark each captured sample; line segments connect consecutive
         * samples in each trajectory so the path stays visible even where
         * dots overlap heavily (dense clusters after the model converges).
         */
        PointCloudSnapshot toSnapshot() {
            int total = fCount + gCount;
            float[] positions = new float[total * 3];
            float[] colors    = new float[total * 3];
            float[] fColors   = new float[fCount * 3];
            float[] gColors   = new float[gCount * 3];

            // Forward trajectory first — cool ramp.
            // Old samples are near-white with a faint cool cast (read as
            // "faded-out" against the dark viewport background); new
            // samples are saturated cyan. Old points stay visible as pale
            // dots rather than disappearing into the background.
            int fOldest = ((fWritePos - fCount) % HISTORY_CAPACITY + HISTORY_CAPACITY) % HISTORY_CAPACITY;
            for (int i = 0; i < fCount; i++) {
                int src = (fOldest + i) % HISTORY_CAPACITY;
                positions[i*3    ] = fxs[src];
                positions[i*3 + 1] = fys[src];
                positions[i*3 + 2] = fzs[src];
                float age = fCount == 1 ? 1f : (float) i / (fCount - 1);
                float r = 0.85f - 0.65f * age;   // R: 0.85 → 0.20
                float g = 0.90f - 0.05f * age;   // G: 0.90 → 0.85
                float b = 0.95f + 0.05f * age;   // B: 0.95 → 1.00  (cool / cyan)
                colors[i*3    ] = r; colors[i*3 + 1] = g; colors[i*3 + 2] = b;
                fColors[i*3   ] = r; fColors[i*3 + 1] = g; fColors[i*3 + 2] = b;
            }

            // Gradient trajectory second — warm ramp.
            // Same near-white-old / saturated-new shape, hue rotated to
            // orange so the two trajectories are colour-distinguishable.
            int gOldest = ((gWritePos - gCount) % HISTORY_CAPACITY + HISTORY_CAPACITY) % HISTORY_CAPACITY;
            int off = fCount;
            for (int i = 0; i < gCount; i++) {
                int src = (gOldest + i) % HISTORY_CAPACITY;
                positions[(off + i)*3    ] = gxs[src];
                positions[(off + i)*3 + 1] = gys[src];
                positions[(off + i)*3 + 2] = gzs[src];
                float age = gCount == 1 ? 1f : (float) i / (gCount - 1);
                float r = 0.95f + 0.05f * age;   // R: 0.95 → 1.00
                float g = 0.90f - 0.45f * age;   // G: 0.90 → 0.45
                float b = 0.85f - 0.75f * age;   // B: 0.85 → 0.10  (warm / orange)
                colors[(off + i)*3    ] = r; colors[(off + i)*3 + 1] = g; colors[(off + i)*3 + 2] = b;
                gColors[i*3        ] = r; gColors[i*3 + 1]         = g; gColors[i*3 + 2]         = b;
            }

            // Line layer — connect each sample to its chronological successor
            // in both trajectories. fCount-1 + gCount-1 segments total,
            // packed forward-first then gradient. Each segment's endpoint
            // colours match the corresponding point colours so the line
            // fades from pale at the old end to saturated at the new end.
            int fSegs = Math.max(0, fCount - 1);
            int gSegs = Math.max(0, gCount - 1);
            int segCount = fSegs + gSegs;
            float[] segEndpoints = new float[segCount * 2 * 3];
            float[] segColors    = new float[segCount * 2 * 3];
            for (int i = 0; i < fSegs; i++) {
                segEndpoints[i*6    ] = positions[i*3        ];
                segEndpoints[i*6 + 1] = positions[i*3 + 1    ];
                segEndpoints[i*6 + 2] = positions[i*3 + 2    ];
                segEndpoints[i*6 + 3] = positions[(i+1)*3    ];
                segEndpoints[i*6 + 4] = positions[(i+1)*3 + 1];
                segEndpoints[i*6 + 5] = positions[(i+1)*3 + 2];
                segColors[i*6    ] = fColors[i*3        ];
                segColors[i*6 + 1] = fColors[i*3 + 1    ];
                segColors[i*6 + 2] = fColors[i*3 + 2    ];
                segColors[i*6 + 3] = fColors[(i+1)*3    ];
                segColors[i*6 + 4] = fColors[(i+1)*3 + 1];
                segColors[i*6 + 5] = fColors[(i+1)*3 + 2];
            }
            int gSegOff = fSegs * 6;
            for (int i = 0; i < gSegs; i++) {
                int src     = (off + i)     * 3;
                int srcNext = (off + i + 1) * 3;
                segEndpoints[gSegOff + i*6    ] = positions[src    ];
                segEndpoints[gSegOff + i*6 + 1] = positions[src + 1];
                segEndpoints[gSegOff + i*6 + 2] = positions[src + 2];
                segEndpoints[gSegOff + i*6 + 3] = positions[srcNext    ];
                segEndpoints[gSegOff + i*6 + 4] = positions[srcNext + 1];
                segEndpoints[gSegOff + i*6 + 5] = positions[srcNext + 2];
                segColors[gSegOff + i*6    ] = gColors[i*3        ];
                segColors[gSegOff + i*6 + 1] = gColors[i*3 + 1    ];
                segColors[gSegOff + i*6 + 2] = gColors[i*3 + 2    ];
                segColors[gSegOff + i*6 + 3] = gColors[(i+1)*3    ];
                segColors[gSegOff + i*6 + 4] = gColors[(i+1)*3 + 1];
                segColors[gSegOff + i*6 + 5] = gColors[(i+1)*3 + 2];
            }

            return new PointCloudSnapshot(
                3, total, positions, colors, null, null,
                segCount, segEndpoints, segColors);
        }
    }

    private static final java.util.IdentityHashMap<VisualizerNodes.Entry, History> HISTORIES
        = new java.util.IdentityHashMap<>();

    private static synchronized History historyFor(VisualizerNodes.Entry entry) {
        return HISTORIES.computeIfAbsent(entry, e -> new History());
    }

    /** Drop any cached history for {@code entry}. Call on node removal. */
    public static synchronized void forget(VisualizerNodes.Entry entry) {
        HISTORIES.remove(entry);
    }

    /**
     * Flatten a {@link Value} into a {@code double[]} — handles the
     * concrete subtypes the visualizer expects to see on its wire.
     * Returns an empty array for types we don't render so the caller
     * silently shows no-data instead of crashing the training loop.
     */
    private static double[] flatten(Value v) {
        return switch (v) {
            case MatrixValue m -> m.data().clone();
            case NumberValue n -> new double[] { n.n() };
            case TensorValue t -> t.data().clone();
            case null          -> new double[0];
            default            -> new double[0];
        };
    }

    private static float componentAt(double[] data, int i) {
        return (i < data.length) ? (float) data[i] : 0f;
    }
}
