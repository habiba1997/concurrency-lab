package com.concurrencylab.booking;

import com.concurrencylab.booking.dto.StatsResponse;

import com.concurrencylab.booking.model.Booking;
import com.concurrencylab.booking.model.Concert;
import java.util.List;

public interface BookingService {

    Concert createConcert(String name, int capacity);

    Booking createBooking(String concertName, int seats);

    void cancelBooking(String concertName, String bookingId);

    StatsResponse getStats(String concertName);

    List<Booking> listBookings(String concertName);
}
