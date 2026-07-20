package resforge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import resforge.res.Layer;
import resforge.res.ResContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainProcessTest {
    @TempDir
    Path tmp;

    record Result(int exit, String out, String err) {
    }

    @Test
    void helpExitsSuccessfully() throws Exception {
        Result r = run("--help");

        assertEquals(0, r.exit);
        assertTrue(r.out.contains("Usage:"));
        assertTrue(r.out.contains("verify <file.res | dir>"));
        assertTrue(r.out.contains("[--action name]"));
        assertEquals("", r.err);
    }

    @Test
    void unknownCommandExitsWithUsageError() throws Exception {
        Result r = run("not-a-command");

        assertEquals(2, r.exit);
        assertTrue(r.err.contains("unknown command: not-a-command"));
        assertTrue(r.out.contains("Usage:"));
    }

    @Test
    void missingCommandAndMissingOperandExitWithUsageError() throws Exception {
        Result noCommand = run();
        Result noOperand = run("info");

        assertEquals(2, noCommand.exit);
        assertTrue(noCommand.err.contains("no command given"));
        assertEquals(2, noOperand.exit);
        assertTrue(noOperand.err.contains("info requires a .res file"));
    }

    @Test
    void ioFailureExitsWithRuntimeError() throws Exception {
        Result r = run("info", tmp.resolve("absent.res").toString());

        assertEquals(1, r.exit);
        assertTrue(r.err.startsWith("error: "));
        assertEquals("", r.out);
    }

    @Test
    void infoAndVerifyDispatchSyntheticResource() throws Exception {
        ResContainer resource = new ResContainer(23);
        resource.layers.add(new Layer("tooltip", "synthetic".getBytes(StandardCharsets.UTF_8)));
        Path file = tmp.resolve("synthetic resource.res");
        Files.write(file, resource.serialize());

        Result info = run("info", file.toString());
        Result verify = run("verify", file.toString());

        assertEquals(0, info.exit);
        assertTrue(info.out.contains("res-version: 23"));
        assertTrue(info.out.contains("layers: 1"));
        assertTrue(info.out.contains("tooltip"));
        assertEquals("", info.err);
        assertEquals(0, verify.exit);
        assertTrue(verify.out.contains("Verified 1 file(s): 1 passed, 0 failed"));
        assertEquals("", verify.err);
    }

    private Result run(String... args) throws Exception {
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path java = javaHome.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-Djava.awt.headless=true");
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dstdout.encoding=UTF-8");
        command.add("-Dstderr.encoding=UTF-8");
        command.add("-cp");
        command.add(Path.of(Main.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI()).toString());
        command.add(Main.class.getName());
        command.addAll(List.of(args));

        Path stdout = tmp.resolve("stdout-" + System.nanoTime() + ".txt");
        Path stderr = tmp.resolve("stderr-" + System.nanoTime() + ".txt");
        Process process = new ProcessBuilder(command)
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .start();
        boolean finished = process.waitFor(Duration.ofSeconds(20).toMillis(), TimeUnit.MILLISECONDS);
        if(!finished) {
            process.destroyForcibly();
            throw new AssertionError("resforge.Main subprocess did not exit");
        }
        return new Result(process.exitValue(), Files.readString(stdout), Files.readString(stderr));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").startsWith("Windows");
    }
}
