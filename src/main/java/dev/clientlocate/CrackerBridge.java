package dev.clientlocate;

import kaptainwutax.seedcrackerX.api.SeedCrackerAPI;

/** Receives the world seed from the built-in seed cracker and hands it to the locator. */
public class CrackerBridge implements SeedCrackerAPI {
    @Override
    public void pushWorldSeed(long seed) {
        ClientLocate.onSeedCracked(seed);
    }
}
