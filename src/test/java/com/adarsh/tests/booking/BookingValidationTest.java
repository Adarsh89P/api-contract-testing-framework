package com.adarsh.tests.booking;

import com.adarsh.core.BaseApiTest;
import com.adarsh.core.Endpoints;
import com.adarsh.core.SpecFactory;
import com.adarsh.utils.SchemaValidator;
import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import io.qameta.allure.Issue;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.restassured.response.Response;
import org.testng.annotations.Test;

import java.time.LocalDate;

import static io.restassured.RestAssured.given;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * Input validation on {@code POST /booking}, which is where this API is weakest.
 *
 * <p>Every payload here is built as raw JSON rather than from {@link
 * com.adarsh.models.Booking}. That is deliberate: the model layer rejects an
 * inverted stay by construction, and a test that cannot express its own input
 * without weakening the model is a test that lies about what it sent. Where the
 * point of the case is to send something invalid, the string is the honest
 * representation.
 */
@Feature("Booking - validation")
public class BookingValidationTest extends BaseApiTest {

    private static final String CHECKIN = LocalDate.now().plusDays(30).toString();
    private static final String CHECKOUT = LocalDate.now().plusDays(35).toString();

    private Response post(String body) {
        return given().spec(SpecFactory.json()).body(body).when().post(Endpoints.BOOKING);
    }

    /** Registers the id if one came back, so nothing a negative test creates is left behind. */
    private void registerIfCreated(Response response) {
        Integer id = response.jsonPath().get("bookingid");
        if (id != null) {
            registerForCleanup(id);
        }
    }

    @Test(description = "a booking missing required fields answers 500, not 400")
    @Issue("FINDINGS-5")
    @Severity(SeverityLevel.CRITICAL)
    @Description("The most serious defect found. An incomplete payload is a client "
            + "error and belongs in the 4xx range with a message naming the missing "
            + "fields. Instead the service throws: HTTP 500 with a plain-text body, "
            + "which tells the caller nothing and looks like an outage on a dashboard.")
    public void incompletePayloadCausesAServerError() {
        Response response = post("{\"firstname\":\"OnlyAName\"}");

        assertEquals(response.statusCode(), 500,
                "known defect FINDINGS-5 may be fixed; an incomplete payload now returns "
                        + response.statusCode() + ". Update this test and FINDINGS.md.");
        assertTrue(response.getContentType().startsWith("text/plain"),
                "a 500 here carries no machine-readable body either, got content type "
                        + response.getContentType());
    }

    @Test(description = "an empty object also answers 500")
    @Issue("FINDINGS-5")
    public void emptyPayloadCausesAServerError() {
        assertEquals(post("{}").statusCode(), 500);
    }

    @Test(description = "a stay that ends before it starts is accepted")
    @Issue("FINDINGS-6")
    @Severity(SeverityLevel.CRITICAL)
    @Description("checkout precedes checkin by nine days and the booking is stored "
            + "anyway. BookingDates refuses to construct this, so the payload is raw "
            + "JSON - the model would not let the framework send it by accident.")
    public void invertedStayIsAccepted() {
        String late = LocalDate.now().plusDays(40).toString();
        String early = LocalDate.now().plusDays(31).toString();

        Response response = post("""
                {"firstname":"Inverted","lastname":"Stay","totalprice":100,"depositpaid":true,
                 "bookingdates":{"checkin":"%s","checkout":"%s"}}""".formatted(late, early));
        registerIfCreated(response);

        assertEquals(response.statusCode(), 200,
                "known defect FINDINGS-6 may be fixed; an inverted stay now returns "
                        + response.statusCode() + ". Update this test and FINDINGS.md.");
        assertEquals(response.jsonPath().getString("booking.bookingdates.checkin"), late);
        assertEquals(response.jsonPath().getString("booking.bookingdates.checkout"), early);
    }

    @Test(description = "a same-day stay of zero nights is accepted")
    @Issue("FINDINGS-6")
    public void zeroNightStayIsAccepted() {
        Response response = post("""
                {"firstname":"ZeroNight","lastname":"Stay","totalprice":100,"depositpaid":true,
                 "bookingdates":{"checkin":"%s","checkout":"%s"}}""".formatted(CHECKIN, CHECKIN));
        registerIfCreated(response);

        assertEquals(response.statusCode(), 200);
    }

    @Test(description = "a negative total price is accepted and stored")
    @Issue("FINDINGS-7")
    @Severity(SeverityLevel.CRITICAL)
    @Description("A booking worth minus five hundred is stored and reads back "
            + "unchanged. Nothing in the API constrains the field to a sensible range.")
    public void negativeTotalPriceIsAccepted() {
        Response response = post("""
                {"firstname":"Negative","lastname":"Price","totalprice":-500,"depositpaid":true,
                 "bookingdates":{"checkin":"%s","checkout":"%s"}}""".formatted(CHECKIN, CHECKOUT));
        registerIfCreated(response);

        assertEquals(response.statusCode(), 200,
                "known defect FINDINGS-7 may be fixed; a negative price now returns "
                        + response.statusCode() + ". Update this test and FINDINGS.md.");
        assertEquals(response.jsonPath().getInt("booking.totalprice"), -500);
    }

