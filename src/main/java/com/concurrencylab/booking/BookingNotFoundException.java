package com.concurrencylab.booking;

public class BookingNotFoundException extends RuntimeException {
    public BookingNotFoundException(String bookingId) {
        super("Booking not found: " + bookingId);
    }
}
