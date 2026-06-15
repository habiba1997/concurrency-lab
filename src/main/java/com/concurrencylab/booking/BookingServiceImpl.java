package com.concurrencylab.booking;

import com.concurrencylab.booking.dto.StatsResponse;
import com.concurrencylab.booking.exceptions.BookingNotFoundException;
import com.concurrencylab.booking.exceptions.ConcertNotFoundException;
import com.concurrencylab.booking.model.Booking;
import com.concurrencylab.booking.model.Concert;
import java.util.HashMap;
import java.util.List;
import org.springframework.stereotype.Service;


@Service
public class BookingServiceImpl implements BookingService {

    private HashMap<String, Concert> concerts = new HashMap<>();

    @Override
    public Concert createConcert(String name, int capacity) {
        if (concerts.containsKey(name)) {
            Concert concert = concerts.get(name);
            if (concert.getCapacity() > capacity) {
                throw new IllegalArgumentException(
                        "Issue Occurred: New capacity must be greater than current capacity");
            }
            concert.setCapacity(capacity);
            concerts.put(name, concert);
        } else {
            concerts.put(name, new Concert(name, capacity));
        }
        return concerts.get(name);
    }

    @Override
    public Booking createBooking(String concertName, int seats) {
        if (!concerts.containsKey(concertName)) {
            throw new ConcertNotFoundException(concertName);
        }
        Concert concert = concerts.get(concertName);
        Booking reservedBooking = concert.reserve(seats);
        concerts.put(concertName, concert);
        return reservedBooking;
    }

    @Override
    public void cancelBooking(String concertName, String bookingId) {
        Concert concert = concerts.get(concertName);
        if (concert == null) throw new ConcertNotFoundException(concertName);
        concert.cancelBooking(bookingId);
    }

    @Override
    public StatsResponse getStats(String concertName) {
        // Look up concert by ID. If missing → ConcertNotFoundException.
        if (!concerts.containsKey(concertName)) {
            throw new ConcertNotFoundException(concertName);
        }
        Concert concert = concerts.get(concertName);

        Integer capacity = concert.getCapacity();
        Integer reservedSeats = concert.getReservedSeats();
        Integer canceledSeats = concert.getCanceledSeats();
        return new StatsResponse(capacity, reservedSeats, capacity - reservedSeats,
                reservedSeats + canceledSeats, canceledSeats);
    }

    @Override
    public List<Booking> listBookings(String concertName) {
        // Look up concert by ID. If missing → ConcertNotFoundException.
        if (!concerts.containsKey(concertName)) {
            throw new ConcertNotFoundException(concertName);
        }
        return concerts.get(concertName).getBookings().values().stream().toList();
    }

}
