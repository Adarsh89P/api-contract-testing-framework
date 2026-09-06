package com.adarsh.config;

import org.aeonbits.owner.Config;
import org.aeonbits.owner.Config.LoadPolicy;
import org.aeonbits.owner.Config.LoadType;

/**
 * Single source of framework settings.
 *
 * <p>The {@link Sources} order <em>is</em> the precedence rule: a {@code -D} flag
 * beats an environment variable, which beats {@code config/config.properties}.
 * Nothing else in this codebase calls {@code System.getenv} or
 * {@code System.getProperty} to read a setting.
 *
 * <p>{@link LoadType#MERGE} is load-bearing, not decoration. Owner's default is
 * {@link LoadType#FIRST}, which uses only the first source that loads at all -
 * and {@code system:properties} always loads. Left on the default, every source
 * below it is dead: the properties file is never opened and every value silently
 * comes from {@code @DefaultValue}. MERGE reads all three and lets the earliest
 * source that defines a key win, which is the precedence actually wanted here.
 *
 * <p>Keys are {@code UPPER_SNAKE_CASE} deliberately. Owner looks up the
 * environment source by exact key name, and most CI systems (GitHub Actions
 * included) will not accept dots in an environment variable name. Uppercase keys
 * are the one spelling that is valid in all three sources at once, so
 * {@code -DBASE_URL=...}, {@code export BASE_URL=...} and {@code BASE_URL=...} in
 * the properties file all address the same setting.
 */
@LoadPolicy(LoadType.MERGE)
@Config.Sources({
        "system:properties",
        "system:env",
        "classpath:config/config.properties"
})
public interface FrameworkConfig extends Config {

    /**
     * No {@code @DefaultValue}: the checked-in properties file is the default, so
     * a regression that stops the file being read fails loudly instead of being
     * masked by an annotation.
     */
    @Key("BASE_URL")
    String baseUrl();

    /**
     * Credentials have no default on purpose. They resolve to {@code null} when
     * unset, and {@link ConfigReader#credentials()} is what turns that into an
     * error — so the read-only smoke suite runs with no secrets configured at all.
     */
    @Key("BOOKER_USERNAME")
    String username();

    @Key("BOOKER_PASSWORD")
    String password();

    /** Free-tier dynos cold-start; the first request of a run can take ~30s. */
    @Key("HTTP_CONNECT_TIMEOUT_MS")
    @DefaultValue("30000")
    int connectTimeoutMs();

    @Key("HTTP_RESPONSE_TIMEOUT_MS")
    @DefaultValue("60000")
    int responseTimeoutMs();

    /** Conservative token lifetime; see AuthManager for why this is not "forever". */
    @Key("TOKEN_TTL_SECONDS")
    @DefaultValue("600")
    long tokenTtlSeconds();

    @Key("LOG_HTTP")
    @DefaultValue("false")
    boolean logHttp();
}
