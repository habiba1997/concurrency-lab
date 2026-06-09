package com.concurrencylab.booking.dto;

public record StatsResponse(
        Integer capacity,
        Integer booked,
        Integer available,
        Integer totalBookings,
        Integer totalCancellations) {}
