package com.concurrencylab.booking.exceptions;

public class ConcertNotFoundException extends RuntimeException {
    public ConcertNotFoundException(String concertName) {
        super("Concert not found: " + concertName);
    }
}
