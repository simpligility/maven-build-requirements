package dev.chainguard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StringUtils {
    private static final Logger log = LoggerFactory.getLogger(StringUtils.class);

    public static String capitalize(String input) {
        log.debug("Capitalizing: {}", input);
        return org.apache.commons.lang3.StringUtils.capitalize(input);
    }
}
