package com.adarsh.core;

import com.adarsh.config.ConfigReader;
import com.adarsh.models.AuthToken;
import com.adarsh.models.Credentials;
import io.qameta.allure.Allure;
import io.restassured.response.Response;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

import static io.restassured.RestAssured.given;

/**
 * Obtains and caches the Restful-Booker session token.
 *
 * <h2>The tokenless 200</h2>
 * {@code POST /auth} answers <strong>HTTP 200 for a failed login</strong>, with
 * {@code {"reason":"Bad credentials"}} in place of {@code {"token":"..."}}. A
 * test that asserts only on the status code therefore passes for every wrong
 * password ever tried, and so does a client that reads the status and moves on.
 * {@link #authenticate} treats a 200 without a token as a failure and throws,
 * which is the single most important line in this class.
 *
 * <h2>Caching</h2>
 * The token is cached with a conservative TTL rather than fetched per request or
 * held forever. Re-authenticating on every call roughly triples traffic against
 * a shared free-tier service; caching forever is a bet that the provider will
 * never start expiring tokens, and that bet fails as a wave of unrelated 403s.
 * The cache is static, not thread-local: one token is valid on every thread, and
 * a thread-local cache would issue one login per worker for no benefit.
 */
public final class AuthManager {

    private static final Logger LOG = LogManager.getLogger(AuthManager.class);

    private static final ReentrantLock LOCK = new ReentrantLock();

    private static volatile CachedToken cached;

    private AuthManager() {
    }

    private record CachedToken(String value, Instant expiresAt) {
        boolean isFresh() {
            return Instant.now().isBefore(expiresAt);
        }
    }

    /**
     * The current token, logging in only if there is no fresh one.
     *
     * @throws IllegalStateException  if credentials are not configured
     * @throws AuthenticationException if the service will not issue a token
     */
    public static String token() {
        CachedToken snapshot = cached;
        if (snapshot != null && snapshot.isFresh()) {
            return snapshot.value();
        }
        LOCK.lock();
        try {
            // Re-check: another thread may have refreshed while this one waited.
            if (cached != null && cached.isFresh()) {
                return cached.value();
            }
            String fresh = authenticate(ConfigReader.credentials());
            Duration ttl = Duration.ofSeconds(ConfigReader.config().tokenTtlSeconds());
            cached = new CachedToken(fresh, Instant.now().plus(ttl));
            LOG.info("Obtained a session token, cached for {}", ttl);
            return fresh;
        } finally {
            LOCK.unlock();
        }
    }

    /**
     * Logs in and returns the token. Bypasses the cache; used by tests that need
     * a specific credential pair.
     *
     * @throws AuthenticationException on any response that does not carry a token,
     *                                 including the HTTP 200 that Restful-Booker
     *                                 returns for bad credentials
     */
    public static String authenticate(Credentials credentials) {
        Response response = given()
                .spec(SpecFactory.json())
                .body(credentials)
                .when()
                .post(Endpoints.AUTH);

        if (response.statusCode() != 200) {
            throw new AuthenticationException(
                    "POST /auth returned HTTP " + response.statusCode() + ": " + response.asString());
        }

        AuthToken body = response.as(AuthToken.class);
        if (!body.isSuccess()) {
            // The status code said 200. Only the body knows this failed.
            Allure.addAttachment("Tokenless 200 from POST /auth", "application/json",
                    response.asString(), ".json");
            throw new AuthenticationException(
                    "POST /auth returned HTTP 200 with no token (reason=" + body.reason() + "). "
                            + "This endpoint reports failure in the body, not the status line, so "
                            + "asserting on the status code alone would have passed here.");
        }
        return body.token();
    }

    /** Drops the cached token so the next call logs in again. */
    public static void invalidate() {
        LOCK.lock();
        try {
            cached = null;
        } finally {
            LOCK.unlock();
        }
    }

    /** Whether a usable token is currently cached; for tests of the cache itself. */
    public static boolean hasFreshToken() {
        CachedToken snapshot = cached;
        return snapshot != null && snapshot.isFresh();
    }
}
