package com.adarsh.contract;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.adarsh.config.ConfigReader;
import com.adarsh.core.AuthManager;
import com.adarsh.core.AuthenticationException;
import com.adarsh.core.Endpoints;
import com.adarsh.core.SpecFactory;
import com.adarsh.models.Booking;
import com.adarsh.models.Credentials;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Consumer contract for the parts of Restful-Booker this framework depends on.
 *
 * <p>These tests are JUnit 5, not TestNG. Pact ships no TestNG runner, which is
 * why they live under {@code contract/} and run only under {@code -Pcontract}
 * with the JUnit platform provider. The default TestNG run excludes this package
 * outright: point one surefire execution at both providers and one of them
 * silently runs nothing at all.
 *
 * <p>The point of doing this on top of the live tests is the direction of the
 * check. The live suite asks "is the provider behaving today". A pact asks "here
 * is precisely what this consumer needs" and produces an artifact the provider
 * can verify against before shipping a change. So the interactions below are
 * written from the client's needs, including the awkward one: the framework
 * genuinely relies on POST /auth answering 200 with a body-level failure, and
 * that expectation belongs in the contract rather than in a comment.
 *
 * <p>Each test drives the real {@link SpecFactory} and {@link AuthManager}
 * against Pact's mock provider by repointing {@code BASE_URL}. Exercising the
 * production client, not a hand-rolled HTTP call, is what makes the contract
 * describe the consumer that actually exists.
 */
@ExtendWith(PactConsumerTestExt.class)
// V3 pinned explicitly: Pact 4.6 defaults to the V4 spec, which requires the
// PactBuilder/V4Pact method signature. The mismatch surfaces as an
// UnsupportedOperationException about a method signature rather than as
// anything mentioning pact versions.
@PactTestFor(providerName = "restful-booker", pactVersion = PactSpecVersion.V3)
class BookingConsumerPactTest {

    private static final String CONSUMER = "api-contract-testing-framework";

    @AfterEach
    void restoreConfiguration() {
        System.clearProperty("BASE_URL");
        ConfigReader.reload();
        AuthManager.invalidate();
    }

    /** Repoints the framework at the mock provider for the duration of one test. */
    private void useMockProvider(MockServer mockServer) {
        System.setProperty("BASE_URL", mockServer.getUrl());
        ConfigReader.reload();
        AuthManager.invalidate();
    }

    // ---------------------------------------------------------------- booking

    @Pact(consumer = CONSUMER)
    public RequestResponsePact bookingExists(PactDslWithProvider builder) {
        PactDslJsonBody booking = new PactDslJsonBody()
                .stringType("firstname", "Ada")
                .stringType("lastname", "Lovelace")
                .integerType("totalprice", 120)
                .booleanType("depositpaid", true)
                .object("bookingdates")
                    // stringMatcher rather than date(): Pact's date() generates today
                    // for every field, so checkin and checkout come back identical and
                    // BookingDates rightly refuses to construct a zero-night stay. The
                    // regex still pins the format, and the examples are a valid stay.
                    .stringMatcher("checkin", "[0-9]{4}-[0-9]{2}-[0-9]{2}", "2030-01-01")
                    .stringMatcher("checkout", "[0-9]{4}-[0-9]{2}-[0-9]{2}", "2030-01-05")
                .closeObject()
                .asBody()
                .stringType("additionalneeds", "Breakfast");

        return builder
                .given("a booking with id 1 exists")
                .uponReceiving("a request for booking 1")
                    .path("/booking/1")
                    .method("GET")
                    .headers(Map.of("Accept", "application/json"))
                .willRespondWith()
                    .status(200)
                    .headers(Map.of("Content-Type", "application/json; charset=utf-8"))
                    .body(booking)
                .toPact();
    }

    @Test
    @DisplayName("GET /booking/{id} returns a booking this framework can deserialise")
    @PactTestFor(pactMethod = "bookingExists")
    void getBookingMatchesTheContract(MockServer mockServer) {
        useMockProvider(mockServer);

        Booking booking = given().spec(SpecFactory.json())
                .pathParam("id", 1)
                .when().get(Endpoints.BOOKING_BY_ID)
                .then().statusCode(200)
                .extract().as(Booking.class);

        // The types are the contract; the values are illustrative. A field the
        // provider drops or retypes breaks this, a renamed value does not.
        assertNotNull(booking.firstname());
        assertNotNull(booking.bookingdates().checkin());
        assertTrue(booking.bookingdates().checkout().isAfter(booking.bookingdates().checkin()));
    }

    // ------------------------------------------------------------------- auth

    @Pact(consumer = CONSUMER)
    public RequestResponsePact authSucceeds(PactDslWithProvider builder) {
        return builder
                .given("the credentials are valid")
                .uponReceiving("a login with valid credentials")
                    .path("/auth")
                    .method("POST")
                    .headers(Map.of("Content-Type", "application/json"))
                    .body(new PactDslJsonBody()
                            .stringType("username", "admin")
                            .stringType("password", "password123"))
                .willRespondWith()
                    .status(200)
                    .headers(Map.of("Content-Type", "application/json; charset=utf-8"))
                    .body(new PactDslJsonBody().stringMatcher("token", "[A-Za-z0-9]+", "abc123def456ghi"))
                .toPact();
    }

    @Test
    @DisplayName("POST /auth returns a token in the body, not in a header")
    @PactTestFor(pactMethod = "authSucceeds")
    void authSuccessMatchesTheContract(MockServer mockServer) {
        useMockProvider(mockServer);

        String token = AuthManager.authenticate(new Credentials("admin", "password123"));

        assertNotNull(token);
        assertTrue(token.matches("[A-Za-z0-9]+"));
    }

    @Pact(consumer = CONSUMER)
    public RequestResponsePact authFailsWithTwoHundred(PactDslWithProvider builder) {
        return builder
                .given("the credentials are not valid")
                .uponReceiving("a login with bad credentials")
                    .path("/auth")
                    .method("POST")
                    .headers(Map.of("Content-Type", "application/json"))
                    .body(new PactDslJsonBody()
                            .stringType("username", "admin")
                            .stringType("password", "wrong"))
                .willRespondWith()
                    // 200, deliberately. This is the provider's real behaviour and the
                    // consumer is built around it; writing 401 here would produce a
                    // contract the provider cannot verify and a client that breaks in
                    // production while its pact stays green.
                    .status(200)
                    .headers(Map.of("Content-Type", "application/json; charset=utf-8"))
                    .body(new PactDslJsonBody().stringType("reason", "Bad credentials"))
                .toPact();
    }

    @Test
    @DisplayName("a rejected login is a 200 carrying reason, and the client must treat it as failure")
    @PactTestFor(pactMethod = "authFailsWithTwoHundred")
    void authFailureMatchesTheContract(MockServer mockServer) {
        useMockProvider(mockServer);

        AuthenticationException thrown = assertThrows(AuthenticationException.class,
                () -> AuthManager.authenticate(new Credentials("admin", "wrong")));

        assertTrue(thrown.getMessage().contains("Bad credentials"));
        assertEquals(false, thrown.getMessage().contains("HTTP 401"),
                "the failure is reported in the body; nothing here should claim a 401");
    }
}
