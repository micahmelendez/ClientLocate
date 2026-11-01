package dev.clientlocate;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

/**
 * Same comparison with Terralith on a real server. The Terralith jar and the recording are not part of
 * the repository: pass -Dclientlocate.terralith=/path/Terralith.zip -Dclientlocate.terralith.recording=/tmp/results.json.
 */
class TerralithParityTest {
    @Test
    void matchesTerralithServer() throws Exception {
        String jar = System.getProperty("clientlocate.terralith");
        String rec = System.getProperty("clientlocate.terralith.recording");
        Assumptions.assumeTrue(jar != null && rec != null, "Terralith parity inputs not provided");
        List<Path> extra = new java.util.ArrayList<>();
        for (String part : jar.split(java.io.File.pathSeparator)) extra.add(Path.of(part));
        ParityRunner.run("TPARITY", rec, extra, 0);
    }
}
