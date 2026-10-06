package demo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ManagedDemoTest {
    @Test
    public void readsText() throws Exception {
        assertEquals("Nested Maven module", ManagedDemo.read("Nested Maven module"));
    }
}
