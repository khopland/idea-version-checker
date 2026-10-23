package io.github.khopland.versionchecker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** A separate JVM exercises cancellation without relying on a shell or network timing. */
public final class MavenProcessFixture {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "arguments" -> {
                System.out.println(String.join("\n", Arrays.copyOfRange(args, 1, args.length)));
                System.err.println("fixture stderr");
            }
            case "failure" -> {
                System.err.println("401 Unauthorized fixture");
                System.exit(7);
            }
            case "output" -> {
                System.out.print("x".repeat(100_000));
                System.err.print("y".repeat(100_000));
            }
            case "tree" -> {
                String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
                new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), MavenProcessFixture.class.getName(),
                    "wait", args[2]).inheritIO().start();
                long deadline = System.nanoTime() + 10_000_000_000L;
                while (!Files.exists(Path.of(args[2])) && System.nanoTime() < deadline) Thread.sleep(10);
                waitForCancellation(args[1]);
            }
            case "wait" -> waitForCancellation(args[1]);
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    private static void waitForCancellation(String marker) throws Exception {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { Thread.sleep(60_000); } catch (InterruptedException ignored) { }
        }));
        Files.writeString(Path.of(marker), Long.toString(ProcessHandle.current().pid()));
        Thread.sleep(60_000);
    }
}
