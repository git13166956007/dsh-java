package io.github.git13166956007.dsh.core.scope;

import io.github.git13166956007.dsh.core.profile.ProfilePatch;
import io.github.git13166956007.dsh.service.ServiceKey;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class ScopeTest {
    private static final ServiceKey<String> VALUE = new ServiceKey<String>("test.value", String.class);

    @Test
    void resolvesParentsAppliesProfilesAndClosesEffects() {
        Scope root = new Scope("root");
        root.provide(VALUE, "root-value");
        AtomicBoolean closed = new AtomicBoolean();
        Scope child = root.child("child", new ProfilePatch("model-a", "prompt-a", null, null,
                java.util.Map.of("filesystem", "read")));
        child.effect(() -> closed.set(true));

        assertEquals("root-value", child.resolve(VALUE));
        assertEquals("model-a", child.profile().modelId());
        assertEquals("root", child.profile().parentId());
        assertEquals("read", child.profile().permissions().get("filesystem"));
        child.close();
        assertTrue(closed.get());
        root.close();
    }
}
