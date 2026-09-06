package com.adarsh.tests.booking;

import com.adarsh.core.BaseApiTest;
import com.adarsh.core.Endpoints;
import com.adarsh.core.SpecFactory;
import com.adarsh.models.Booking;
import com.adarsh.models.BookingResponse;
import com.adarsh.utils.BookingFactory;
import com.adarsh.utils.SchemaValidator;
import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import io.qameta.allure.Issue;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.restassured.response.Response;
import org.testng.annotations.Test;

import static io.restassured.RestAssured.given;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * {@code PUT}, {@code PATCH} and {@code DELETE} on {@code /booking/&#123;id&#125;},
 * plus the authorisation rules that guard them. These are the only endpoints
 * that need a token.
 */
@Feature("Booking - update and delete")
public class UpdateDeleteBookingTest extends BaseApiTest {

    /** Creates a booking to operate on and registers it for cleanup. */
    private int freshBooking() {
        BookingResponse created = given().spec(SpecFactory.json())
                .body(BookingFactory.random())
                .when().post(Endpoints.BOOKING).as(BookingResponse.class);
        registerForCleanup(created.bookingid());
        return created.bookingid();
    }

    @Test(description = "PUT replaces the whole booking")
    @Severity(SeverityLevel.BLOCKER)
    public void putReplacesTheBooking() {
        int id = freshBooking();
        Booking replacement = BookingFactory.random();

        Response response = given().spec(SpecFactory.authenticated()).pathParam("id", id)
                .body(replacement).when().put(Endpoints.BOOKING_BY_ID);

        assertEquals(response.statusCode(), 200);
        SchemaValidator.assertMatches(response, SchemaValidator.BOOKING);
        assertEquals(response.as(Booking.class), replacement);

        Response fetched = given().spec(SpecFactory.json()).pathParam("id", id)
                .when().get(Endpoints.BOOKING_BY_ID);
        assertEquals(fetched.as(Booking.class), replacement, "the replacement was not persisted");
    }

    @Test(description = "PATCH updates only the fields supplied")
    @Severity(SeverityLevel.CRITICAL)
    public void patchUpdatesOnlyTheGivenFields() {
        int id = freshBooking();
        Booking before = given().spec(SpecFactory.json()).pathParam("id", id)
                .when().get(Endpoints.BOOKING_BY_ID).as(Booking.class);
        String newFirstName = BookingFactory.uniqueFirstName();

        Response response = given().spec(SpecFactory.authenticated()).pathParam("id", id)
                .body("{\"firstname\":\"" + newFirstName + "\"}")
                .when().patch(Endpoints.BOOKING_BY_ID);

        assertEquals(response.statusCode(), 200);
        SchemaValidator.assertMatches(response, SchemaValidator.BOOKING);

        Booking after = response.as(Booking.class);
        assertEquals(after.firstname(), newFirstName);
        assertEquals(after, before.withName(newFirstName, before.lastname()),
                "PATCH changed a field that was not in the request body");
    }

    @Test(description = "DELETE removes the booking, and it is gone afterwards")
    @Severity(SeverityLevel.BLOCKER)
    public void deleteRemovesTheBooking() {
        int id = freshBooking();

        given().spec(SpecFactory.authenticated()).pathParam("id", id)
                .when().delete(Endpoints.BOOKING_BY_ID)
                .then().statusCode(201);

        given().spec(SpecFactory.json()).pathParam("id", id)
                .when().get(Endpoints.BOOKING_BY_ID)
                .then().statusCode(404);
    }

    @Test(description = "DELETE answers 201 Created, which is the wrong status for a deletion")
    @Issue("FINDINGS-4")
    @Description("A deletion creates nothing. The correct answer is 204 No Content, or "
            + "200 with a body. 201 Created is actively misleading, and a client written "
            + "against the HTTP spec would treat it as a failure. Note that the cleanup "
            + "in BaseApiTest has to accept 201 as success because of this.")
    public void deleteAnswersCreatedInsteadOfNoContent() {
        int id = freshBooking();

        int status = given().spec(SpecFactory.authenticated()).pathParam("id", id)
                .when().delete(Endpoints.BOOKING_BY_ID).statusCode();

        assertEquals(status, 201,
                "known defect FINDINGS-4 may be fixed; DELETE now returns " + status
                        + ". Update this test, BaseApiTest.deleteCreatedBookings and FINDINGS.md.");
    }

    @Test(description = "deleting an already-deleted booking answers 405, not 404")
    @Issue("FINDINGS-10")
    @Description("Once the record is gone the route stops matching, so the framework "
            + "falls through to a 405 Method Not Allowed on a resource that simply does "
            + "not exist. A caller retrying a delete cannot distinguish 'already gone' "
            + "from 'this endpoint does not support DELETE'.")
    public void deletingTwiceAnswersMethodNotAllowed() {
        int id = freshBooking();

        given().spec(SpecFactory.authenticated()).pathParam("id", id)
                .when().delete(Endpoints.BOOKING_BY_ID).then().statusCode(201);

        int second = given().spec(SpecFactory.authenticated()).pathParam("id", id)
                .when().delete(Endpoints.BOOKING_BY_ID).statusCode();

        assertEquals(second, 405,
                "known defect FINDINGS-10 may be fixed; a repeat DELETE now returns "
                        + second + ". Update this test and FINDINGS.md.");
    }

    @Test(description = "PUT without a token is refused")
    @Severity(SeverityLevel.BLOCKER)
    public void putWithoutTokenIsRefused() {
        int id = freshBooking();

        Response response = given().spec(SpecFactory.json()).pathParam("id", id)
                .body(BookingFactory.random()).when().put(Endpoints.BOOKING_BY_ID);

        assertEquals(response.statusCode(), 403, "an unauthenticated write must not succeed");
    }

    @Test(description = "DELETE without a token is refused")
    @Severity(SeverityLevel.BLOCKER)
    public void deleteWithoutTokenIsRefused() {
        int id = freshBooking();

        given().spec(SpecFactory.json()).pathParam("id", id)
                .when().delete(Endpoints.BOOKING_BY_ID)
                .then().statusCode(403);

        given().spec(SpecFactory.json()).pathParam("id", id)
                .when().get(Endpoints.BOOKING_BY_ID)
                .then().statusCode(200);
    }

    @Test(description = "a fabricated token is refused")
    @Severity(SeverityLevel.BLOCKER)
    @Description("The token is opaque and unsigned, so this also confirms it is "
            + "checked against server state rather than merely parsed.")
    public void fabricatedTokenIsRefused() {
        int id = freshBooking();

        given().spec(SpecFactory.json()).cookie("token", "deadbeefdeadbeef")
                .pathParam("id", id)
                .when().delete(Endpoints.BOOKING_BY_ID)
                .then().statusCode(403);
    }

    @Test(description = "missing authorisation answers 403 where 401 is the correct status")
    @Issue("FINDINGS-8")
    @Description("403 Forbidden means 'authenticated, but not allowed'. A request with "
            + "no credentials at all is 401 Unauthorized, and the response should carry "
            + "a WWW-Authenticate header telling the client how to authenticate. Neither "
            + "happens, so a client cannot tell 'log in' from 'you may never do this'.")
    public void unauthenticatedRequestUsesForbiddenInsteadOfUnauthorized() {
        int id = freshBooking();

        Response response = given().spec(SpecFactory.json()).pathParam("id", id)
                .when().delete(Endpoints.BOOKING_BY_ID);

        assertEquals(response.statusCode(), 403,
                "known defect FINDINGS-8 may be fixed; got " + response.statusCode());
        assertTrue(response.getHeader("WWW-Authenticate") == null,
                "a WWW-Authenticate header appeared; FINDINGS-8 is partially fixed");
    }

    @Test(description = "the token is read from a cookie, not from an Authorization header")
    @Description("An API quirk rather than a defect, but one that silently breaks any "
            + "client built on the usual bearer-token assumption: the header is ignored "
            + "and the request is refused as if no credentials were sent.")
    public void bearerHeaderIsNotAccepted() {
        int id = freshBooking();
        String token = com.adarsh.core.AuthManager.token();

        given().spec(SpecFactory.json()).pathParam("id", id)
                .header("Authorization", "Bearer " + token)
                .when().delete(Endpoints.BOOKING_BY_ID)
                .then().statusCode(403);

        given().spec(SpecFactory.json()).pathParam("id", id)
                .cookie("token", token)
                .when().delete(Endpoints.BOOKING_BY_ID)
                .then().statusCode(201);
    }

    @Test(description = "PUT on a booking that does not exist answers 405")
    public void putOnMissingBookingIsRejected() {
        int status = given().spec(SpecFactory.authenticated()).pathParam("id", 999_999_999)
                .body(BookingFactory.random()).when().put(Endpoints.BOOKING_BY_ID).statusCode();

        assertTrue(status == 404 || status == 405,
                "expected the update to be refused for a missing booking, got " + status);
    }
}
