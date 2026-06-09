package com.concurrencylab.booking.exceptions;

public class BookingNotFoundException extends RuntimeException {
    public BookingNotFoundException(Integer bookingId) {
        super("Booking not found: " + bookingId);
    }
}
