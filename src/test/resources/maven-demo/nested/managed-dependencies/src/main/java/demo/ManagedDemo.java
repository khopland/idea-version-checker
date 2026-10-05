package demo;

import org.apache.commons.io.IOUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class ManagedDemo {
    private ManagedDemo() {}

    public static String read(String input) throws IOException {
        try (var stream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))) {
            return IOUtils.toString(stream, StandardCharsets.UTF_8);
        }
    }
}
