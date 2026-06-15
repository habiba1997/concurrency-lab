package com.concurrencylab.booking;

import com.concurrencylab.booking.dto.StatsResponse;
import com.concurrencylab.booking.exceptions.BookingNotFoundException;
import com.concurrencylab.booking.exceptions.InsufficientSeatsException;
import com.concurrencylab.booking.model.Booking;
import com.concurrencylab.booking.model.Concert;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ============================================================================ LAYER 2 — Multithreaded concurrency
 * tests. Each test simulates many threads hitting BookingServiceImpl in parallel to surface concurrency bugs (oversell,
 * lost updates, inconsistent reads). ============================================================================
 * <p>
 * KEY BUILDING BLOCKS USED IN EVERY TEST:
 * <p>
 * 1) ExecutorService pool = Executors.newFixedThreadPool(50);
 * ────────────────────────────────────────────────────────── A *thread pool* with 50 worker threads. We submit() 500
 * tasks to it; the pool reuses its 50 threads to process them. Each worker thread picks up one task, runs it, then
 * comes back for the next.
 * <p>
 * Why a pool instead of `new Thread(...).start()` 500 times? - Creating 500 OS threads is expensive and wasteful. - The
 * pool lets us control the level of parallelism (=50).
 * <p>
 * 2) CountDownLatch startGate = new CountDownLatch(1); ────────────────────────────────────────────────── A
 * CountDownLatch is a *one-way gate* initialized to a number. Threads can call .await() to block until the count
 * reaches 0. Other threads call .countDown() to decrement the count.
 * <p>
 * With count=1, it acts as a "starting gun": - Every worker calls startGate.await()  → they all sit and wait. - The
 * main test thread calls startGate.countDown() → count becomes 0 → all blocked workers wake up SIMULTANEOUSLY.
 * <p>
 * Why do we need a starting gun? - When we submit() 500 tasks, the pool starts running them as it finds free threads —
 * gradually, not all at once. - For concurrency testing we want maximum contention: all threads hitting the critical
 * section at the SAME moment. - The gate forces every thread to wait at the same line, then all charge through
 * together.
 * <p>
 * 3) CountDownLatch endGate = new CountDownLatch(N); ────────────────────────────────────────────── A "finish line"
 * initialized to N (number of tasks). Each worker calls endGate.countDown() in its `finally` block when done. After
 * it's been decremented N times, the count reaches 0. The main thread calls endGate.await() which blocks until count ==
 * 0, i.e. all workers are done.
 * <p>
 * Why not just call pool.shutdown() and pool.awaitTermination()? - You can. But endGate is more explicit about "I'm
 * waiting for N tasks to finish" rather than "I'm waiting for the pool to drain". - It also lets us add a timeout
 * (endGate.await(30, SECONDS)) so a hung test fails fast instead of running forever.
 * <p>
 * 4) AtomicInteger successes = new AtomicInteger(); ───────────────────────────────────────────── The TEST's own
 * counter (not the code under test). 500 threads will be incrementing it concurrently, so we use AtomicInteger to count
 * without losing increments. (We MUST use this in tests so the test itself isn't buggy — otherwise we couldn't trust
 * the numbers we measure.)
 * <p>
 * THE FULL FLOW, IN ORDER:
 * <p>
 * main thread                       worker threads (pool) ────────────                      ─────────────────────
 * create pool of 50 threads for i in 0..499: pool.submit(task)  ─────────► task picked up by free worker worker:
 * startGate.await()  ⏸ waits (all 500 tasks queued, ~50 running) startGate.countDown()  ────────► gate opens, ALL
 * workers unblock worker: service.createBooking(...) worker: successes.incrementAndGet() worker: endGate.countDown()
 * endGate.await(30, SEC) ⏸ wait    (workers finish one by one) (when last worker counts down, gate opens)
 * pool.shutdown() assert successes == 100 ============================================================================
 * <p>
 * Restaurant analogy Executors.newFixedThreadPool(50) │ └── "Hire 50 waiters."  ←── these are real OS threads, created
 * upfront pool.submit(task) │ └── "Add this order to the kitchen ticket queue." Worker (waiter) loop, forever: 1. Walk
 * to the queue 2. If queue empty → wait 3. If queue has an order → grab one, fulfill it, repeat If you submit 500
 * orders to 50 waiters, only 50 can be worked on simultaneously. The other 450 sit in the queue until a waiter is
 * free.
 * <p>
 * Time  | Main thread                  | Worker A              | Worker B              | Queue
 * ──────┼──────────────────────────────┼───────────────────────┼───────────────────────┼───────── t=0   | create pool,
 * gates           |                       |                       | [] t=1   | submit(T1)                   | | | [T1]
 * t=2   | submit(T2)                   |                       | | [T1,T2] t=3   | submit(T3) | | | [T1,T2,T3] | (pool
 * wakes the 2 workers) t=4   | | picks T1              | picks T2 | [T3] t=5   | | startGate.await() ⏸   |
 * startGate.await() ⏸   | [T3] | | (BLOCKED) | (BLOCKED) | [T3] | | | ⚠ T3 just sits in queue. No worker is free to
 * pick it up — both blocked! | t=6   | startGate.countDown()        | ↓ unblocks | ↓ unblocks            | [T3] t=7   |
 * endGate.await(30s) ⏸         | createBooking()       | createBooking()       | [T3] t=8   | | successes++           |
 * successes++           | [T3] t=9   | | endGate.countDown()   | endGate.countDown()   | [T3] | | (endGate: 3→2) |
 * (endGate: 2→1) | t=10  |                              | done → become free | done → become free    | [T3] t=11  | |
 * picks T3              |                       | [] t=12  | | startGate.await()     | | | | (already 0 — passes!) | |
 * t=13  |                              | createBooking()       | | t=14  | | successes++ | | t=15  | |
 * endGate.countDown()   | | | | (endGate: 1→0) |                       | | endGate.await() returns ✅   | |
 * | t=16 | pool.shutdown()              | | | t=17  | assertions
 * <p>
 * NOTE : T3/T51 hit startGate.await() — but the gate is already at 0 — so it passed straight through without blocking.
 * <p>
 * POOL SIZE = max number of tasks that can RUN SIMULTANEOUSLY    │ │  N         = total number of tasks (drives
 * throughput)          │ │                                                                 │ │  The "starting gun" only
 * creates simultaneous start for the     │ │  first POOL_SIZE tasks. Tasks beyond that run in "rolling"      │ │
 * fashion as workers free up.
 * <p>
 * You CAN do newFixedThreadPool(500) and get 500 simultaneous attacks. But: 500 OS threads is expensive — each thread
 * reserves ~512KB of stack space. 500 threads = ~250MB just for stacks.
 */
