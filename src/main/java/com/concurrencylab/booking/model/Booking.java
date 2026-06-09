package com.concurrencylab.booking.model;

import java.time.Instant;

public final class Booking {
    private final String id;
    private final int seats;
    private final Instant createdAt;

    public Booking(String id, int seats, Instant createdAt) {
        this.id = id;
        this.seats = seats;
        this.createdAt = createdAt;
    }

    public String getId()         { return id; }
    public int getSeats()         { return seats; }
    public Instant getCreatedAt() { return createdAt; }
}
