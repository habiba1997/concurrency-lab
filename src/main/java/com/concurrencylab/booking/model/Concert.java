package com.concurrencylab.booking.model;

import static com.concurrencylab.utils.Utils.generateUUID;

import com.concurrencylab.booking.exceptions.InsufficientSeatsException;
import java.time.Instant;
import java.util.HashMap;
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
    private Integer canceledSeats = 0;
    private HashMap<String, Booking> bookings = new HashMap<>();

    public Concert(String name, Integer capacity) {
        this.name = name;
        this.capacity = capacity;
    }

    public Integer getReservedSeats() {
        return reservedSeats.intValue();
    }

    public void setReservedSeats(Integer reservedSeats) {
        boolean setted = this.reservedSeats.compareAndSet(this.getReservedSeats(), reservedSeats);
        if (!setted) {
            log.error("Issue Occurred: Failed to update reserved seats for concert {}. Expected: {}, Actual: {}",
                    this.name, this.getReservedSeats(), reservedSeats);
        }
    }

    public synchronized Booking reserve(int reservedSeats) {
        Integer availableSeats = this.getAvailableSeats();
        if (availableSeats < reservedSeats) {
            throw new InsufficientSeatsException(reservedSeats, availableSeats);
        }
        this.setReservedSeats(this.getReservedSeats() + reservedSeats);
        Booking booking = new Booking(generateUUID(), reservedSeats, Instant.now());
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


    public void cancelBooking(String bookingId) {
        Booking booking = bookings.get(bookingId);
        this.canceledSeats = canceledSeats + booking.getSeats();
        this.setReservedSeats(this.getReservedSeats() - booking.getSeats());
        this.getBookings().remove(bookingId);
    }
}
