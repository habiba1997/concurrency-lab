package com.concurrencylab.booking.model;

import static com.concurrencylab.utils.Utils.generateUUID;

import com.concurrencylab.booking.exceptions.InsufficientSeatsException;
import java.time.Instant;
import java.util.HashMap;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;


@Getter
@Setter
@AllArgsConstructor
public class Concert {

    private String name;
    private Integer capacity = 0;
    private Integer reservedSeats = 0;
    private Integer canceledSeats = 0;
    private HashMap<String, Booking> bookings = new HashMap<>();

    public Concert(String name, Integer capacity) {
        this.name = name;
        this.capacity = capacity;
    }

    public Booking reserve(int reservedSeats) {
        Integer availableSeats = this.getAvailableSeats();
        if (availableSeats < reservedSeats) {
            throw new InsufficientSeatsException(reservedSeats, availableSeats);
        }
        this.setReservedSeats(this.reservedSeats + reservedSeats);
        Booking booking = new Booking(generateUUID(), reservedSeats, Instant.now());
        this.bookings.put(booking.getUuid(), booking);
        return booking;
    }

    public Integer getAvailableSeats() {
        int available = capacity - reservedSeats;
        if (available < 0) {
            throw new IllegalArgumentException("Issue Occurred: Seats must be greater than zero");
        }
        return available;
    }


    public void cancelBooking(String bookingId) {
        Booking booking = bookings.get(bookingId);
        this.canceledSeats = canceledSeats + booking.getSeats();
        this.reservedSeats = this.reservedSeats - booking.getSeats();
        this.getBookings().remove(bookingId);
    }
}
