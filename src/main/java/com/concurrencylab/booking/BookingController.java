package com.concurrencylab.booking;

import com.concurrencylab.booking.dto.BookingResponse;
import com.concurrencylab.booking.dto.ConcertResponse;
import com.concurrencylab.booking.dto.CreateBookingRequest;
import com.concurrencylab.booking.dto.CreateConcertRequest;
import com.concurrencylab.booking.dto.StatsResponse;
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
    public ResponseEntity<ConcertResponse> createConcert(@Valid @RequestBody CreateConcertRequest req) {
        String id = service.createConcert(req.name(), req.capacity());
        return ResponseEntity
                .created(URI.create("/concerts/" + id))
                .body(new ConcertResponse(id, req.name(), req.capacity()));
    }

    @PostMapping("/{concertId}/bookings")
    public ResponseEntity<BookingResponse> createBooking(
            @PathVariable String concertId,
            @Valid @RequestBody CreateBookingRequest req) {
        String bookingId = service.createBooking(concertId, req.seats());
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(new BookingResponse(bookingId, req.seats(), java.time.Instant.now()));
    }

    @DeleteMapping("/{concertId}/bookings/{bookingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelBooking(@PathVariable String concertId, @PathVariable String bookingId) {
        service.cancelBooking(concertId, bookingId);
    }

    @GetMapping("/{concertId}/stats")
    public StatsResponse getStats(@PathVariable String concertId) {
        return service.getStats(concertId);
    }

    @GetMapping("/{concertId}/bookings")
    public List<BookingResponse> listBookings(@PathVariable String concertId) {
        return service.listBookings(concertId);
    }
}
