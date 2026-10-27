package com.vchat.config;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class VchatPropertiesTest {
    private final VchatProperties.Ai ai = new VchatProperties.Ai("claude-sonnet-5-5", List.of("claude-sonnet-5-5", " claude-haiku-5-5 "));

    @Test
    void choicesStartWithTheDefaultWithoutDuplicates() {
        assertEquals(List.of("claude-sonnet-5-5", "claude-haiku-5-5"), ai.choices());
    }

    @Test
    void botModelIsUsedOnlyWhileItIsOffered() {
        assertEquals("claude-haiku-5-5", ai.resolve("claude-haiku-5-5"));
        assertEquals("claude-sonnet-5-5", ai.resolve(null));
        assertEquals("claude-sonnet-5-5", ai.resolve("claude-opus-5-5")); // not in the allow-list
        assertFalse(ai.allows("claude-opus-5-5"));
    }
}
