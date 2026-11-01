package dev.clientlocate;

import org.junit.jupiter.api.Test;

import java.util.List;

/** Compares this mod with what a real vanilla 1.21.1 server printed for /locate (vanilla_locate.json). */
class ParityTest {
    @Test
    void matchesVanilla() throws Exception {
        ParityRunner.run("PARITY", "/vanilla_locate.json", List.of(), 94);
    }
}
