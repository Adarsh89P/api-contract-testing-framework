package com.adarsh.models;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;

/**
 * Check-in / check-out pair.
 *
 * <p>The canonical constructor rejects a check-out that is not strictly after
 * check-in. That makes an invalid stay unrepresentable in the model layer, which
 * is the point: the framework can never accidentally send a nonsensical range.
 *
 * <p>The API itself accepts inverted ranges (see FINDINGS.md). Tests that probe
 * that defect deliberately bypass this type and post raw JSON - if a payload is
 * invalid by construction, building it out of validated objects is a
 * contradiction, and a test that cannot express its own input is a test that
 * lies about what it sent.
 */
public record BookingDates(
        @JsonProperty("checkin") @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate checkin,
        @JsonProperty("checkout") @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate checkout) {

    public BookingDates {
        if (checkin == null || checkout == null) {
            throw new IllegalArgumentException("checkin and checkout are both required");
        }
        if (!checkout.isAfter(checkin)) {
            throw new IllegalArgumentException(
                    "checkout must be strictly after checkin, got checkin=" + checkin
                            + " checkout=" + checkout);
        }
    }

    /** A stay of {@code nights} starting {@code daysFromNow}, relative to today. */
    public static BookingDates startingIn(long daysFromNow, long nights) {
        LocalDate checkin = LocalDate.now().plusDays(daysFromNow);
        return new BookingDates(checkin, checkin.plusDays(nights));
    }

    public long nights() {
        return checkout.toEpochDay() - checkin.toEpochDay();
    }
}
