package io.github.git13166956007.dsh.event;

public final class RuntimeEvents {
    public static final EventKey<LifecycleEvent> LIFECYCLE =
            new EventKey<LifecycleEvent>("runtime.lifecycle", LifecycleEvent.class);

    private RuntimeEvents() { }

    public record LifecycleEvent(Phase phase, String runtimeId) {
        public enum Phase { STARTING, STARTED, STOPPING, STOPPED }
    }
}
