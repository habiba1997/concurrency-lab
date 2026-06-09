package com.concurrencylab.booking;

public class ConcertNotFoundException extends RuntimeException {
    public ConcertNotFoundException(String concertId) {
        super("Concert not found: " + concertId);
    }
}
