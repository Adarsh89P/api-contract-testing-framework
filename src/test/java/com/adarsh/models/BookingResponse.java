package com.adarsh.models;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Body of {@code POST /booking}: the new id plus an echo of the stored booking.
 * The echo is what makes round-trip assertions possible without a second GET.
 */
public record BookingResponse(
        @JsonProperty("bookingid") int bookingid,
        @JsonProperty("booking") Booking booking) {
}
