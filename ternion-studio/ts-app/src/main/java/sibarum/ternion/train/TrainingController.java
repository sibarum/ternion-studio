package sibarum.ternion.train;

import sibarum.dasum.gui.core.reactive.Property;
import sibarum.mcc.graph.CompGraphNode;
import sibarum.mcc.graph.ComputationGraph;
import sibarum.mcc.graph.SlotSource;
import sibarum.mcc.op.Terminal;
import sibarum.mcc.primitive.Differentiable;
import sibarum.mcc.primitive.Primitive;
import sibarum.mcc.primitive.Trainable;
import sibarum.mcc.training.Corpus;
import sibarum.mcc.training.Example;
import sibarum.mcc.value.MatrixValue;
import sibarum.mcc.value.NumberValue;
import sibarum.mcc.value.Value;
import sibarum.ternion.app.AppContext;
import sibarum.ternion.designer.NodeSpec;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    // ----- training scratch state (worker-thread only) -----
    private final IdentityHashMap<Object, Trainable> pendingSteps = new IdentityHashMap<>();
    private Iterator<Example> currentIterator;
    private Corpus currentCorpus;

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
            lastError.set("");
            pendingSteps.clear();
            currentIterator = null;
            currentCorpus = ctx.corpus().toCorpus();
            atRest.set(false);
            state.set(TrainingState.RUNNING);
            worker = new Thread(this::workerLoop, "ts-train-worker");
            worker.setDaemon(true);
            worker.start();
        } finally {
            lock.unlock();
        }
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

    public void step() {
        lock.lock();
        try {
            if (state.get() == TrainingState.PAUSED) {
                stepPending = true;
                wakeup.signalAll();
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
                Example ex = nextExample();
                if (ex == null) {
                    // No data — sleep briefly and re-check (the user may
                    // be adding rows in the Data tab).
                    sleep(100);
                    continue;
                }
                try {
                    runOneExample(ex);
                } catch (RuntimeException e) {
                    lastError.set(failureMessage(e));
                    state.set(TrainingState.STOPPED);
                    atRest.set(true);
                    return;
                }

                if (wasStep) {
                    state.set(TrainingState.PAUSED);
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            atRest.set(true);
        }
    }

    /** Pull the next example from the active corpus iterator, rolling
     *  over to a fresh iterator (next epoch) at end-of-stream. Returns
     *  {@code null} if the corpus is empty. */
    private Example nextExample() {
        if (currentCorpus == null) return null;
        if (currentIterator == null || !currentIterator.hasNext()) {
            if (currentIterator != null) {
                // End of epoch — drain pending steps if accumulating.
                if (!stepEveryExample.get()) applyPendingSteps();
                currentEpoch.set(currentEpoch.get() + 1);
            }
            // Refresh the corpus snapshot — the user may have edited
            // rows since the last epoch.
            currentCorpus = ctx.corpus().toCorpus();
            if (currentCorpus.size() == 0) return null;
            currentIterator = currentCorpus.stream();
            currentExample.set(0L);
        }
        if (!currentIterator.hasNext()) return null;
        return currentIterator.next();
    }

    private void runOneExample(Example ex) {
        ComputationGraph graph = snapshotGraph();
        if (graph == null) {
            throw new IllegalStateException("graph has no Terminal node — add one in the Designer");
        }
        Map<String, CompGraphNode> byId = idMap(graph);

        // Bind named inputs to graph roots.
        for (Map.Entry<String, Value> e : ex.inputs().entrySet()) {
            String key = e.getKey();
            int hash = key.lastIndexOf('#');
            if (hash < 0) continue;
            String nodeId = key.substring(0, hash);
            int slot;
            try { slot = Integer.parseInt(key.substring(hash + 1)); }
            catch (NumberFormatException nfe) { continue; }
            CompGraphNode n = byId.get(nodeId);
            if (n == null) continue;
            graph.bindRoot(n, slot, e.getValue());
        }

        Value out = graph.execute();
        double loss = halfMse(out, ex.target());
        Value terminalGrad = mseGrad(out, ex.target());

        // Reverse-topo backprop with per-node gradient capture.
        Map<CompGraphNode, Value> grads = new LinkedHashMap<>();
        grads.put(graph.terminal(), terminalGrad);

        List<CompGraphNode> order = graph.topoOrder();
        for (int i = order.size() - 1; i >= 0; i--) {
            CompGraphNode n = order.get(i);
            Value gradAtN = grads.get(n);
            Primitive p = n.tNode().primitive();
            double gradMag = gradAtN == null ? 0.0 : magnitudeOf(gradAtN);
            nodeRuntime.update(n, n.producedValue(), gradMag);
            if (gradAtN == null) continue;
            if (!(p instanceof Differentiable diff)) continue;

            List<Value> inputGrads = diff.backward(gradAtN);
            if (p instanceof Trainable t) {
                pendingSteps.put(t.trainableIdentity(), t);
            }
            for (int slot = 0; slot < n.slotCount(); slot++) {
                SlotSource src = n.slot(slot);
                if (src == null) continue;
                if (slot >= inputGrads.size()) break;
                Value g = inputGrads.get(slot);
                if (g == null) continue;
                accumulate(grads, src.source(), g);
            }
        }
        nodeRuntime.publish();

        if (stepEveryExample.get()) {
            applyPendingSteps();
        }

        lossHistory.append(loss);
        currentLoss.set(loss);
        currentExample.set(currentExample.get() + 1);
    }

    private void applyPendingSteps() {
        double lr = learningRate.get();
        for (Trainable t : pendingSteps.values()) {
            t.step(lr);
        }
        pendingSteps.clear();
    }

    // ---------- helpers ----------

    /** Snapshot the GraphSync's live nodes into a fresh ComputationGraph
     *  rooted at a Terminal node, or {@code null} if no Terminal exists. */
    private ComputationGraph snapshotGraph() {
        CompGraphNode terminal = null;
        for (NodeSpec spec : ctx.graphSync().liveNodes()) {
            if (spec.tNode().primitive() instanceof Terminal) {
                terminal = spec.cgNode();
                break;
            }
        }
        if (terminal == null) return null;
        return ctx.graphSync().snapshot(terminal);
    }

    private static Map<String, CompGraphNode> idMap(ComputationGraph g) {
        Map<String, CompGraphNode> out = new HashMap<>();
        for (CompGraphNode n : g.nodes()) out.put(n.id(), n);
        return out;
    }

    // ----- MSE helpers (mirrors mcc-core/GraphTrainer's private methods) -----

    private static double halfMse(Value out, Value target) {
        if (out instanceof MatrixValue mo && target instanceof MatrixValue mt
                && mo.data().length == mt.data().length) {
            double s = 0.0;
            for (int i = 0; i < mo.data().length; i++) {
                double d = mo.data()[i] - mt.data()[i];
                s += d * d;
            }
            return 0.5 * s;
        }
        if (out instanceof NumberValue no && target instanceof NumberValue nt) {
            double d = no.n() - nt.n();
            return 0.5 * d * d;
        }
        throw new IllegalArgumentException(
            "MSE loss only supports MATRIX or NUMBER outputs; got out=" + out.type()
                + " target=" + target.type());
    }

    private static Value mseGrad(Value out, Value target) {
        if (out instanceof MatrixValue mo && target instanceof MatrixValue mt) {
            double[] g = new double[mo.data().length];
            for (int i = 0; i < g.length; i++) g[i] = mo.data()[i] - mt.data()[i];
            return new MatrixValue(g);
        }
        if (out instanceof NumberValue no && target instanceof NumberValue nt) {
            return new NumberValue(no.n() - nt.n());
        }
        throw new IllegalArgumentException(
            "MSE gradient only supports MATRIX or NUMBER outputs; got " + out.type());
    }

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

    private static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
