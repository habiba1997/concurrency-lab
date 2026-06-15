package com.concurrencylab.booking.model;

import static com.concurrencylab.utils.Utils.generateUUID;

import com.concurrencylab.booking.exceptions.BookingNotFoundException;
import com.concurrencylab.booking.exceptions.InsufficientSeatsException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;


@Slf4j
@Getter
@Setter
@AllArgsConstructor
public class Concert {

    private String name;
    private Integer capacity = 0;
    private AtomicInteger reservedSeats = new AtomicInteger(0);
    private AtomicInteger canceledSeats = new AtomicInteger(0);
    private Map<String, Booking> bookings = new ConcurrentHashMap<>();

    public Concert(String name, Integer capacity) {
        this.name = name;
        this.capacity = capacity;
    }

    public Integer getReservedSeats() {
        return reservedSeats.intValue();
    }

    public Booking reserve(int reservedSeatsValue) {

        while (true) {
            int current = this.reservedSeats.get();
            Integer availableSeats = this.getAvailableSeats();
            if (availableSeats < reservedSeatsValue) {
                throw new InsufficientSeatsException(reservedSeatsValue, availableSeats);
            }
            int newValue = reservedSeatsValue + current;
            if (this.reservedSeats.compareAndSet(current, newValue)) {
                break;
            }
        }
        Booking booking = new Booking(generateUUID(), reservedSeatsValue, Instant.now());
        this.bookings.put(booking.getUuid(), booking);
        return booking;
    }

    public Integer getAvailableSeats() {
        int available = capacity - this.getReservedSeats();
        if (available < 0) {
            throw new IllegalArgumentException("Issue Occurred: Seats must be greater than zero");
        }
        return available;
    }

    public Integer getCanceledSeats() {
        return canceledSeats.intValue();
    }

    public void cancelBooking(String bookingId) {
        Booking booking = bookings.remove(bookingId);   // atomic: returns old value or null
        if (booking == null) {
            throw new BookingNotFoundException(bookingId);
        }
        canceledSeats.addAndGet(booking.getSeats());
        reservedSeats.addAndGet(-booking.getSeats());
    }
}
