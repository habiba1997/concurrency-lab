package com.concurrencylab.booking.dto;

import java.time.Instant;

public record BookingResponse(String bookingId, int seats, Instant createdAt) {}
