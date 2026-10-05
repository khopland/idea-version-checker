package demo;

import com.google.common.base.Joiner;
import org.slf4j.Logger;

public final class LiteralDemo {
    private LiteralDemo() {}

    public static String join(String... words) {
        return Joiner.on(" ").join(words);
    }

    public static String loggerName(Logger logger) {
        return logger.getName();
    }
}
