package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.TenantRuntime;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

final class TenantRuntimeIntegrationTest {
    @Test
    void multipleContextsShareOneTenantIsolatedEngine() throws Exception {
        Assumptions.assumeTrue(
                System.getProperty("polyglot.engine.IsolateLibrary") != null,
                "requires the Oreslang polyglot isolate library");

        IsolatePolicy policy = IsolatePolicy.developer();
        try (TenantRuntime tenant =
                     new TenantRuntime("tenant-test", policy, ExecutionProfile.serverJit());
             Context first = tenant.openContext();
             Context second = tenant.openContext()) {

            assertSame(tenant.engine(), first.getEngine());
            assertSame(tenant.engine(), second.getEngine());

            Source source = Source.newBuilder(OresLanguage.ID, """
                    pub routine main() => void {
                      val n = 1 + 1;
                      return;
                    }
                    """, "tenant-smoke.ores")
                    .mimeType(OresLanguage.MIME_TYPE)
                    .buildLiteral();

            first.eval(source);
            second.eval(source);
        }
    }
}
