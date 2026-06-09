package com.concurrencylab.booking;

public class InsufficientSeatsException extends RuntimeException {
    public InsufficientSeatsException(int requested, int available) {
        super("Requested " + requested + " seats, only " + available + " available");
    }
}
