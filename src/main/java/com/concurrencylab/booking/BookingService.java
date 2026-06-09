package com.concurrencylab.booking;

import com.concurrencylab.booking.dto.BookingResponse;
import com.concurrencylab.booking.dto.StatsResponse;

import java.util.List;

public interface BookingService {

    String createConcert(String name, int capacity);

    String createBooking(String concertId, int seats);

    void cancelBooking(String concertId, String bookingId);

    StatsResponse getStats(String concertId);

    List<BookingResponse> listBookings(String concertId);
}
