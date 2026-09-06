package com.adarsh.config;

import com.adarsh.models.Credentials;
import org.aeonbits.owner.ConfigFactory;

/**
 * Accessor for {@link FrameworkConfig}. Holds the instance so Owner parses the
 * sources once, and turns missing credentials into an actionable failure rather
 * than a NullPointerException three layers down.
 */
public final class ConfigReader {

    private static volatile FrameworkConfig config = ConfigFactory.create(FrameworkConfig.class);

    private ConfigReader() {
    }

    public static FrameworkConfig config() {
        return config;
    }

    public static String baseUrl() {
        return stripTrailingSlash(config.baseUrl());
    }

    /**
     * Resolved lazily and never cached, so a suite that touches no protected
     * endpoint can run with no secrets present.
     *
     * @throws IllegalStateException if either credential is missing or blank
     */
    public static Credentials credentials() {
        String username = config.username();
        String password = config.password();
        if (isBlank(username) || isBlank(password)) {
            throw new IllegalStateException(
                    "Credentials are not configured. Set BOOKER_USERNAME and BOOKER_PASSWORD as "
                            + "environment variables, or pass -DBOOKER_USERNAME=... -DBOOKER_PASSWORD=... . "
                            + "The smoke suite is read-only and does not need them.");
        }
        return new Credentials(username, password);
    }

    /** Re-reads every source. Exists for tests that mutate system properties. */
    public static void reload() {
        ConfigFactory.setProperty("__reload__", String.valueOf(System.nanoTime()));
        config = ConfigFactory.create(FrameworkConfig.class);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
