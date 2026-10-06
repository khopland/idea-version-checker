package demo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LiteralDemoTest {
    @Test
    public void joinsWords() {
        assertEquals("Maven version checker", LiteralDemo.join("Maven", "version", "checker"));
    }
}
