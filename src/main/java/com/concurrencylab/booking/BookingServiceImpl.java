package com.concurrencylab.booking;

import com.concurrencylab.booking.dto.BookingResponse;
import com.concurrencylab.booking.dto.StatsResponse;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Your implementation goes here.
 *
 * Reminders:
 *   - Spring Boot serves requests on a thread pool (default ~200 threads).
 *     EVERY method below can be called by many threads at the same time.
 *   - There is no database. Storage lives in this instance's memory.
 *   - You decide every storage choice (collection types, counters, IDs).
 *   - Acceptance invariants (must hold under any load):
 *       I1: booked + available == capacity, always
 *       I2: booked never exceeds capacity
 *       I3: every successful createBooking returns an ID that listBookings will show
 *       I4: double-cancellation -> exactly one success, one 404
 *       I5: stats returns a coherent snapshot (numbers agree with each other)
 *       I6: 1000 RPS for 60s -> no 500 errors, no inconsistencies
 *
 * Throw the right exceptions; the GlobalExceptionHandler maps them to HTTP codes.
 */
@Service
public class BookingServiceImpl implements BookingService {


    @Override
    public String createConcert(String name, int capacity) {
        throw new UnsupportedOperationException("TODO: createConcert");
    }

    @Override
    public String createBooking(String concertId, int seats) {
        throw new UnsupportedOperationException("TODO: createBooking");
    }

    @Override
    public void cancelBooking(String concertId, String bookingId) {
        throw new UnsupportedOperationException("TODO: cancelBooking");
    }

    @Override
    public StatsResponse getStats(String concertId) {
        throw new UnsupportedOperationException("TODO: getStats");
    }

    @Override
    public List<BookingResponse> listBookings(String concertId) {
        throw new UnsupportedOperationException("TODO: listBookings");
    }
}
