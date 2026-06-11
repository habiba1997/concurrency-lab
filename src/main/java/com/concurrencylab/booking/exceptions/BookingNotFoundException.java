package com.concurrencylab.booking.exceptions;

public class BookingNotFoundException extends RuntimeException {
    public BookingNotFoundException(String bookingId) {
        super("Booking not found: " + bookingId);
    }
}
