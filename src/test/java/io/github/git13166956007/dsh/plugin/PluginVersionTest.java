package io.github.git13166956007.dsh.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginVersionTest {
    @Test
    void supportsExactCaretTildeAndComparisonRanges() {
        assertTrue(PluginVersion.satisfies("1.4.2", "^1.2.0"));
        assertFalse(PluginVersion.satisfies("2.0.0", "^1.2.0"));
        assertTrue(PluginVersion.satisfies("1.4.2", "~1.4.0"));
        assertFalse(PluginVersion.satisfies("1.5.0", "~1.4.0"));
        assertTrue(PluginVersion.satisfies("1.4.2", ">=1.0.0 <2.0.0"));
    }
}
