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
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.restassured.response.Response;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** {@code GET /booking} and {@code GET /booking/&#123;id&#125;}. */
@Feature("Booking - read")
public class ReadBookingTest extends BaseApiTest {

    private Booking seeded;
    private int seededId;

    @BeforeClass(alwaysRun = true)
    public void seedABooking() {
        seeded = BookingFactory.random();
        BookingResponse created = given().spec(SpecFactory.json()).body(seeded)
                .when().post(Endpoints.BOOKING).as(BookingResponse.class);
        seededId = created.bookingid();
        registerForCleanup(seededId);
    }

    @Test(description = "GET /booking returns a list of ids matching the schema")
    @Severity(SeverityLevel.CRITICAL)
    public void listReturnsBookingIds() {
        Response response = given().spec(SpecFactory.json()).when().get(Endpoints.BOOKING);

        assertEquals(response.statusCode(), 200);
        SchemaValidator.assertMatches(response, SchemaValidator.BOOKING_IDS);

        List<Integer> ids = response.jsonPath().getList("bookingid", Integer.class);
        assertFalse(ids.isEmpty(), "the shared instance should never have zero bookings");
        assertTrue(ids.contains(seededId),
                "the booking seeded by this class is missing from the unfiltered list");
    }

    @Test(description = "GET /booking/{id} returns the stored booking")
    @Severity(SeverityLevel.BLOCKER)
    public void getByIdReturnsTheBooking() {
        Response response = given().spec(SpecFactory.json()).pathParam("id", seededId)
                .when().get(Endpoints.BOOKING_BY_ID);

        assertEquals(response.statusCode(), 200);
        SchemaValidator.assertMatches(response, SchemaValidator.BOOKING);
        assertEquals(response.as(Booking.class), seeded);
    }

    @Test(description = "filtering by name finds only the seeded booking")
    @Description("The filter runs across every booking on a shared public instance, "
            + "which is why BookingFactory generates a name nobody else will have used. "
            + "With a fixed name this assertion would depend on strangers.")
    public void filterByNameReturnsTheSeededBooking() {
        Response response = given().spec(SpecFactory.json())
                .queryParam("firstname", seeded.firstname())
                .queryParam("lastname", seeded.lastname())
                .when().get(Endpoints.BOOKING);

        assertEquals(response.statusCode(), 200);
        SchemaValidator.assertMatches(response, SchemaValidator.BOOKING_IDS);

        List<Integer> ids = response.jsonPath().getList("bookingid", Integer.class);
        assertEquals(ids, List.of(seededId),
                "expected exactly the seeded booking for a name generated to be unique");
    }

    @Test(description = "a filter that matches nothing returns an empty list, not an error")
    public void filterWithNoMatchesReturnsEmptyList() {
        Response response = given().spec(SpecFactory.json())
                .queryParam("firstname", BookingFactory.uniqueFirstName())
                .when().get(Endpoints.BOOKING);

        assertEquals(response.statusCode(), 200);
        SchemaValidator.assertMatches(response, SchemaValidator.BOOKING_IDS);
        assertTrue(response.jsonPath().getList("bookingid").isEmpty(),
                "an unmatched filter should yield an empty array");
    }
}
