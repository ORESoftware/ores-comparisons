package dev.oreslang.launcher;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.runtime.OresDiagnosticRenderer;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class OresMain {
    private OresMain() { }

    /**
     * Only the standalone command-line host terminates the process. Tests and
     * embedders use the exit-status boundary below.
     */
    public static void main(String[] args) {
        int status = exitStatusForArgs(args, System.out, System.err);
        if (status != 0) System.exit(status);
    }

    static int exitStatusForArgs(String[] args) {
        return exitStatusForArgs(args, System.out, System.err);
    }

    static int exitStatusForArgs(String[] args, PrintStream out, PrintStream err) {
        boolean json = hasJsonFlag(args);
        try {
            int status = runForExitStatus(args, out, err);
            out.flush();
            err.flush();
            if (status == 0 && (out.checkError() || err.checkError())) {
                err.println("oreslang: failed to publish CLI output");
                return 1;
            }
            return status;
        } catch (UsageException failure) {
            if (json) err.println(OresDiagnosticRenderer.renderJson(failure));
            else err.println("oreslang: " + failure.getMessage());
            return 2;
        } catch (Exception failure) {
            if (json) err.println(OresDiagnosticRenderer.renderJson(failure));
            else err.println(OresDiagnosticRenderer.renderText(failure));
            return 1;
        }
    }

    private static int runForExitStatus(
            String[] args,
            PrintStream out,
            PrintStream err) throws Exception {
        boolean strict = false;
        String mode = "jit";
        String platform = "server";
        List<IsolatePolicy.Capability> additionalCapabilities = new ArrayList<>();
        String filename = null;

        for (String arg : args) {
            try {
                if (arg.equals("--json")) {
                    // Diagnostic transport only. Program stdout remains unchanged.
                } else if (arg.equals("--strict-isolate")) strict = true;
                else if (arg.startsWith("--mode=")) mode = arg.substring("--mode=".length());
                else if (arg.startsWith("--platform=")) platform = arg.substring("--platform=".length());
                else if (arg.startsWith("--allow=")) {
                    String raw = arg.substring("--allow=".length());
                    if (raw.isBlank()) {
                        throw new UsageException("--allow requires a comma-separated capability list");
                    }
                    for (String value : raw.split(",", -1)) {
                        if (value.isBlank()) {
                            throw new UsageException("--allow cannot contain empty capabilities");
                        }
                        additionalCapabilities.add(IsolatePolicy.Capability.valueOf(
                                value.trim().toUpperCase(Locale.ROOT)));
                    }
                } else if (arg.startsWith("--")) {
                    throw new UsageException("unknown option: " + arg);
                } else if (filename == null) {
                    filename = arg;
                } else {
                    throw new UsageException("only one .ores file may be supplied");
                }
            } catch (IllegalArgumentException invalid) {
                if (invalid instanceof UsageException usage) throw usage;
                throw new UsageException(invalid.getMessage());
            }
        }

        if (filename == null) {
            throw new UsageException(usage());
        }

        final Path path;
        try {
            path = Path.of(filename);
        } catch (InvalidPathException invalid) {
            throw new UsageException("invalid source path: " + invalid.getMessage());
        }
        if (!Files.isRegularFile(path)) {
            throw new UsageException("not a file: " + path);
        }

        final ExecutionProfile profile;
        try {
            profile = ExecutionProfile.parse(mode, platform);
        } catch (IllegalArgumentException invalid) {
            throw new UsageException(invalid.getMessage());
        }

        IsolatePolicy policy = strict ? IsolatePolicy.strictFaas() : IsolatePolicy.developer();
        if (!additionalCapabilities.isEmpty()) {
            policy = policy.withCapabilities(
                    additionalCapabilities.toArray(IsolatePolicy.Capability[]::new));
        }

        LinkedProgramRunner.run(path, policy, profile, out, err);
        return 0;
    }

    private static boolean hasJsonFlag(String[] args) {
        for (String arg : args) {
            if ("--json".equals(arg)) return true;
        }
        return false;
    }

    private static String usage() {
        return "usage: ores [--json] [--strict-isolate] "
                + "[--mode=aot|jit|hybrid] "
                + "[--platform=server|windows|macos|linux|android|ios] "
                + "[--allow=CAP,...] <file.ores>";
    }

    private static final class UsageException extends IllegalArgumentException {
        UsageException(String message) {
            super(message == null || message.isBlank() ? "invalid command line" : message);
        }
    }
}
