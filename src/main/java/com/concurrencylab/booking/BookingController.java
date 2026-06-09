package com.concurrencylab.booking;

import com.concurrencylab.booking.dto.CreateBookingRequest;
import com.concurrencylab.booking.dto.CreateConcertRequest;
import com.concurrencylab.booking.dto.StatsResponse;
import com.concurrencylab.booking.model.Booking;
import com.concurrencylab.booking.model.Concert;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/concerts")
public class BookingController {

    private final BookingService service;

    public BookingController(BookingService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Concert> createConcert(@Valid @RequestBody CreateConcertRequest req) {
        Concert concert = service.createConcert(req.name(), req.capacity());
        return ResponseEntity
                .created(URI.create("/concerts/" + concert.getName()))
                .body(concert);
    }

    @PostMapping("/{concertId}/bookings")
    public ResponseEntity<Booking> createBooking(
            @PathVariable String concertId,
            @Valid @RequestBody CreateBookingRequest req) {
        Booking booking = service.createBooking(concertId, req.seats());
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(booking);
    }

    @DeleteMapping("/{concertId}/bookings/{bookingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelBooking(@PathVariable String concertId, @PathVariable Integer bookingId) {
        service.cancelBooking(concertId, bookingId);
    }

    @GetMapping("/{concertId}/stats")
    public StatsResponse getStats(@PathVariable String concertId) {
        return service.getStats(concertId);
    }

    @GetMapping("/{concertId}/bookings")
    public List<Booking> listBookings(@PathVariable String concertId) {
        return service.listBookings(concertId);
    }
}
