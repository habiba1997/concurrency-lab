package com.concurrencylab.booking;

import com.concurrencylab.booking.dto.StatsResponse;
import com.concurrencylab.booking.exceptions.BookingNotFoundException;
import com.concurrencylab.booking.exceptions.InsufficientSeatsException;
import com.concurrencylab.booking.model.Booking;
import com.concurrencylab.booking.model.Concert;
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
 * t=2   | submit(T2)                   |                       | | [T1,T2] t=3   | submit(T3) |                       |
 *                       | [T1,T2,T3] | (pool wakes the 2 workers) t=4   | | picks T1              | picks T2
 *   | [T3] t=5   |                              | startGate.await() ⏸   | startGate.await() ⏸   | [T3] | | (BLOCKED)
 *          | (BLOCKED)             | [T3] | | | ⚠ T3 just sits in queue. No worker is free to pick it up — both
 * blocked! | t=6   | startGate.countDown()        | ↓ unblocks | ↓ unblocks            | [T3] t=7   |
 * endGate.await(30s) ⏸         | createBooking()       | createBooking()       | [T3] t=8   |
 *    | successes++           | successes++           | [T3] t=9   | | endGate.countDown()   | endGate.countDown()   |
 * [T3] | | (endGate: 3→2)        | (endGate: 2→1)        | t=10  |                              | done → become free
 * | done → become free    | [T3] t=11  |                              | picks T3              |                       |
 * [] t=12  | | startGate.await()     |                       | |                              | (already 0 — passes!) |
 * | t=13  |                              | createBooking()       | | t=14  | | successes++           |
 *      | t=15  | | endGate.countDown()   |                       | | | (endGate: 1→0) |                       | |
 * endGate.await() returns ✅   | |                       | t=16  | pool.shutdown()              |
 * | | t=17  | assertions
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
        int totalReads = 100;
        int totalWrites = 100;
        Concert concert = service.createConcert("Stats", 100);
        String name = concert.getName();

        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(totalWrites + totalReads);
        AtomicInteger inconsistencies = new AtomicInteger();

        // Extract the two task types into named Runnables so we can interleave
        // their submission. Without interleaving, the pool would pull all writers
        // first (FIFO queue), finish them, THEN pull readers — no overlap, no race.
        Runnable writerTask = () -> {
            try {
                startGate.await();
                service.createBooking(name, 1);
            } catch (InsufficientSeatsException e) {
                log.error("Insufficient seats for booking " + name);
            } catch (Exception e) {
                fail("Unexpected exception: " + e);
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
                fail("Unexpected exception: " + e);
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
        assertTrue(endGate.await(30, TimeUnit.SECONDS),
                "Test timed out — some workers never finished. Possible deadlock or hang.");
        pool.shutdown();
        assertEquals(0, inconsistencies.get(),
                "INVARIANT I1: booked + available must equal capacity at all times");

        StatsResponse stats = service.getStats(name);
        assertEquals(totalWrites, stats.booked());
        assertEquals(stats.capacity(), stats.booked() + stats.available());
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
                    fail("Unexpected exception: " + e);
                } finally {
                    endGate.countDown();
                }
            });
        }
        startGate.countDown();
        assertTrue(endGate.await(10, TimeUnit.SECONDS), "timed out");
        pool.shutdown();

        assertEquals(1, successes.get(),
                "INVARIANT I4: exactly ONE cancellation should succeed");
        assertEquals(1, notFounds.get(),
                "INVARIANT I4: the loser must get BookingNotFoundException");
        assertEquals(1, service.getStats(concert.getName()).totalCancellations(),
                "totalCancellations must be 1, not 2 — cancellation must not be counted twice");
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
        ConcurrentLinkedQueue<String> idsToCancel = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < preBookings; i++) {
            Booking b = service.createBooking(name, 1);
            idsToCancel.add(b.getUuid());
        }
        assertEquals(500, service.getStats(name).booked(),
                "Precondition: 500 booked, 500 available, 0 cancelled.");

        AtomicInteger bookerSuccesses = new AtomicInteger();
        AtomicInteger cancellerSuccesses = new AtomicInteger();

        // Same interleaving fix as C2: without it, all 500 bookers run BEFORE
        // any canceller (FIFO queue), so the test exercises zero overlap.
        Runnable bookerTask = () -> {
            try {
                startGate.await();
                service.createBooking(name, 1);
                bookerSuccesses.incrementAndGet();
            } catch (InsufficientSeatsException e) {
                log.error("Insufficient seats for booking " + name);
            } catch (Exception e) {
                fail("Unexpected: " + e);
            } finally {
                endGate.countDown();
            }
        };

        Runnable cancellerTask = () -> {
            try {
                startGate.await();
                String id = idsToCancel.poll();  // atomically take next id
                if (id == null) return;
                service.cancelBooking(name, id);
                cancellerSuccesses.incrementAndGet();
            } catch (BookingNotFoundException e) {
                // shouldn't happen — each canceller gets a unique id
            } catch (Exception e) {
                fail("Unexpected: " + e);
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
        assertTrue(endGate.await(30, TimeUnit.SECONDS), "timed out");
        pool.shutdown();

        StatsResponse stats = service.getStats(name);
        assertEquals(stats.capacity(), stats.booked() + stats.available(),
                "INVARIANT I1: booked + available must equal capacity");
        // AUDIT EQUATION (this is the hard one):
        int expectedBooked = 500 + bookerSuccesses.get() - cancellerSuccesses.get();
        assertEquals(expectedBooked, stats.booked(),
                "Audit: booked must equal (initial 500) + (new bookings) - (cancellations)");
        assertEquals(stats.booked(), service.listBookings(name).size(),
                "List size must match counter");
    }

    // ========================================================================
    //  C5 — List vs counter parity
    //  500 threads each book 1 seat on a 1000-capacity concert.
    //  After: listBookings.size() == stats.booked().
    // ========================================================================
    @Test
    void bookingCount_500ThreadsBookSimultaneously_listAndCounterAgree() throws Exception {
        // ── STEP 1: ARRANGE ────────────────────────────────────────────────
        // 1a. Create a fresh BookingService.
        // 1b. Create a concert "Parity" with capacity 1000 (room for all 500 bookings).
        // 1c. Capture name.

        // ── STEP 2: CONSTANTS ──────────────────────────────────────────────
        // 2a. N = 500

        // ── STEP 3: POOL + GATES + COUNTERS ───────────────────────────────
        // 3a. ExecutorService pool of size ~50.
        // 3b. startGate(1), endGate(N).
        // 3c. AtomicInteger successes = new AtomicInteger();

        // ── STEP 4: SUBMIT N BOOKER TASKS ─────────────────────────────────
        // For i in 0..N:
        //   pool.submit(() -> {
        //       try {
        //           startGate.await();
        //           service.createBooking(name, 1);
        //           successes.incrementAndGet();
        //       } catch (Exception e) { fail("Unexpected: " + e); }
        //       finally { endGate.countDown(); }
        //   });

        // ── STEP 5: FIRE GUN + WAIT ───────────────────────────────────────
        // 5a. startGate.countDown();
        // 5b. assertTrue(endGate.await(30, TimeUnit.SECONDS), "timed out");
        // 5c. pool.shutdown();

        // ── STEP 6: ASSERT — THREE LEVELS OF STRICTNESS ───────────────────
        // 6a. assertEquals(500, successes.get(),
        //         "All 500 attempts should have succeeded (capacity is 1000)");
        // 6b. assertEquals(500, service.getStats(name).booked(),
        //         "Counter must equal successful bookings");
        // 6c. assertEquals(500, service.listBookings(name).size(),
        //         "List must contain all 500 bookings");
        // 6d. THE HARD ONE — list and counter must AGREE:
        //     int listSize = service.listBookings(name).size();
        //     int counter  = service.getStats(name).booked();
        //     assertEquals(listSize, counter,
        //         "INVARIANT I3: list size must equal counter — they drift apart " +
        //         "if they're updated at different atomicity boundaries. " +
        //         "Observed list=" + listSize + " counter=" + counter);
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
        // ── STEP 1: ARRANGE ────────────────────────────────────────────────
        // 1a. Create a fresh BookingService.
        // 1b. Create a concert "Storm" with capacity 1000.
        // 1c. Capture name.

        // ── STEP 2: PRE-POPULATE 1000 BOOKINGS, SAVE IDS ──────────────────
        // 2a. List<Integer> bookingIds = new ArrayList<>();
        //     (Plain ArrayList is fine here — single-threaded setup phase.)
        // 2b. For i in 0..1000:
        //         Booking b = service.createBooking(name, 1);
        //         bookingIds.add(b.getId());

        // ── STEP 3: HELPER PATTERN — extract a runCancellationPass() method ─
        // Because you'll do this TWICE, write a small helper inside the test:
        //
        //   private int[] runCancellationPass(BookingService service, String name,
        //                                     List<Integer> ids) throws Exception {
        //       int N = ids.size();
        //       ExecutorService pool = Executors.newFixedThreadPool(100);
        //       CountDownLatch startGate = new CountDownLatch(1);
        //       CountDownLatch endGate   = new CountDownLatch(N);
        //       AtomicInteger successes = new AtomicInteger();
        //       AtomicInteger notFounds = new AtomicInteger();
        //
        //       for (int i = 0; i < N; i++) {
        //           final Integer id = ids.get(i);
        //           pool.submit(() -> {
        //               try {
        //                   startGate.await();
        //                   service.cancelBooking(name, id);
        //                   successes.incrementAndGet();
        //               } catch (BookingNotFoundException e) {
        //                   notFounds.incrementAndGet();
        //               } catch (Exception e) { fail("Unexpected: " + e); }
        //               finally { endGate.countDown(); }
        //           });
        //       }
        //       startGate.countDown();
        //       endGate.await(30, TimeUnit.SECONDS);
        //       pool.shutdown();
        //       return new int[] { successes.get(), notFounds.get() };
        //   }

        // ── STEP 4: PASS 1 ────────────────────────────────────────────────
        // 4a. int[] pass1 = runCancellationPass(service, name, bookingIds);
        // 4b. assertEquals(1000, pass1[0], "Pass 1: all 1000 cancellations should succeed");
        // 4c. assertEquals(0,    pass1[1], "Pass 1: no NotFound exceptions");

        // ── STEP 5: PASS 2 (SAME IDS!) ────────────────────────────────────
        // 5a. int[] pass2 = runCancellationPass(service, name, bookingIds);
        // 5b. assertEquals(0,    pass2[0], "Pass 2: no cancellation should succeed (all already cancelled)");
        // 5c. assertEquals(1000, pass2[1], "Pass 2: all 1000 should be BookingNotFoundException");

        // ── STEP 6: FINAL INVARIANT ───────────────────────────────────────
        // 6a. assertEquals(1000, service.getStats(name).totalCancellations(),
        //         "totalCancellations must be 1000 (NOT 2000) — cancellation is idempotent");
        // 6b. assertEquals(0, service.getStats(name).booked(),
        //         "booked must be 0 after all cancellations");
    }

    // ========================================================================
    //  C7 — Concurrent concert creation with the same name
    //  100 threads simultaneously call createConcert("Same", 100).
    //  Decide your contract: idempotent? exclusive? then assert accordingly.
    // ========================================================================
    @Test
    void concurrentConcertCreation_sameName_doesNotCorrupt() throws Exception {
        // ── STEP 0: DECIDE YOUR CONTRACT FIRST (before writing the test) ──
        // Your current createConcert() has THREE possible legitimate behaviors
        // when called twice with the same name. Pick one and assert it:
        //
        //   Contract A — IDEMPOTENT:
        //       Second call returns the SAME concert that already exists. No error.
        //       Real-world example: PUT semantics.
        //
        //   Contract B — UPDATE-IF-EXISTS:
        //       Your current code does this (it adjusts capacity if greater).
        //       Risky: silent overwrites under concurrency.
        //
        //   Contract C — EXCLUSIVE:
        //       Second caller throws ConcertAlreadyExistsException.
        //       Real-world example: POST with a unique-key constraint.
        //
        // For this test we'll assume Contract A or B — the important property is
        // "no crash, no NPE, no half-built concert visible".

        // ── STEP 1: ARRANGE ────────────────────────────────────────────────
        // 1a. Create a fresh BookingService.
        // 1b. final String name = "Same"; // shared across threads

        // ── STEP 2: CONSTANTS ──────────────────────────────────────────────
        // 2a. N = 100

        // ── STEP 3: POOL + GATES + COUNTERS ───────────────────────────────
        // 3a. ExecutorService pool of size ~50.
        // 3b. startGate(1), endGate(N).
        // 3c. AtomicInteger successes = new AtomicInteger();
        // 3d. AtomicInteger failures  = new AtomicInteger();  // only used for Contract C

        // ── STEP 4: SUBMIT N CREATION TASKS ───────────────────────────────
        // For i in 0..N:
        //   pool.submit(() -> {
        //       try {
        //           startGate.await();
        //           service.createConcert(name, 100);
        //           successes.incrementAndGet();
        //       } catch (RuntimeException e) {
        //           failures.incrementAndGet();  // expected under Contract C
        //       } finally {
        //           endGate.countDown();
        //       }
        //   });

        // ── STEP 5: FIRE GUN + WAIT ───────────────────────────────────────
        // 5a. startGate.countDown();
        // 5b. assertTrue(endGate.await(30, TimeUnit.SECONDS), "timed out");
        // 5c. pool.shutdown();

        // ── STEP 6: ASSERT NO CORRUPTION ──────────────────────────────────
        // 6a. The MOST IMPORTANT assertion: stats must work and return valid numbers.
        //     StatsResponse stats = service.getStats(name);
        //     assertNotNull(stats, "Concert must be in a usable state, not half-built");
        //     assertEquals(100, stats.capacity(), "Capacity must be 100, not null or 0");
        //     assertEquals(0,   stats.booked(),   "Fresh concert, no bookings");
        //     assertEquals(100, stats.available(), "Fresh concert, all available");
        //
        // 6b. Optional contract-specific assertion:
        //     // Contract A (idempotent):    assertEquals(N, successes.get());
        //     // Contract C (exclusive):     assertEquals(1, successes.get());
        //     //                              assertEquals(N - 1, failures.get());
        //
        // 6c. THE BUG THIS TEST CATCHES (when it fails):
        //     - HTTP 500 / NPE → HashMap corruption from concurrent put()
        //     - stats.capacity() == 0 or null → object visible before fully constructed
        //     - Multiple concerts with same name in some internal list → consistency loss
    }
}
