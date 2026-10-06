package demo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PropertyDemoTest {
    @Test
    public void writesJson() throws Exception {
        assertEquals("{\"greeting\":\"Maven\"}", PropertyDemo.greetingJson("maven"));
    }
}
