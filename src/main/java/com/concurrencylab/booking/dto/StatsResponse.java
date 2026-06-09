package com.concurrencylab.booking.dto;

public record StatsResponse(
        int capacity,
        int booked,
        int available,
        long totalBookings,
        long totalCancellations) {}
