package sibarum.ternion.app;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Watchdog for diagnosing UI freezes. The render thread calls
 * {@link #beat} each frame; a daemon thread checks every second and
 * dumps a full thread stack trace to stderr if the heartbeat hasn't
 * advanced in {@link #FREEZE_THRESHOLD_MS} milliseconds.
 *
 * <p>Logs once per detected freeze (re-arms when heartbeats resume), so
 * a sustained freeze produces a single dump rather than a flood.
 */
public final class FreezeDetector {

    private static final long FREEZE_THRESHOLD_MS = 5_000L;

    private static final AtomicLong lastBeatMs = new AtomicLong(System.currentTimeMillis());
    private static final AtomicLong frameCounter = new AtomicLong(0);
    private static volatile boolean dumped = false;
    private static volatile ScheduledExecutorService scheduler;

    private FreezeDetector() {}

    public static void start() {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ts-freeze-watchdog");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(FreezeDetector::check, 1, 1, TimeUnit.SECONDS);
    }

    /** Call once per rendered frame on the main thread. */
    public static void beat() {
        lastBeatMs.set(System.currentTimeMillis());
        frameCounter.incrementAndGet();
        if (dumped) {
            // Recovered — re-arm for the next freeze.
            dumped = false;
            System.err.println("[freeze-watchdog] heartbeat resumed at frame "
                + frameCounter.get());
        }
    }

    private static void check() {
        long elapsed = System.currentTimeMillis() - lastBeatMs.get();
        if (elapsed < FREEZE_THRESHOLD_MS) return;
        if (dumped) return;
        dumped = true;
        dump(elapsed);
    }

    private static void dump(long elapsedMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n========== FREEZE DETECTED ==========\n");
        sb.append("No render-thread heartbeat for ").append(elapsedMs).append(" ms.\n");
        sb.append("Last completed frame: ").append(frameCounter.get()).append("\n");
        sb.append("Full thread dump follows.\n\n");

        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        ThreadInfo[] infos = mx.dumpAllThreads(true, true);
        for (ThreadInfo info : infos) {
            sb.append('"').append(info.getThreadName()).append('"');
            sb.append(" #").append(info.getThreadId());
            sb.append(" state=").append(info.getThreadState());
            if (info.getLockName() != null) {
                sb.append(" waiting on ").append(info.getLockName());
            }
            if (info.getLockOwnerName() != null) {
                sb.append(" (owned by ").append(info.getLockOwnerName())
                    .append(" #").append(info.getLockOwnerId()).append(')');
            }
            sb.append('\n');
            for (StackTraceElement el : info.getStackTrace()) {
                sb.append("    at ").append(el).append('\n');
            }
            for (java.lang.management.MonitorInfo m : info.getLockedMonitors()) {
                sb.append("    locked ").append(m).append(" at ").append(m.getLockedStackFrame()).append('\n');
            }
            for (java.lang.management.LockInfo l : info.getLockedSynchronizers()) {
                sb.append("    holds AbstractQueuedSynchronizer ").append(l).append('\n');
            }
            sb.append('\n');
        }
        sb.append("========== END FREEZE DUMP ==========\n");
        System.err.println(sb);
        System.err.flush();
    }
}
