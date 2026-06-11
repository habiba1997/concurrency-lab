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
        // Look up concert by ID. If missing → ConcertNotFoundException.
        if (!concerts.containsKey(concertName)) {
            throw new ConcertNotFoundException(concertName);
        }
        Concert concert = concerts.get(concertName);
        //Look up booking by ID inside the concert. If missing OR already cancelled → BookingNotFoundException.
        if (!concert.getBookings().containsKey(bookingId)) {
            throw new BookingNotFoundException(bookingId);
        }
        concert.cancelBooking(bookingId);
    }

    @Override
    public StatsResponse getStats(String concertName) {
        // Look up concert by ID. If missing → ConcertNotFoundException.
        if (!concerts.containsKey(concertName)) {
            throw new ConcertNotFoundException(concertName);
        }
        Concert concert = concerts.get(concertName);
        return new StatsResponse(concert.getCapacity(), concert.getReservedSeats(), concert.getAvailableSeats(),
                concert.getReservedSeats() + concert.getCanceledSeats(), concert.getCanceledSeats());
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
