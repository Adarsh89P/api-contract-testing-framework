package com.adarsh.tests.auth;

import com.adarsh.config.ConfigReader;
import com.adarsh.core.AuthManager;
import com.adarsh.core.AuthenticationException;
import com.adarsh.core.Endpoints;
import com.adarsh.core.SpecFactory;
import com.adarsh.models.AuthToken;
import com.adarsh.models.Credentials;
import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import io.qameta.allure.Issue;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.restassured.response.Response;
import org.testng.annotations.Test;

import static io.restassured.RestAssured.given;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * {@code POST /auth}.
 *
 * <p>The defining property of this endpoint is that it answers HTTP 200 whether
 * the login succeeded or not. Several tests below exist specifically to prove
 * that a status-code assertion is not a test of anything here.
 */
@Feature("Authentication")
public class AuthTest {

    @Test(description = "valid credentials yield a token")
    @Severity(SeverityLevel.BLOCKER)
    public void validCredentialsReturnAToken() {
        String token = AuthManager.authenticate(ConfigReader.credentials());

        assertNotNull(token);
        assertTrue(token.matches("[A-Za-z0-9]{10,}"),
                "expected an opaque alphanumeric token, got: " + token);
    }

    @Test(description = "a failed login is reported as HTTP 200 with a reason, not as 401")
    @Issue("FINDINGS-2")
    @Severity(SeverityLevel.CRITICAL)
    @Description("The trap this whole framework is built around. A wrong password "
            + "produces 200 OK with {\"reason\":\"Bad credentials\"} and no token. Any "
            + "negative auth test written as expect(401), or even as expect(not 200), "
            + "passes here for the wrong reason. Asserting the body is the only "
            + "assertion that means anything.")
    public void badCredentialsAnswerTwoHundredWithoutAToken() {
        Response response = given()
                .spec(SpecFactory.json())
                .body(new Credentials("admin", "definitely-not-the-password"))
                .when()
                .post(Endpoints.AUTH);

        assertEquals(response.statusCode(), 200,
                "known defect FINDINGS-2 may be fixed; /auth now returns "
                        + response.statusCode() + ". Update this test and FINDINGS.md.");

        AuthToken body = response.as(AuthToken.class);
        assertNull(body.token(), "a rejected login must not carry a token");
        assertEquals(body.reason(), "Bad credentials");
        assertFalse(body.isSuccess());
    }

    @Test(description = "AuthManager refuses to treat a tokenless 200 as success")
    @Severity(SeverityLevel.BLOCKER)
    @Description("The guard that makes every downstream negative test honest. Without "
            + "it, AuthManager would hand out null and the failure would surface much "
            + "later as an unexplained 403 on some unrelated booking test.")
    public void authManagerThrowsOnTokenlessTwoHundred() {
        AuthenticationException thrown = expectThrows(AuthenticationException.class,
                () -> AuthManager.authenticate(new Credentials("admin", "wrong-password")));

        assertTrue(thrown.getMessage().contains("no token"),
                "the message should explain what actually went wrong, got: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains("Bad credentials"),
                "the message should carry the server's reason, got: " + thrown.getMessage());
    }

    @Test(description = "an empty credential object is rejected, still as HTTP 200")
    public void emptyCredentialsAreRejected() {
        Response response = given()
                .spec(SpecFactory.json())
                .body("{}")
                .when()
                .post(Endpoints.AUTH);

        assertEquals(response.statusCode(), 200);
        assertEquals(response.as(AuthToken.class).reason(), "Bad credentials");
    }

    @Test(description = "a username with no password is rejected")
    public void partialCredentialsAreRejected() {
        expectThrows(AuthenticationException.class,
                () -> AuthManager.authenticate(new Credentials("admin", null)));
    }

    @Test(description = "a malformed body is rejected at the HTTP layer")
    @Description("Contrast with the cases above: syntactically invalid JSON does get "
            + "a 4xx. The 200 is specific to a well-formed request with wrong values.")
    public void malformedBodyIsRejectedWithFourHundred() {
        given().spec(SpecFactory.json())
                .body("this is not json")
                .when().post(Endpoints.AUTH)
                .then().statusCode(400);
    }

    @Test(description = "the cached token is reused rather than re-fetched",
            dependsOnMethods = "validCredentialsReturnAToken")
    @Description("Re-authenticating per request triples traffic against a shared "
            + "free-tier service. The cache is static so one token serves every thread.")
    public void tokenIsCachedAcrossCalls() {
        AuthManager.invalidate();
        assertFalse(AuthManager.hasFreshToken());

        String first = AuthManager.token();
        assertTrue(AuthManager.hasFreshToken());
        String second = AuthManager.token();

        assertSame(first, second, "the second call should have returned the cached instance");
    }

    @Test(description = "invalidating the cache forces a fresh login",
            dependsOnMethods = "tokenIsCachedAcrossCalls")
    public void invalidateForcesReauthentication() {
        AuthManager.token();
        AuthManager.invalidate();

        assertFalse(AuthManager.hasFreshToken(), "cache should be empty after invalidate()");
        assertNotNull(AuthManager.token(), "the next call should log in again");
    }
}
