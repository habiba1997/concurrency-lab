package com.concurrencylab.booking;

import com.concurrencylab.booking.exceptions.InsufficientSeatsException;
import com.concurrencylab.booking.model.Concert;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ============================================================================
 *  LAYER 2 — Multithreaded concurrency tests.
 *  Each test simulates many threads hitting BookingServiceImpl in parallel
 *  to surface concurrency bugs (oversell, lost updates, inconsistent reads).
 * ============================================================================
 *
 *  KEY BUILDING BLOCKS USED IN EVERY TEST:
 *
 *  1) ExecutorService pool = Executors.newFixedThreadPool(50);
 *     ──────────────────────────────────────────────────────────
 *     A *thread pool* with 50 worker threads. We submit() 500 tasks to it;
 *     the pool reuses its 50 threads to process them. Each worker thread picks
 *     up one task, runs it, then comes back for the next.
 *
 *     Why a pool instead of `new Thread(...).start()` 500 times?
 *       - Creating 500 OS threads is expensive and wasteful.
 *       - The pool lets us control the level of parallelism (=50).
 *
 *  2) CountDownLatch startGate = new CountDownLatch(1);
 *     ──────────────────────────────────────────────────
 *     A CountDownLatch is a *one-way gate* initialized to a number.
 *     Threads can call .await() to block until the count reaches 0.
 *     Other threads call .countDown() to decrement the count.
 *
 *     With count=1, it acts as a "starting gun":
 *       - Every worker calls startGate.await()  → they all sit and wait.
 *       - The main test thread calls startGate.countDown() → count becomes 0
 *         → all blocked workers wake up SIMULTANEOUSLY.
 *
 *     Why do we need a starting gun?
 *       - When we submit() 500 tasks, the pool starts running them as it
 *         finds free threads — gradually, not all at once.
 *       - For concurrency testing we want maximum contention: all threads
 *         hitting the critical section at the SAME moment.
 *       - The gate forces every thread to wait at the same line, then all
 *         charge through together.
 *
 *  3) CountDownLatch endGate = new CountDownLatch(N);
 *     ──────────────────────────────────────────────
 *     A "finish line" initialized to N (number of tasks).
 *     Each worker calls endGate.countDown() in its `finally` block when done.
 *     After it's been decremented N times, the count reaches 0.
 *     The main thread calls endGate.await() which blocks until count == 0,
 *     i.e. all workers are done.
 *
 *     Why not just call pool.shutdown() and pool.awaitTermination()?
 *       - You can. But endGate is more explicit about "I'm waiting for N tasks
 *         to finish" rather than "I'm waiting for the pool to drain".
 *       - It also lets us add a timeout (endGate.await(30, SECONDS)) so a
 *         hung test fails fast instead of running forever.
 *
 *  4) AtomicInteger successes = new AtomicInteger();
 *     ─────────────────────────────────────────────
 *     The TEST's own counter (not the code under test). 500 threads will be
 *     incrementing it concurrently, so we use AtomicInteger to count without
 *     losing increments. (We MUST use this in tests so the test itself isn't
 *     buggy — otherwise we couldn't trust the numbers we measure.)
 *
 *  THE FULL FLOW, IN ORDER:
 *
 *     main thread                       worker threads (pool)
 *     ────────────                      ─────────────────────
 *     create pool of 50 threads
 *     for i in 0..499:
 *         pool.submit(task)  ─────────► task picked up by free worker
 *                                       worker: startGate.await()  ⏸ waits
 *                                       (all 500 tasks queued, ~50 running)
 *     startGate.countDown()  ────────► gate opens, ALL workers unblock
 *                                       worker: service.createBooking(...)
 *                                       worker: successes.incrementAndGet()
 *                                       worker: endGate.countDown()
 *     endGate.await(30, SEC) ⏸ wait    (workers finish one by one)
 *                                      (when last worker counts down, gate opens)
 *     pool.shutdown()
 *     assert successes == 100
 * ============================================================================
 *
 * Restaurant analogy
 * Executors.newFixedThreadPool(50)
 *                  │
 *                  └── "Hire 50 waiters."  ←── these are real OS threads, created upfront
 * pool.submit(task)
 *                  │
 *                  └── "Add this order to the kitchen ticket queue."
 * Worker (waiter) loop, forever:
 *     1. Walk to the queue
 *     2. If queue empty → wait
 *     3. If queue has an order → grab one, fulfill it, repeat
 * If you submit 500 orders to 50 waiters, only 50 can be worked on simultaneously. The other 450 sit in the queue until a waiter is free.
 *
 * Time  | Main thread                  | Worker A              | Worker B              | Queue
 * ──────┼──────────────────────────────┼───────────────────────┼───────────────────────┼─────────
 * t=0   | create pool, gates           |                       |                       | []
 * t=1   | submit(T1)                   |                       |                       | [T1]
 * t=2   | submit(T2)                   |                       |                       | [T1,T2]
 * t=3   | submit(T3)                   |                       |                       | [T1,T2,T3]
 *       | (pool wakes the 2 workers)
 * t=4   |                              | picks T1              | picks T2              | [T3]
 * t=5   |                              | startGate.await() ⏸   | startGate.await() ⏸   | [T3]
 *       |                              | (BLOCKED)             | (BLOCKED)             | [T3]
 *       |                              |
 *       | ⚠ T3 just sits in queue. No worker is free to pick it up — both blocked!
 *       |
 * t=6   | startGate.countDown()        | ↓ unblocks            | ↓ unblocks            | [T3]
 * t=7   | endGate.await(30s) ⏸         | createBooking()       | createBooking()       | [T3]
 * t=8   |                              | successes++           | successes++           | [T3]
 * t=9   |                              | endGate.countDown()   | endGate.countDown()   | [T3]
 *       |                              | (endGate: 3→2)        | (endGate: 2→1)        |
 * t=10  |                              | done → become free    | done → become free    | [T3]
 * t=11  |                              | picks T3              |                       | []
 * t=12  |                              | startGate.await()     |                       |
 *       |                              | (already 0 — passes!) |                       |
 * t=13  |                              | createBooking()       |                       |
 * t=14  |                              | successes++           |                       |
 * t=15  |                              | endGate.countDown()   |                       |
 *       |                              | (endGate: 1→0)        |                       |
 *       | endGate.await() returns ✅   |                       |                       |
 * t=16  | pool.shutdown()              |                       |                       |
 * t=17  | assertions
 *
 *  NOTE : T3/T51 hit startGate.await() — but the gate is already at 0 — so it passed straight through without blocking.
 *
 *   POOL SIZE = max number of tasks that can RUN SIMULTANEOUSLY    │
 * │  N         = total number of tasks (drives throughput)          │
 * │                                                                 │
 * │  The "starting gun" only creates simultaneous start for the     │
 * │  first POOL_SIZE tasks. Tasks beyond that run in "rolling"      │
 * │  fashion as workers free up.
 *
 * You CAN do newFixedThreadPool(500) and get 500 simultaneous attacks. But:
 * 500 OS threads is expensive — each thread reserves ~512KB of stack space. 500 threads = ~250MB just for stacks.
 */
class BookingServiceConcurrencyTest {

    // ========================================================================
    //  C1 — Overbooking
    //  500 threads each try to book 1 seat on a 100-capacity concert.
    //  Expected: exactly 100 succeed, exactly 400 fail with InsufficientSeats.
    // ========================================================================
    @Test
    void overbooking_500ThreadsBook1Seat_exactly100Succeed() throws Exception {
        BookingService service = new BookingServiceImpl();
        Concert concert = service.createConcert("Overbook", 100);
        String name = concert.getName();

        int N = 500;
        //  ── "Hire 50 waiters."  ←── these are real OS threads, created upfront
        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch startGate = new CountDownLatch(1); // Once the count reaches 0, it stays at 0 forever. The latch is one-way. Any await() called after that point passes through with no wait.
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
                    fail("Unexpected exception: " + e);
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
        assertTrue(endGate.await(30, TimeUnit.SECONDS),
                "Test timed out — some workers never finished. Possible deadlock or hang."); //
        pool.shutdown();

        // ── 5. Assert invariants ───────────────────────────────────────────
        assertEquals(100, successes.get(),
                "INVARIANT I2: exactly 100 bookings should succeed for a 100-seat concert");
        assertEquals(400, expectedFailures.get(),
                "INVARIANT I2: exactly 400 bookings should fail with InsufficientSeats");
        assertEquals(100, service.getStats(name).booked(),
                "INVARIANT I1: stats.booked must reflect actual successful bookings");
        assertEquals(100, service.listBookings(name).size(),
                "INVARIANT I3: list size must match number of successful bookings");
    }

    // ========================================================================
    //  C2 — Stats consistency under write storm
    //  100 writers + 100 readers (each reader polls 100 times).
    //  Each read must satisfy: booked + available == capacity.
    // ========================================================================
    @Test
    void statsConsistency_writersAndReaders_neverInconsistent() throws Exception {
        // TODO: implement C2.
        // Hints:
        //   - Use TWO loops to submit tasks: one for writers, one for readers.
        //     The writer task: service.createBooking(name, 1)
        //     The reader task: loop 100 times { read stats; check sum }
        //   - Use a single startGate so writers AND readers all start together.
        //   - endGate count should be (writers + readers).
        //   - AtomicInteger inconsistencies; if booked + available != capacity,
        //     increment it.
        //   - Final assertion: assertEquals(0, inconsistencies.get())
    }

    // ========================================================================
    //  C3 — Double cancellation race
    //  Two threads cancel the SAME booking ID simultaneously.
    //  Expected: exactly 1 succeeds, exactly 1 throws BookingNotFoundException.
    // ========================================================================
    @Test
    void doubleCancel_twoThreadsCancelSameBooking_exactlyOneSucceeds() throws Exception {
        // TODO: implement C3.
        // Hints:
        //   - Arrange: create concert, then service.createBooking(name, 1)
        //     and capture the returned Booking's id.
        //   - N = 2.
        //   - Each task: service.cancelBooking(name, bookingId)
        //   - Count successes vs BookingNotFoundException.
        //   - Final assertion: successes == 1, notFounds == 1.
    }

    // ========================================================================
    //  C4 — Mixed book + cancel: final state must be consistent
    //  Pre-populate 500 bookings on a 1000-cap concert.
    //  In parallel: 500 bookers + 500 cancellers.
    //  After: booked + available == 1000, and audit equation holds.
    // ========================================================================
    @Test
    void mixedBookCancel_finalStateIsConsistent() throws Exception {
        // TODO: implement C4.
        // Hints:
        //   - Use a ConcurrentLinkedQueue<Integer> to hand out booking IDs to
        //     canceller threads (poll() is atomic and thread-safe).
        //   - Submit 500 bookers (each does createBooking(name, 1)) + 500
        //     cancellers (each polls an id and cancels it).
        //   - Track booker successes and canceller successes separately.
        //   - Final assertion:
        //       stats.booked + stats.available == 1000
        //       stats.booked == 500 + bookerSuccesses - cancellerSuccesses
    }

    // ========================================================================
    //  C5 — List vs counter parity
    //  500 threads each book 1 seat on a 1000-capacity concert.
    //  After: listBookings.size() == stats.booked().
    // ========================================================================
    @Test
    void bookingCount_500ThreadsBookSimultaneously_listAndCounterAgree() throws Exception {
        // TODO: implement C5.
        // Hints:
        //   - Same skeleton as C1 but with capacity = 1000 and N = 500.
        //   - Expect all 500 to succeed (no failures).
        //   - Final assertions:
        //       service.listBookings(name).size() == 500
        //       service.getStats(name).booked() == 500
        //       service.listBookings(name).size() == service.getStats(name).booked()
        //   - The third assertion is the hard one — counter and collection
        //     can drift if they're updated at different atomicity boundaries.
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
        // TODO: implement C6.
        // Hints:
        //   - Pre-populate 1000 bookings, capture IDs in a list.
        //   - Run a parallel cancellation pass (1000 threads).
        //   - Run it AGAIN (same IDs).
        //   - Assert pass 1: 1000 successes, 0 not-founds.
        //   - Assert pass 2: 0 successes, 1000 not-founds.
        //   - Assert: service.getStats(name).totalCancellations() == 1000
        //     (NOT 2000 — cancellation must be idempotent).
    }

    // ========================================================================
    //  C7 — Concurrent concert creation with the same name
    //  100 threads simultaneously call createConcert("Same", 100).
    //  Decide your contract: idempotent? exclusive? then assert accordingly.
    // ========================================================================
    @Test
    void concurrentConcertCreation_sameName_doesNotCorrupt() throws Exception {
        // TODO: implement C7.
        // Hints:
        //   - N = 100. Single startGate, all threads call createConcert("Same", 100).
        //   - Catch any RuntimeException (some contracts allow "already exists").
        //   - Final assertion: service.getStats("Same") returns a valid response
        //     (does not 500 or NPE).
        //   - Decide and document: did you intend createConcert to be idempotent,
        //     or to throw on duplicate name? Either is valid — the test asserts
        //     whichever you chose.
    }
}
