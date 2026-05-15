package sibarum.ternion.train;

/**
 * Lifecycle states of the {@link TrainingController}. Transitions:
 * <ul>
 *   <li>IDLE → RUNNING via {@code start()}</li>
 *   <li>RUNNING → PAUSED via {@code pause()}</li>
 *   <li>PAUSED → RUNNING via {@code resume()}</li>
 *   <li>PAUSED → RUNNING-for-one-example → PAUSED via {@code step()}</li>
 *   <li>any → STOPPED via {@code stop()}</li>
 * </ul>
 *
 * <p>The {@link TrainingController} also tracks a transient
 * "step requested" flag while in PAUSED that the worker observes.
 */
public enum TrainingState {
    IDLE,
    RUNNING,
    PAUSED,
    STOPPED
}
