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
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/** {@code POST /booking}. Creation needs no token on this API. */
@Feature("Booking - create")
public class CreateBookingTest extends BaseApiTest {

    @Test(description = "a booking is created and echoed back unchanged")
    @Severity(SeverityLevel.BLOCKER)
    public void createReturnsIdAndEchoesTheBooking() {
        Booking request = BookingFactory.random();

        Response response = given().spec(SpecFactory.json()).body(request)
                .when().post(Endpoints.BOOKING);

        SchemaValidator.assertMatches(response, SchemaValidator.BOOKING_CREATED);

        BookingResponse created = response.as(BookingResponse.class);
        registerForCleanup(created.bookingid());

        assertTrue(created.bookingid() > 0, "expected a positive id, got " + created.bookingid());
        assertEquals(created.booking(), request,
                "the echoed booking must be exactly what was sent");
    }

    @Test(description = "POST /booking answers 200 OK where 201 Created is required")
    @Issue("FINDINGS-3")
    @Description("A request that creates a resource should answer 201 with a Location "
            + "header. This one answers 200 and sends no Location, so a client has to "
            + "parse the body to learn the id of the thing it just made. Pinned as a "
            + "characterisation test.")
    public void createAnswersOkInsteadOfCreated() {
        Response response = given().spec(SpecFactory.json()).body(BookingFactory.random())
                .when().post(Endpoints.BOOKING);
        registerForCleanup(response.as(BookingResponse.class).bookingid());

        assertEquals(response.statusCode(), 200,
                "known defect FINDINGS-3 may be fixed; POST /booking now returns "
                        + response.statusCode() + ". Update this test and FINDINGS.md.");
        assertNull(response.getHeader("Location"),
                "a Location header appeared; FINDINGS-3 is partially fixed");
    }

    @Test(description = "additionalneeds is optional and is omitted, not nulled, when absent")
    public void createWithoutOptionalFieldSucceeds() {
        Booking request = BookingFactory.withoutAdditionalNeeds();

        Response response = given().spec(SpecFactory.json()).body(request)
                .when().post(Endpoints.BOOKING);

        SchemaValidator.assertMatches(response, SchemaValidator.BOOKING_CREATED);
        BookingResponse created = response.as(BookingResponse.class);
        registerForCleanup(created.bookingid());

        assertNull(created.booking().additionalneeds());
        assertNull(response.jsonPath().get("booking.additionalneeds"),
                "the key should be absent, not present-and-null");
    }

    @Test(description = "each create yields a distinct id")
    public void idsAreUniquePerCreate() {
        int first = createAndRegister();
        int second = createAndRegister();

        assertTrue(first != second, "two creates returned the same id: " + first);
    }

    @Test(description = "a created booking is immediately readable")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Read-after-write, kept separate from the create assertion: the echo "
            + "in the POST response and the stored record are two different claims, and "
            + "an API can get the first right while getting the second wrong.")
    public void createdBookingIsRetrievableWithTheSameContent() {
        Booking request = BookingFactory.random();
        BookingResponse created = given().spec(SpecFactory.json()).body(request)
                .when().post(Endpoints.BOOKING).as(BookingResponse.class);
        registerForCleanup(created.bookingid());

        Response fetched = given().spec(SpecFactory.json())
                .pathParam("id", created.bookingid())
                .when().get(Endpoints.BOOKING_BY_ID);

        SchemaValidator.assertMatches(fetched, SchemaValidator.BOOKING);
        assertEquals(fetched.as(Booking.class), request,
                "the stored booking differs from what was sent");
    }

    private int createAndRegister() {
        BookingResponse created = given().spec(SpecFactory.json())
                .body(BookingFactory.random())
                .when().post(Endpoints.BOOKING).as(BookingResponse.class);
        assertNotNull(created.booking());
        registerForCleanup(created.bookingid());
        return created.bookingid();
    }
}
