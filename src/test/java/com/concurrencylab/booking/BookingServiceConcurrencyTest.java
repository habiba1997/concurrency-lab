package com.concurrencylab.booking;

import com.concurrencylab.booking.dto.StatsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure JUnit + ExecutorService test that hammers the service in-process.
 *
 * Enable each test (remove @Disabled) AFTER you've implemented the corresponding
 * service method. These tests are intentionally aggressive and will FAIL if your
 * implementation has the classic concurrency bugs.
 *
 * NOTE: concurrency bugs are non-deterministic. A test passing once does NOT mean
 * you're safe. Run each test repeatedly:
 *     mvn test -Dtest=BookingServiceConcurrencyTest -DforkCount=1 -DreuseForks=false
 * or just loop:
 *     for i in {1..20}; do mvn -q test -Dtest=BookingServiceConcurrencyTest || break; done
 */
class BookingServiceConcurrencyTest {

    private BookingService service;
    private String concertId;
    private static final int CAPACITY = 100;
    private static final int CONCURRENT_BOOKERS = 500;

    @BeforeEach
    void setUp() {
        service = new BookingServiceImpl();
        concertId = service.createConcert("Overbooking Test", CAPACITY);
    }

    @Disabled("Remove @Disabled once createBooking is implemented")
    @Test
    void overbookingTest_500ThreadsBook1Seat_exactly100Succeed() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(CONCURRENT_BOOKERS);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        for (int i = 0; i < CONCURRENT_BOOKERS; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    service.createBooking(concertId, 1);
                    successes.incrementAndGet();
                } catch (InsufficientSeatsException e) {
                    failures.incrementAndGet();
                } catch (Exception e) {
                    fail("Unexpected exception: " + e);
                } finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(endGate.await(30, TimeUnit.SECONDS), "Test timed out");
        pool.shutdown();

        assertEquals(CAPACITY, successes.get(),
                "INVARIANT VIOLATION: expected exactly " + CAPACITY + " successful bookings");
        assertEquals(CONCURRENT_BOOKERS - CAPACITY, failures.get(),
                "INVARIANT VIOLATION: expected " + (CONCURRENT_BOOKERS - CAPACITY) + " failures");

        StatsResponse stats = service.getStats(concertId);
        assertEquals(CAPACITY, stats.booked(), "stats.booked must equal capacity after oversell");
        assertEquals(0, stats.available(), "stats.available must be zero");
        assertEquals(CAPACITY, stats.booked() + stats.available(), "INVARIANT I1 violated");
    }

    @Disabled("Remove @Disabled once getStats is implemented")
    @Test
    void statsConsistency_underWriteStorm_neverInconsistent() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(200);
        AtomicInteger inconsistencies = new AtomicInteger();

        // 100 writer threads, 100 reader threads
        for (int i = 0; i < 100; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    try { service.createBooking(concertId, 1); } catch (Exception ignored) {}
                } catch (InterruptedException ignored) {
                } finally { endGate.countDown(); }
            });
        }
        for (int i = 0; i < 100; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    for (int j = 0; j < 100; j++) {
                        StatsResponse s = service.getStats(concertId);
                        if (s.booked() + s.available() != CAPACITY) {
                            inconsistencies.incrementAndGet();
                        }
                    }
                } catch (InterruptedException ignored) {
                } finally { endGate.countDown(); }
            });
        }

        startGate.countDown();
        assertTrue(endGate.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(0, inconsistencies.get(),
                "INVARIANT I1/I5 VIOLATED: observed " + inconsistencies.get()
                        + " stats reads where booked + available != capacity");
    }

    @Disabled("Remove @Disabled once cancelBooking is implemented")
    @Test
    void doubleCancellation_exactlyOneSucceeds() throws Exception {
        String bookingId = service.createBooking(concertId, 1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger notFound = new AtomicInteger();

        Runnable cancel = () -> {
            try {
                start.await();
                service.cancelBooking(concertId, bookingId);
                ok.incrementAndGet();
            } catch (BookingNotFoundException e) {
                notFound.incrementAndGet();
            } catch (Exception e) {
                fail("Unexpected: " + e);
            }
        };

        Future<?> f1 = pool.submit(cancel);
        Future<?> f2 = pool.submit(cancel);
        start.countDown();
        f1.get(5, TimeUnit.SECONDS);
        f2.get(5, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, ok.get(),  "Exactly one cancellation should succeed");
        assertEquals(1, notFound.get(), "Exactly one should observe BookingNotFound");
    }
}