@Slf4j
@SpringBootTest
class BookingServiceConcurrencyTest {

    @Autowired
    private BookingService service;

    // ========================================================================
    //  C1 — Overbooking
    //  500 threads each try to book 1 seat on a 100-capacity concert.
    //  Expected: exactly 100 succeed, exactly 400 fail with InsufficientSeats.
    // ========================================================================
    @Test
    void overbooking_500ThreadsBook1Seat_exactly100Succeed() throws Exception {
        Concert concert = service.createConcert("Overbook", 100);
        String name = concert.getName();

        int N = 500;
        //  ── "Hire 50 waiters."  ←── these are real OS threads, created upfront
        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch startGate = new CountDownLatch(
                1); // Once the count reaches 0, it stays at 0 forever. The latch is one-way. Any await() called after that point passes through with no wait.
        CountDownLatch endGate = new CountDownLatch(N);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger expectedFailures = new AtomicInteger();

        for (int i = 0; i < N; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    service.createBooking(name, 1);
                    successes.incrementAndGet();

                } catch (InsufficientSeatsException e) {
                    expectedFailures.incrementAndGet();
                } catch (Exception e) {
                    fail(e);
                } finally {
                    endGate.countDown();
                }
            });
        }
        //        NOTE : T3/t51 hit startGate.await() — but the gate is already at 0 — so it passed straight through without blocking.
        // submit(1).... submit(500) are in Queue but only 50 are taken in start and all are blocked till this line
        // ── 4. Fire the starting gun, wait for finish ──────────────────────
        startGate.countDown();

        // "Block until count reaches 0,
        //    OR until 30 seconds have passed —
        //    whichever happens FIRST."
        assertTrue(endGate.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(100, successes.get());
        assertEquals(400, expectedFailures.get());
        StatsResponse stats = service.getStats(name);
        assertEquals(100, stats.booked());
        assertEquals(0, stats.available());
        assertEquals(stats.capacity(), stats.booked() + stats.available());
        assertEquals(100, stats.totalBookings());
        assertEquals(100, service.listBookings(name).size());
    }

    // ========================================================================
    //  C2 — Stats consistency under write storm
    //  100 writers + 100 readers (each reader polls 100 times).
    //  Each read must satisfy: booked + available == capacity.
    // ========================================================================
    @Test
    void statsConsistency_writersAndReaders_neverInconsistent() throws Exception {
        int totalReads = 100;
        int totalWrites = 100;
        Concert concert = service.createConcert("Stats", 100);
        String name = concert.getName();

        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(totalWrites + totalReads);
        AtomicInteger inconsistencies = new AtomicInteger();
        AtomicInteger writerSuccesses = new AtomicInteger();
        AtomicInteger writerFailures = new AtomicInteger();

        // Extract the two task types into named Runnables so we can interleave
        // their submission. Without interleaving, the pool would pull all writers
        // first (FIFO queue), finish them, THEN pull readers — no overlap, no race.
        Runnable writerTask = () -> {
            try {
                startGate.await();
                service.createBooking(name, 1);
                writerSuccesses.incrementAndGet();
            } catch (InsufficientSeatsException e) {
                log.error("Insufficient seats for booking " + name);
                writerFailures.incrementAndGet();
            } catch (Exception e) {
                fail(e);
            } finally {
                endGate.countDown();
            }
        };

        Runnable readerTask = () -> {
            try {
                startGate.await();
                // 100 reads per reader × 100 readers = 10,000 total reads
                for (int j = 0; j < 100; j++) {
                    StatsResponse stats = service.getStats(name);
                    if (stats.booked() + stats.available() != stats.capacity()) {
                        inconsistencies.incrementAndGet();
                    }
                }
            } catch (Exception e) {
                fail(e);
            } finally {
                endGate.countDown();
            }
        };

        // Interleaved submission: queue becomes [W, R, W, R, ...] so the first
        // batch of workers picks up a mix of writers AND readers.
        // Assumes totalWrites == totalReads (both 100 here).
        for (int i = 0; i < totalWrites; i++) {
            pool.submit(writerTask);
            pool.submit(readerTask);
        }

        startGate.countDown();
        assertTrue(endGate.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(0, inconsistencies.get());
        assertEquals(totalWrites, writerSuccesses.get() + writerFailures.get());
        assertEquals(0, writerFailures.get());

        StatsResponse stats = service.getStats(name);
        assertEquals(totalWrites, stats.booked());
        assertEquals(stats.capacity(), stats.booked() + stats.available());
        assertEquals(totalWrites, service.listBookings(name).size());
    }

    // ========================================================================
    //  C3 — Double cancellation race
    //  Two threads cancel the SAME booking ID simultaneously.
    //  Expected: exactly 1 succeeds, exactly 1 throws BookingNotFoundException.
    // ========================================================================
    @Test
    void doubleCancel_twoThreadsCancelSameBooking_exactlyOneSucceeds() throws Exception {
        Concert concert = service.createConcert("DoubleCancel", 10);
        Booking booking = service.createBooking(concert.getName(), 1);

        int cancellationTimes = 2;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(cancellationTimes);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger notFounds = new AtomicInteger();

        for (int i = 0; i < cancellationTimes; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    service.cancelBooking(concert.getName(), booking.getUuid());
                    successes.incrementAndGet();
                } catch (BookingNotFoundException e) {
                    notFounds.incrementAndGet();
                } catch (Exception e) {
                    fail(e);
                } finally {
                    endGate.countDown();
                }
            });
        }
        startGate.countDown();
        assertTrue(endGate.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, successes.get());
        assertEquals(1, notFounds.get());

        StatsResponse stats = service.getStats(concert.getName());
        assertEquals(1, stats.totalCancellations());
        assertEquals(0, stats.booked());
        assertEquals(stats.capacity(), stats.available());
        assertEquals(stats.capacity(), stats.booked() + stats.available());
        assertEquals(0, service.listBookings(concert.getName()).size());
    }

    // ========================================================================
    //  C4 — Mixed book + cancel: final state must be consistent
    //  Pre-populate 500 bookings on a 1000-cap concert.
    //  In parallel: 500 bookers + 500 cancellers.
    //  After: booked + available == 1000, and audit equation holds.
    // ========================================================================
    @Test
    void mixedBookCancel_finalStateIsConsistent() throws Exception {
        int preBookings = 500;
        int bookers = 500;
        int cancellers = 500;

        Concert concert = service.createConcert("Mix", bookers + cancellers);
        String name = concert.getName();

        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(bookers + cancellers);

        //     (Why ConcurrentLinkedQueue? Because canceller threads will poll() from it
        //      concurrently — poll() is atomic. A regular LinkedList would corrupt.)
        // Imagine the queue holds [A, B, C, D]. Thread T1 and T2 both call poll() simultaneously.
        // "A" was returned to both threads → duplicate work (in your C4 test: two cancellers try to cancel the same booking id → one wins, one gets BookingNotFoundException).
        //"B" was never returned to anyone → lost item (a booking that should have been cancelled, isn't).
        ConcurrentLinkedQueue<String> idsToCancel = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < preBookings; i++) {
            Booking b = service.createBooking(name, 1);
            idsToCancel.add(b.getUuid());
        }
        assertEquals(preBookings, service.getStats(name).booked());

        AtomicInteger bookerSuccesses = new AtomicInteger();
        AtomicInteger bookerFailures = new AtomicInteger();
        AtomicInteger cancellerSuccesses = new AtomicInteger();
        AtomicInteger cancellerFailures = new AtomicInteger();

        // Same interleaving fix as C2: without it, all 500 bookers run BEFORE
        // any canceller (FIFO queue), so the test exercises zero overlap.
        Runnable bookerTask = () -> {
            try {
                startGate.await();
                service.createBooking(name, 1);
                bookerSuccesses.incrementAndGet();
            } catch (InsufficientSeatsException e) {
                log.error("Insufficient seats for booking " + name);
                bookerFailures.incrementAndGet();
            } catch (Exception e) {
                fail(e);
            } finally {
                endGate.countDown();
            }
        };

        Runnable cancellerTask = () -> {
            try {
                startGate.await();
                String id = idsToCancel.poll();  // atomically take next id
                if (id == null) {
                    cancellerFailures.incrementAndGet();
                    return;
                }
                service.cancelBooking(name, id);
                cancellerSuccesses.incrementAndGet();
            } catch (BookingNotFoundException e) {
                cancellerFailures.incrementAndGet();
            } catch (Exception e) {
                fail(e);
            } finally {
                endGate.countDown();
            }
        };

        // Interleaved: [B, C, B, C, ...] — assumes bookers == cancellers.
        for (int i = 0; i < bookers; i++) {
            pool.submit(bookerTask);
            pool.submit(cancellerTask);
        }

        startGate.countDown();
        assertTrue(endGate.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        // every booker + every canceller must land in exactly one branch
        assertEquals(bookers, bookerSuccesses.get() + bookerFailures.get());
        assertEquals(cancellers, cancellerSuccesses.get() + cancellerFailures.get());
        assertEquals(0, cancellerFailures.get());

        StatsResponse stats = service.getStats(name);
        assertEquals(stats.capacity(), stats.booked() + stats.available());
        // audit equation: booked = initial + new bookings - cancellations
        int expectedBooked = preBookings + bookerSuccesses.get() - cancellerSuccesses.get();
        assertEquals(expectedBooked, stats.booked());
        assertEquals(stats.booked(), service.listBookings(name).size());
        assertEquals(cancellerSuccesses.get(), stats.totalCancellations());
    }

    // ========================================================================
    //  C5 — List vs counter parity
    //  500 threads each book 1 seat on a 1000-capacity concert.
    //  After: listBookings.size() == stats.booked().
    // ========================================================================
    @Test
    void bookingCount_500ThreadsBookSimultaneously_listAndCounterAgree() throws Exception {
        int bookings = 500;
        int capacity = 1000;

        Concert concert = service.createConcert("PARITY", capacity);
        String name = concert.getName();

        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(bookings);

        AtomicInteger bookingSuccesses = new AtomicInteger();

        Runnable bookerTask = () -> {
            try {
                startGate.await();
                service.createBooking(name, 1);
                bookingSuccesses.incrementAndGet();
            } catch (InsufficientSeatsException e) {
                log.error("Insufficient seats for booking " + name);
            } catch (Exception e) {
                fail(e);
            } finally {
                endGate.countDown();
            }
        };

        for (int i = 0; i < bookings; i++) {
            pool.submit(bookerTask);
        }

        startGate.countDown();
        assertTrue(endGate.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        StatsResponse stats = service.getStats(name);
        assertEquals(bookings, bookingSuccesses.get());
        assertEquals(bookingSuccesses.get(), stats.booked());
        assertEquals(capacity, stats.booked() + stats.available());
        // the whole point of C5: list and counter must agree
        assertEquals(stats.booked(), service.listBookings(name).size());
    }

    // ========================================================================
    //  C6 — Cancel storm idempotency
    //  1000 pre-populated bookings. 1000 threads cancel each unique one.
    //  Run the same cancellation set twice.
    //  1st run: exactly 1000 successes, 0 failures.
    //  2nd run: exactly 0 successes, 1000 BookingNotFoundException.
    // ========================================================================
    @Test
    void cancelStorm_runTwice_secondRunAllFail() throws Exception {
        int cancellations = 1000;
        String name = "StormCancellation";
        Concert concert = service.createConcert(name, cancellations);

        List<String> bookingIds = new ArrayList<>();
        for (int i = 0; i < cancellations; i++) {
            Booking booking = service.createBooking(concert.getName(), 1);
            bookingIds.add(booking.getUuid());
        }

        int[] pass1 = runCancellationPass(cancellations, bookingIds, concert);
        assertEquals(1000, pass1[0]);
        assertEquals(0, pass1[1]);

        int[] pass2 = runCancellationPass(cancellations, bookingIds, concert);
        assertEquals(0, pass2[0]);
        assertEquals(1000, pass2[1]);

        StatsResponse stats = service.getStats(name);
        assertEquals(cancellations, stats.totalCancellations());
        assertEquals(0, stats.booked());
        assertEquals(stats.capacity(), stats.available());
        assertEquals(stats.capacity(), stats.booked() + stats.available());
        assertEquals(0, service.listBookings(name).size());
    }

    private int[] runCancellationPass(int cancellations, List<String> bookingIds, Concert concert)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(cancellations);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger notFounds = new AtomicInteger();

        for (int i = 0; i < cancellations; i++) {
            final String id = bookingIds.get(i);
            pool.submit(() -> {
                try {
                    startGate.await();
                    service.cancelBooking(concert.getName(), id);
                    successes.incrementAndGet();
                } catch (BookingNotFoundException e) {
                    notFounds.incrementAndGet();
                } catch (Exception e) {
                    fail(e);
                } finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(endGate.await(10, TimeUnit.SECONDS));
        pool.shutdown();
        return new int[]{successes.get(), notFounds.get()};
    }

    // ========================================================================
//  C7 — Concurrent concert creation with the same name
//  100 threads simultaneously call createConcert("Same", 100).
//  Decide your contract: idempotent? exclusive? then assert accordingly.
// ========================================================================
    @Test
    void concurrentConcertCreation_sameName_doesNotCorrupt() throws Exception {
        int times = 100;
        String concertName = "concert";

        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(times);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger lessCapacityFailure = new AtomicInteger();

        for (int i = 0; i < times; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    service.createConcert(concertName, times);
                    successes.incrementAndGet();
                } catch (IllegalArgumentException e) {
                    lessCapacityFailure.incrementAndGet();
                } catch (Exception e) {
                    fail(e);
                } finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(endGate.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(times, successes.get() + lessCapacityFailure.get());
        assertEquals(times, successes.get());
        assertEquals(0, lessCapacityFailure.get());

        StatsResponse stats = service.getStats(concertName);
        assertNotNull(stats);
        assertEquals(times, stats.capacity());
        assertEquals(0, stats.booked());
        assertEquals(times, stats.available());
        assertEquals(stats.capacity(), stats.booked() + stats.available());
        assertEquals(0, service.listBookings(concertName).size());
    }
}