    @Test(description = "an unparseable date is stored as the literal string 0NaN-aN-aN")
    @Issue("FINDINGS-11")
    @Severity(SeverityLevel.CRITICAL)
    @Description("The API parses \"not-a-date\" with a date routine, gets NaN, "
            + "stringifies the result and persists it. The record is now permanently "
            + "corrupt: it violates the response schema, and any client that parses "
            + "checkin will fail on a booking it did not create. Rejecting the request "
            + "would have been the smaller problem.")
    public void unparseableDateIsPersistedAsCorruptData() {
        Response response = post("""
                {"firstname":"Corrupt","lastname":"Date","totalprice":100,"depositpaid":true,
                 "bookingdates":{"checkin":"not-a-date","checkout":"%s"}}""".formatted(CHECKOUT));
        registerIfCreated(response);

        assertEquals(response.statusCode(), 200,
                "known defect FINDINGS-11 may be fixed; got " + response.statusCode());
        assertEquals(response.jsonPath().getString("booking.bookingdates.checkin"), "0NaN-aN-aN",
                "expected the corrupted value this defect produces");

        // And it is not merely echoed - it is stored, so it breaks the read schema too.
        Response fetched = given().spec(SpecFactory.json())
                .pathParam("id", response.jsonPath().getInt("bookingid"))
                .when().get(Endpoints.BOOKING_BY_ID);

        assertEquals(fetched.jsonPath().getString("bookingdates.checkin"), "0NaN-aN-aN");
        assertTrue(!SchemaValidator.matches(SchemaValidator.BOOKING).matches(fetched.asString()),
                "a stored booking that violates its own response schema should not validate");
    }

    @Test(description = "a numeric string price is silently coerced rather than rejected")
    @Issue("FINDINGS-12")
    @Description("\"999\" is accepted and reads back as the number 999. Harmless in "
            + "isolation, but it means the API's idea of a valid price is 'anything "
            + "JavaScript will coerce', which is why the schemas here say integer.")
    public void stringPriceIsCoercedToANumber() {
        Response response = post("""
                {"firstname":"Coerced","lastname":"Price","totalprice":"999","depositpaid":true,
                 "bookingdates":{"checkin":"%s","checkout":"%s"}}""".formatted(CHECKIN, CHECKOUT));
        registerIfCreated(response);

        assertEquals(response.statusCode(), 200);
        assertEquals(response.jsonPath().getInt("booking.totalprice"), 999);
        SchemaValidator.assertMatches(response, SchemaValidator.BOOKING_CREATED);
    }

    @Test(description = "an unrecognised field is dropped rather than echoed or rejected")
    @Description("Correct behaviour, asserted so a future change to it is visible. "
            + "If the API started echoing unknown fields, additionalProperties:false "
            + "would begin failing everywhere and this test says why.")
    public void unknownFieldIsIgnored() {
        Response response = post("""
                {"firstname":"Extra","lastname":"Field","totalprice":100,"depositpaid":true,
                 "bookingdates":{"checkin":"%s","checkout":"%s"},"loyaltytier":"gold"}"""
                .formatted(CHECKIN, CHECKOUT));
        registerIfCreated(response);

        assertEquals(response.statusCode(), 200);
        assertNull(response.jsonPath().get("booking.loyaltytier"));
        SchemaValidator.assertMatches(response, SchemaValidator.BOOKING_CREATED);
    }

    @Test(description = "GET on an id that does not exist answers 404 in plain text")
    @Description("Correct status, but the body is the bare word 'Not Found' rather "
            + "than a JSON error object - so a client cannot parse errors and successes "
            + "the same way. Asserted rather than glossed over.")
    public void missingBookingAnswersNotFound() {
        Response response = given().spec(SpecFactory.json())
                .pathParam("id", 999_999_999)
                .when().get(Endpoints.BOOKING_BY_ID);

        assertEquals(response.statusCode(), 404);
        assertTrue(response.getContentType().startsWith("text/plain"),
                "error bodies are plain text, not JSON; got " + response.getContentType());
    }

    @Test(description = "a non-numeric id answers 404 rather than 400")
    public void nonNumericIdAnswersNotFound() {
        given().spec(SpecFactory.json()).pathParam("id", "not-an-id")
                .when().get(Endpoints.BOOKING_BY_ID)
                .then().statusCode(404);
    }
}
