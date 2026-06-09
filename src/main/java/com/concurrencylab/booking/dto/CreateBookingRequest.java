package com.concurrencylab.booking.dto;

import jakarta.validation.constraints.Min;

public record CreateBookingRequest(@Min(1) int seats) {}
