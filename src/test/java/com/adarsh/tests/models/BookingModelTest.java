package com.adarsh.tests.models;

import com.adarsh.models.AuthToken;
import com.adarsh.models.Booking;
import com.adarsh.models.BookingDates;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import org.testng.annotations.Test;

import java.time.LocalDate;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/** Offline checks on the model layer's serialisation contract and invariants. */
@Feature("Models")
public class BookingModelTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test(description = "an inverted stay cannot be constructed")
    public void invertedDatesAreRejected() {
        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class,
                () -> new BookingDates(LocalDate.of(2030, 1, 10), LocalDate.of(2030, 1, 1)));
        assertTrue(thrown.getMessage().contains("strictly after"));
    }

    @Test(description = "a zero-night stay cannot be constructed")
    public void sameDayCheckoutIsRejected() {
        LocalDate day = LocalDate.of(2030, 1, 10);
        expectThrows(IllegalArgumentException.class, () -> new BookingDates(day, day));
    }

    @Test(description = "dates serialise as yyyy-MM-dd, not as an ISO timestamp or an array")
    @Description("Restful-Booker stores dates as plain calendar days; a LocalDate "
            + "serialised with Jackson's default would arrive as an array and be rejected.")
    public void datesSerialiseAsPlainCalendarDays() throws Exception {
        String json = mapper.writeValueAsString(
                new BookingDates(LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 3)));
        assertEquals(json, "{\"checkin\":\"2030-01-01\",\"checkout\":\"2030-01-03\"}");
    }

    @Test(description = "additionalneeds is omitted rather than sent as null")
    public void optionalFieldIsOmittedWhenAbsent() throws Exception {
        String json = mapper.writeValueAsString(new Booking(
                "A", "B", 10, true, BookingDates.startingIn(1, 2), null));
        assertFalse(json.contains("additionalneeds"), "expected the key to be absent, got " + json);
    }

    @Test(description = "an unknown response field is a failure, not a silent drop")
    @Description("Jackson's fail-on-unknown default is kept on purpose so a provider "
            + "adding a field breaks visibly, the same way additionalProperties:false does.")
    public void unknownFieldsAreRejected() {
        String withExtra = """
                {"firstname":"A","lastname":"B","totalprice":10,"depositpaid":true,
                 "bookingdates":{"checkin":"2030-01-01","checkout":"2030-01-03"},
                 "loyaltytier":"gold"}""";
        expectThrows(UnrecognizedPropertyException.class,
                () -> mapper.readValue(withExtra, Booking.class));
    }

    @Test(description = "a tokenless auth body is not a success")
    public void authTokenModelsBothArms() throws Exception {
        AuthToken failure = mapper.readValue("{\"reason\":\"Bad credentials\"}", AuthToken.class);
        assertFalse(failure.isSuccess());
        assertEquals(failure.reason(), "Bad credentials");

        AuthToken success = mapper.readValue("{\"token\":\"abc123\"}", AuthToken.class);
        assertTrue(success.isSuccess());
        assertFalse(success.toString().contains("abc123"), "token must not leak via toString");
    }

    @Test(description = "relative date helper produces the requested stay length")
    public void relativeDatesAreAnchoredToToday() {
        BookingDates dates = BookingDates.startingIn(7, 3);
        assertEquals(dates.checkin(), LocalDate.now().plusDays(7));
        assertEquals(dates.nights(), 3);
    }
}
