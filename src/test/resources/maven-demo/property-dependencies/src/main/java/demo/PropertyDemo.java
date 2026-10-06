package demo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;

public final class PropertyDemo {
    private PropertyDemo() {}

    public static String greetingJson(String name) throws JsonProcessingException {
        return new ObjectMapper().writeValueAsString(Map.of("greeting", StringUtils.capitalize(name)));
    }
}
