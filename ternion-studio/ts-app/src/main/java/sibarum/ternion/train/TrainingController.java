package sibarum.ternion.train;

import sibarum.dasum.gui.core.reactive.Property;
import sibarum.mcc.graph.CompGraphNode;
import sibarum.mcc.graph.ComputationGraph;
import sibarum.mcc.graph.SlotSource;
import sibarum.mcc.primitive.Differentiable;
import sibarum.mcc.primitive.Parameterized;
import sibarum.mcc.primitive.Primitive;
import sibarum.mcc.primitive.Trainable;
import sibarum.mcc.value.MatrixValue;
import sibarum.mcc.value.NumberValue;
import sibarum.mcc.value.Value;
import sibarum.ternion.app.AppContext;
import sibarum.ternion.data.source.DataSource;
import sibarum.ternion.data.source.DataSourceRegistry;
import sibarum.ternion.designer.DataSourceBoundNodes;
import sibarum.ternion.designer.DatasetColumnPrimitive;
import sibarum.ternion.designer.FrozenNodes;
import sibarum.ternion.designer.LossOutputPrimitive;
import sibarum.ternion.designer.VisualizerNodes;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns the training worker thread, the run/pause/step/stop state
 * machine, and the (forward / backward / step) loop body. Replicates
 * {@code GraphTrainer.trainEpoch} in ts-app because mcc-core's helpers
 * are private and we need finer-grained hooks: per-example progress,
 * per-node gradient capture, pause boundaries between examples.
 *
 * <p>Threading model:
 * <ul>
 *   <li>GLFW thread calls {@link #start}/{@link #pause}/{@link #resume}/
 *       {@link #step}/{@link #stop} — these set state under a lock and
 *       signal the worker.</li>
 *   <li>One worker thread runs the loop. It snapshots the graph from
 *       {@link AppContext#graphSync()} per example, so live edits in the
 *       Designer take effect on the next example boundary.</li>
 *   <li>State is published via {@code Property} fields read by the UI.
 *       {@code Property.set} is the documented thread-safe bridge: it
 *       calls {@code Invalidator.invalidate} which uses
 *       {@code glfwPostEmptyEvent}.</li>
 * </ul>
 *
 * <p>Error handling: any exception during a training step (no terminal,
 * unwired slot, dim mismatch, etc.) is caught, the message is reported
 * via {@link #lastError}, and the state transitions to STOPPED so the
 * UI can re-enable Start.
 */
public final class TrainingController {

    private final AppContext ctx;
    private final LossHistory lossHistory = new LossHistory();
    private final NodeRuntime nodeRuntime = new NodeRuntime();

    // ----- observable state for the UI -----
    private final Property<TrainingState> state    = new Property<>(TrainingState.IDLE);
    /** True iff the worker thread is actually parked on the wakeup
     *  condition. Distinct from {@link #state} because {@code pause()}
     *  flips state to PAUSED synchronously while the worker may still be
     *  mid-example. Tests and any precise pause-then-observe code should
     *  wait for this flag. */
    private final Property<Boolean> atRest         = new Property<>(true);
    private final Property<Double> currentLoss     = new Property<>(0.0);
    private final Property<Long> currentExample    = new Property<>(0L);
    private final Property<Long> currentEpoch      = new Property<>(0L);
    private final Property<Float> learningRate     = new Property<>(0.1f);
    private final Property<Boolean> stepEveryExample = new Property<>(true);
    private final Property<String> lastError       = new Property<>("");

    // ----- worker coordination -----
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition wakeup   = lock.newCondition();
    private volatile boolean stepPending = false;
    private Thread worker;

    // ----- UI publish throttle -----
    // The worker can grind through thousands of examples per second on
    // tiny graphs. Firing Property.set on currentLoss/currentExample/
    // currentEpoch every example fires their subscribers (TextStates
    // updates, render invalidates) on the worker at that rate, which
    // starves the renderer. Coalesce UI publishes to ~60Hz; training
    // math is unaffected.
    private static final long UI_PUBLISH_INTERVAL_NS = 16_000_000L;
    private long lastUiPublishNs = 0L;
    private long internalExample = 0L;
    private long internalEpoch   = 0L;
    private boolean uiPublishPending = false;
    private double lastLoss = 0.0;

    // ----- training scratch state (worker-thread only) -----
    private final IdentityHashMap<Object, Trainable> pendingSteps = new IdentityHashMap<>();
    /** Nodes from the most recent successful example's graph snapshot. Used
     *  by {@link #applyPendingSteps} to freeze participants at epoch end
     *  in accumulate mode (where the graph isn't otherwise reachable). */
    private List<CompGraphNode> lastParticipants = List.of();
    /** Single source id the trainer iterates. Cached at bootstrap from
     *  the {@link LossOutputPrimitive}'s binding. */
    private String lossOutputSourceId = null;
    /** Position into the source's rows. */
    private int lossOutputRowIdx = 0;

    public TrainingController(AppContext ctx) {
        this.ctx = ctx;
    }

    // ---------- public observables ----------

    public Property<TrainingState> state()       { return state; }
    public Property<Boolean> atRest()            { return atRest; }
    public Property<Double> currentLoss()        { return currentLoss; }
    public Property<Long> currentExample()       { return currentExample; }
    public Property<Long> currentEpoch()         { return currentEpoch; }
    public Property<Float> learningRate()        { return learningRate; }
    public Property<Boolean> stepEveryExample()  { return stepEveryExample; }
    public Property<String> lastError()          { return lastError; }
    public LossHistory lossHistory()             { return lossHistory; }
    public NodeRuntime nodeRuntime()             { return nodeRuntime; }

    // ---------- control surface ----------

    public void start() {
        lock.lock();
        try {
            if (state.get() == TrainingState.RUNNING) return;
            if (state.get() != TrainingState.IDLE && state.get() != TrainingState.STOPPED) return;
            if (!preflightOrError("Training can't start")) return;
            bootstrapWorker(TrainingState.RUNNING);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Spin up the worker thread in {@code initialState}, after the lock-held
     * caller has already preflighted. Resets publish counters and corpus
     * iterator so a new run begins cleanly. Shared by {@link #start} (which
     * launches RUNNING) and {@link #step} (which launches PAUSED with
     * {@code stepPending=true} for a one-shot advance from IDLE/STOPPED).
     *
     * <p>Caller must hold {@link #lock}.
     */
    private void bootstrapWorker(TrainingState initialState) {
        lastUiPublishNs = 0L;
        internalExample = 0L;
        internalEpoch   = 0L;
        uiPublishPending = false;
        lastError.set("");
        pendingSteps.clear();
        lossOutputRowIdx = 0;
        Set<String> srcs = DataSourceBoundNodes.distinctIteratedSources();
        this.lossOutputSourceId = srcs.isEmpty() ? null : srcs.iterator().next();
        atRest.set(false);
        state.set(initialState);
        worker = new Thread(this::workerLoop, "ts-train-worker");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Run the preflight check; on failure, set {@link #lastError} + show a
     * Status toast prefixed with {@code prefix} and return {@code false}.
     * Caller already holds {@link #lock}.
     */
    private boolean preflightOrError(String prefix) {
        String preflight = validateBeforeStart();
        if (preflight != null) {
            lastError.set(preflight);
            sibarum.dasum.gui.core.status.Status.error(prefix + ": " + preflight);
            return false;
        }
        return true;
    }

    /**
     * Run the cheap pre-flight checks before spawning the worker thread.
     * Returns {@code null} if everything is set up; otherwise a short
     * human-readable description of the first problem found.
     */
    private String validateBeforeStart() {
        CompGraphNode lossSink = DataSourceBoundNodes.findLossTarget();
        if (lossSink == null) {
            return "no Loss Output node in the graph — spawn one and wire its "
                + "'predicted' input to your model's output";
        }
        return validateLossOutputPath(lossSink);
    }

    /**
     * Preflight for the LossOutput-driven iteration path. Validates
     * exactly one source is referenced, that source has rows, the loss
     * sink itself is wired, and every non-{@link DatasetColumnPrimitive}
     * input slot in the snapshot rooted at {@code lossSink} has a source.
     */
    private String validateLossOutputPath(CompGraphNode lossSink) {
        // Only iterated bindings (DatasetColumn + LossOutput) count
        // toward the single-source rule. Lookup Columns are
        // random-access — the graph can reference any number of them
        // against any sources alongside the iterated source.
        Set<String> srcs = DataSourceBoundNodes.distinctIteratedSources();
        if (srcs.isEmpty()) {
            return "Loss Output has no source binding — pick a source + column in its Properties.";
        }
        if (srcs.size() > 1) {
            return "graph references " + srcs.size() + " distinct iterated sources " + srcs
                + "; V1 supports exactly one — wire all Dataset Column + Loss Output nodes to the same source"
                + " (Lookup Column nodes don't count and can use any source).";
        }
        String sourceId = srcs.iterator().next();
        DataSourceRegistry reg = DataSourceRegistry.current();
        DataSource src = reg == null ? null : reg.byId(sourceId);
        if (src == null) {
            return "Loss Output references unknown source '" + sourceId + "'";
        }
        if (src.rowCount() == 0) {
            return "source '" + sourceId + "' has no rows — add some via the Data tab first.";
        }
        // Loss sink itself must have its predicted slot wired.
        if (lossSink.slotCount() < 1 || lossSink.slot(0) == null) {
            return "Loss Output's 'predicted' slot is unwired — wire the model output into it.";
        }
        // Every non-DatasetColumn input slot in the snapshot must be wired.
        ComputationGraph snapshot = ctx.graphSync().snapshot(lossSink);
        for (CompGraphNode n : snapshot.nodes()) {
            if (n.tNode().primitive() instanceof DatasetColumnPrimitive) continue;
            for (int i = 0; i < n.slotCount(); i++) {
                if (n.slot(i) != null) continue;
                return "slot " + i + " of '" + n.id() + "' is unwired"
                    + " — wire it to a Dataset Column or another node";
            }
        }
        return null;
    }

    public void pause() {
        lock.lock();
        try {
            if (state.get() == TrainingState.RUNNING) {
                state.set(TrainingState.PAUSED);
            }
        } finally {
            lock.unlock();
        }
    }

    public void resume() {
        lock.lock();
        try {
            if (state.get() == TrainingState.PAUSED) {
                state.set(TrainingState.RUNNING);
                wakeup.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Advance training by exactly one example, regardless of current
     * state:
     * <ul>
     *   <li>IDLE / STOPPED — preflight, then bootstrap the worker in
     *       {@link TrainingState#PAUSED} with {@code stepPending=true} so
     *       the worker runs one example immediately and then awaits.</li>
     *   <li>PAUSED — signal {@code stepPending} and wake the worker.</li>
     *   <li>RUNNING — no-op (the worker is already advancing on its own;
     *       click Pause first if you want single-step control).</li>
     * </ul>
     */
    public void step() {
        lock.lock();
        try {
            TrainingState s = state.get();
            switch (s) {
                case IDLE, STOPPED -> {
                    if (!preflightOrError("Step")) return;
                    stepPending = true;
                    bootstrapWorker(TrainingState.PAUSED);
                }
                case PAUSED -> {
                    stepPending = true;
                    wakeup.signalAll();
                }
                case RUNNING -> {
                    // Intentional no-op — already running.
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public void stop() {
        Thread w;
        lock.lock();
        try {
            if (state.get() == TrainingState.IDLE || state.get() == TrainingState.STOPPED) return;
            state.set(TrainingState.STOPPED);
            wakeup.signalAll();
            w = worker;
        } finally {
            lock.unlock();
        }
        if (w != null) {
            try { w.join(2000); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        // Flush the final loss/example/epoch values so the post-stop UI
        // reflects where training actually ended, not the last throttled
        // publish from ~16ms before.
        maybePublishUi(true);
    }

    // ---------- worker ----------

    private void workerLoop() {
        try {
            while (true) {
                // Wait until we have permission to advance: state == RUNNING,
                // or state == PAUSED with a pending step request.
                lock.lock();
                try {
                    if (state.get() == TrainingState.PAUSED && !stepPending) {
                        atRest.set(true);
                        while (state.get() == TrainingState.PAUSED && !stepPending) {
                            wakeup.await();
                        }
                        atRest.set(false);
                    }
                    if (state.get() == TrainingState.STOPPED) {
                        atRest.set(true);
                        return;
                    }
                } finally {
                    lock.unlock();
                }
                boolean wasStep;
                lock.lock();
                try {
                    wasStep = stepPending;
                    stepPending = false;
                } finally {
                    lock.unlock();
                }

                // Run one example outside the lock so pause() can grab the
                // lock in parallel; the next iteration sees the new state.
                boolean advanced;
                try {
                    advanced = runOneLossOutputStep();
                } catch (RuntimeException e) {
                    maybePublishUi(true);  // flush before reporting
                    String msg = failureMessage(e);
                    lastError.set(msg);
                    sibarum.dasum.gui.core.status.Status.error(
                        "Training stopped: " + msg, stackTraceOf(e));
                    state.set(TrainingState.STOPPED);
                    atRest.set(true);
                    return;
                }
                if (!advanced) {
                    sleep(100);
                    continue;
                }

                if (wasStep) {
                    maybePublishUi(true);  // flush at step boundary
                    state.set(TrainingState.PAUSED);
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            atRest.set(true);
        }
    }

    /**
     * Phase C-1 iteration step: advance every {@code SourceBound}
     * binding to the next row, snapshot the graph rooted at the Loss
     * Output sink, run forward, and backprop with the trivial
     * {@code dL/dL=1} seed at the sink (since the sink's
     * {@code producedValue} is the scalar loss).
     *
     * <p>Returns {@code false} only if the active source has zero rows,
     * which lets the worker sleep + retry rather than busy-loop while
     * the user is mid-edit on the Data tab.
     */
    private boolean runOneLossOutputStep() {
        CompGraphNode lossSink = DataSourceBoundNodes.findLossTarget();
        if (lossSink == null) return false;
        DataSource src = DataSourceRegistry.current().byId(lossOutputSourceId);
        if (src == null) return false;
        int total = src.rowCount();
        if (total <= 0) return false;
        if (lossOutputRowIdx >= total) {
            if (!stepEveryExample.get()) applyPendingSteps();
            internalEpoch++;
            uiPublishPending = true;
            lossOutputRowIdx = 0;
        }
        int row = lossOutputRowIdx++;
        // Only iterated bindings (INPUT + LOSS_TARGET) get the row
        // pointer. Lookup bindings are random-access by key and
        // would no-op here anyway, but filtering up front keeps the
        // contract explicit.
        for (DataSourceBoundNodes.Binding b : DataSourceBoundNodes.all()) {
            if (b.primitive().iterated()) b.primitive().setCurrentRow(row);
        }

        ComputationGraph graph = ctx.graphSync().snapshot(lossSink);
        Value out;
        try {
            out = graph.execute();
        } catch (RuntimeException ex) {
            throw new RuntimeException("forward (row " + row + "): " + ex.getMessage(), ex);
        }
        double loss = (out instanceof NumberValue n) ? n.n() : 0.0;

        // Backward: seed dL/dL=1 at the sink. The sink's
        // {@link LossOutputPrimitive#backward} returns the clipped
        // (predicted − expected) on the predicted slot which feeds the
        // standard reverse-topo walk.
        Map<CompGraphNode, Value> grads = new LinkedHashMap<>();
        grads.put(graph.terminal(), new NumberValue(1.0));
        List<CompGraphNode> order = graph.topoOrder();
        for (int i = order.size() - 1; i >= 0; i--) {
            CompGraphNode n = order.get(i);
            Value gradAtN = grads.get(n);
            Primitive p = n.tNode().primitive();
            double gradMag = gradAtN == null ? 0.0 : magnitudeOf(gradAtN);
            nodeRuntime.update(n, n.producedValue(), gradMag);
            VisualizerNodes.Entry vis = VisualizerNodes.of(n);
            if (vis != null) {
                VisualizerTap.publish(vis, n.producedValue(), gradAtN);
            }
            if (gradAtN == null) continue;
            if (!(p instanceof Differentiable diff)) continue;
            List<Value> inputGrads;
            try {
                inputGrads = diff.backward(gradAtN);
            } catch (RuntimeException backErr) {
                throw new RuntimeException(
                    "node '" + n.id() + "' (" + p.name() + ") backward: " + backErr.getMessage(), backErr);
            }
            if (p instanceof Trainable t) {
                pendingSteps.put(t.trainableIdentity(), t);
            }
            for (int slot = 0; slot < n.slotCount(); slot++) {
                SlotSource srcSlot = n.slot(slot);
                if (srcSlot == null) continue;
                if (slot >= inputGrads.size()) break;
                Value g = inputGrads.get(slot);
                if (g == null) continue;
                accumulate(grads, srcSlot.source(), g);
            }
        }
        nodeRuntime.publish();
        lastParticipants = List.copyOf(graph.nodes());

        if (stepEveryExample.get()) {
            applyPendingSteps();
        }

        lossHistory.append(loss);
        lastLoss = loss;
        internalExample++;
        uiPublishPending = true;
        maybePublishUi(false);
        return true;
    }

    /**
     * Coalesces UI-observable Property.set calls (currentLoss /
     * currentExample / currentEpoch) to ~60Hz so the worker thread
     * doesn't dominate Property subscribers — primarily
     * {@link sibarum.dasum.gui.core.input.TextStates#setContent} — at
     * thousands of Hz, which can starve the render thread.
     *
     * @param force when {@code true}, publishes immediately regardless
     *              of the throttle. Used at state transitions to ensure
     *              the post-stop frame reflects the last training step.
     */
    private void maybePublishUi(boolean force) {
        if (!uiPublishPending && !force) return;
        long now = System.nanoTime();
        if (!force && now - lastUiPublishNs < UI_PUBLISH_INTERVAL_NS) return;
        lastUiPublishNs = now;
        uiPublishPending = false;
        currentLoss.set(lastLoss);
        currentExample.set(internalExample);
        currentEpoch.set(internalEpoch);
    }

    /**
     * Apply accumulated trainable steps. When at least one Trainable was
     * actually stepped, every node that participated in the most recent
     * successful example freezes — its structural config can no longer be
     * edited in place without first deleting the node.
     *
     * <p>After the step, post-step parameters are scanned for NaN/Inf.
     * If a node's weights went non-finite, throws with the node id so
     * training stops loudly instead of silently corrupting the next
     * forward pass.
     */
    private void applyPendingSteps() {
        boolean stepped = !pendingSteps.isEmpty();
        double lr = learningRate.get();
        for (Trainable t : pendingSteps.values()) {
            t.step(lr);
        }
        pendingSteps.clear();
        if (!stepped) return;
        FrozenNodes.markAll(lastParticipants);
        for (CompGraphNode n : lastParticipants) {
            if (n.tNode().primitive() instanceof Parameterized p
                && hasNonFiniteParameters(p)) {
                throw new RuntimeException(
                    "diverged: node '" + n.id() + "' has non-finite parameters after step"
                        + " — lower the learning rate or click Reset Training");
            }
        }
    }

    private static boolean hasNonFiniteParameters(Parameterized p) {
        for (Parameterized.NamedTensor t : p.parameters()) {
            double[] data = t.data();
            for (int i = 0; i < data.length; i++) {
                if (!Double.isFinite(data[i])) return true;
            }
        }
        return false;
    }

    // ---------- helpers ----------

    private static void accumulate(Map<CompGraphNode, Value> grads, CompGraphNode key, Value addend) {
        Value existing = grads.get(key);
        if (existing == null) {
            grads.put(key, addend);
        } else {
            grads.put(key, addValues(existing, addend));
        }
    }

    private static Value addValues(Value a, Value b) {
        if (a instanceof MatrixValue ma && b instanceof MatrixValue mb
                && ma.data().length == mb.data().length) {
            double[] out = new double[ma.data().length];
            for (int i = 0; i < out.length; i++) out[i] = ma.data()[i] + mb.data()[i];
            return new MatrixValue(out);
        }
        if (a instanceof NumberValue na && b instanceof NumberValue nb) {
            return new NumberValue(na.n() + nb.n());
        }
        throw new IllegalStateException(
            "cannot accumulate gradients of type " + a.type() + " and " + b.type());
    }

    private static double magnitudeOf(Value v) {
        if (v instanceof MatrixValue m) {
            double s = 0.0;
            for (double d : m.data()) s += d * d;
            return Math.sqrt(s);
        }
        if (v instanceof NumberValue n) {
            return Math.abs(n.n());
        }
        return 0.0;
    }

    private static String failureMessage(Throwable t) {
        String msg = t.getMessage();
        return msg == null ? t.getClass().getSimpleName() : msg;
    }

    private static String stackTraceOf(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
