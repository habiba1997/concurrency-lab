# Concurrency Scenarios — Test These (Code + JMeter)

These are the scenarios you must use to surface concurrency bugs.
Each scenario has **two ways** to test it:
- **CODE** — a multithreaded JUnit test using `ExecutorService` (fast, deterministic-ish, easy to debug in IDE)
- **JMETER** — an HTTP load test that hits the running app (real network, real Tomcat thread pool)

Run BOTH. They catch different bugs:
- CODE tests bypass HTTP, so they catch service-level concurrency issues directly.
- JMETER tests include the full HTTP request handling, JSON serialization,
  and Tomcat's request thread pool — sometimes bugs only appear there.

For CODE tests, create a file:
`src/test/java/com/concurrencylab/booking/BookingServiceConcurrencyTest.java`

Each test you write should follow this template:

```java
@Test
void scenarioName() throws Exception {
    // 1. Arrange: create the service, seed concerts
    BookingService service = new BookingServiceImpl();
    Concert concert = service.createConcert("X", 100);

    // 2. Build the gates and counters
    int N = 500;
    ExecutorService pool = Executors.newFixedThreadPool(50);
    CountDownLatch startGate = new CountDownLatch(1);
    CountDownLatch endGate = new CountDownLatch(N);
    AtomicInteger successes = new AtomicInteger();
    AtomicInteger expectedFailures = new AtomicInteger();

    // 3. Submit N tasks
    for (int i = 0; i < N; i++) {
        pool.submit(() -> {
            try {
                startGate.await();        // all threads released at once
                service.createBooking("X", 1);
                successes.incrementAndGet();
            } catch (InsufficientSeatsException e) {
                expectedFailures.incrementAndGet();
            } catch (Exception e) {
                fail("Unexpected: " + e);
            } finally {
                endGate.countDown();
            }
        });
    }

    // 4. Release the gates, wait for completion
    startGate.countDown();
    assertTrue(endGate.await(30, TimeUnit.SECONDS));
    pool.shutdown();

    // 5. Assert invariants
    assertEquals(100, successes.get());
    assertEquals(400, expectedFailures.get());
}
```



Run a CODE test 20 times in a row to catch non-determinism:
```bash
for i in {1..20}; do mvn -q -Dtest=BookingServiceConcurrencyTest#scenarioName test \
   || { echo "FAILED on run $i"; break; }; done
```

---

## Scenario C1 — Overbooking

**Invariant tested**: I2 (no oversell)

### CODE
- Arrange: `createConcert("Overbook", 100)`
- Act: 500 threads each call `service.createBooking("Overbook", 1)`
- Assert:
  - `successes == 100`
  - `failures (InsufficientSeatsException) == 400`
  - `service.getStats("Overbook").booked() == 100`
  - `service.listBookings("Overbook").size() == 100`

### JMETER
- Start app, create a concert capacity=100, copy its name
- Thread Group: 500 threads, ramp-up 1s, loop 1
- Sampler: POST `/concerts/{name}/bookings` body `{"seats":1}`
- Expected response codes:
  - `201` count: **exactly 100**
  - `409` count: **exactly 400**
  - `500` count: **0**
- After: `curl /stats` → `booked=100, available=0`

**Failure modes**:
- More than 100 `201`s → oversell (check-then-act bug in `reserve()`)
- Any `500`s → unhandled exception (NPE, CME from HashMap, etc.)
- `booked + available != 100` → counter and field state drift

---

## Scenario C2 — Stats consistency under write storm

**Invariant tested**: I1 (`booked + available == capacity`), I5 (coherent snapshot)

### CODE
- Arrange: `createConcert("Stats", 1000)`
- Act:
  - 100 writer threads, each calls `createBooking("Stats", 1)` once
  - 100 reader threads, each calls `getStats("Stats")` 100 times
  - In each reader iteration, check: `stats.booked() + stats.available() == 1000`
  - Increment an `AtomicInteger inconsistencies` on each violation
- Assert:
  - `inconsistencies.get() == 0`
  - Final `booked == 100`

### JMETER
- Two **parallel** Thread Groups:
  - **Writers**: 100 threads, ramp-up 1s, loop 1 — POST `/bookings` `{"seats":1}`
  - **Readers**: 100 threads, ramp-up 0s, loop 100 — GET `/stats`
- Add a JSR223 PostProcessor on the GET request (Groovy):
  ```groovy
  import groovy.json.JsonSlurper
  def json = new JsonSlurper().parseText(prev.getResponseDataAsString())
  if (json.booked + json.available != json.capacity) {
      prev.setSuccessful(false)
      prev.setResponseMessage("INVARIANT I1 VIOLATED: " + json)
  }
  ```
- Expected: **zero** failed GET requests
- Even one failure means I5 is broken

**Failure modes**:
- Reader sees `booked + available != capacity` → fields read independently without coherent snapshot
- 500 errors on reads while writers run → unsafe iteration of a non-concurrent collection

---

## Scenario C3 — Double cancellation race

**Invariant tested**: I4 (exactly one cancellation wins)

### CODE
- Arrange: `createConcert("X", 10)`, `createBooking("X", 1)` → capture `bookingId`
- Act: 2 threads each call `cancelBooking("X", bookingId)` (use a `CountDownLatch` for simultaneous start)
- Catch both `BookingNotFoundException` and successful return
- Assert:
  - `successes == 1`
  - `BookingNotFoundException count == 1`
  - Final `service.getStats("X").totalCancellations() == 1` (NOT 2)

### JMETER
- Pre-populate 100 bookings (1 seat each)
- Save the IDs to a file:
  ```bash
  curl -s http://localhost:8080/concerts/X/bookings | jq -r '.[].id' > /tmp/bids.csv
  ```
- Thread Group: 200 threads (2 per booking), loop 1
- CSV Data Set Config: file `/tmp/bids.csv`, variable `BID`, Recycle: **True**, Sharing: **All threads**
- Sampler: DELETE `/concerts/X/bookings/${BID}`
- Expected:
  - **Exactly 100** of `204`
  - **Exactly 100** of `404`
  - **Zero** `500`s
- After: `stats.totalCancellations == 100` (not 200)

**Failure modes**:
- More than 100 `204`s → double-cancellation not detected (counter decremented twice)
- `canceledSeats > totalBookings` → counters went haywire

---

## Scenario C4 — Mixed book + cancel final consistency

**Invariant tested**: I1 (booked+available=capacity), audit equation

### CODE
- Arrange: `createConcert("Mix", 1000)`, pre-populate 500 bookings, save IDs to a `ConcurrentLinkedQueue<Integer> idsToCancel`
- Act in parallel:
  - 500 threads each call `createBooking("Mix", 1)` (capture successes)
  - 500 threads each `poll()` an ID and call `cancelBooking("Mix", id)` (capture successes)
- Assert (after both finish):
  - `booked + available == 1000` (capacity)
  - `booked == 500 + bookSuccesses - cancelSuccesses` (audit equation)
  - `listBookings.size() == booked`

### JMETER
- Pre-populate 500 bookings, save IDs
- Two parallel Thread Groups:
  - **Bookers**: 500 threads, ramp-up 1s, loop 1 — POST `/bookings` `{"seats":1}`
  - **Cancellers**: 500 threads with CSV data set of IDs — DELETE `/bookings/${BID}`
- After both finish: `stats.booked + stats.available == 1000`
- Also: `listBookings.length == stats.booked`

**Failure modes**:
- Stats sum drifts from capacity → counters/collection out of sync
- 500 errors during mixed load → likely CME from concurrent HashMap iteration

---

## Scenario C5 — List vs counter parity

**Invariant tested**: I3 (every successful booking is visible in list)

### CODE
- Arrange: `createConcert("Parity", 1000)`
- Act: 500 threads each `createBooking("Parity", 1)`
- Assert:
  - `service.listBookings("Parity").size() == 500`
  - `service.getStats("Parity").booked() == 500`
  - `listBookings.size() == stats.booked()` (the harder invariant)

### JMETER
- Create concert capacity=1000
- 500 threads, ramp-up 1s, loop 1 — POST `/bookings` `{"seats":1}`
- Expected: all 500 succeed (`201`)
- After:
  ```bash
  STATS_BOOKED=$(curl -s /stats | jq .booked)
  LIST_COUNT=$(curl -s /bookings | jq 'length')
  test "$STATS_BOOKED" -eq 500
  test "$LIST_COUNT" -eq 500
  test "$STATS_BOOKED" -eq "$LIST_COUNT"
  ```

**Failure modes**:
- `listBookings.size != stats.booked` → counter and collection updated at different
  atomicity boundaries
- `listBookings.size < 500` while all POSTs returned 201 → silent loss (HashMap
  corruption or overwritten entries due to colliding IDs)

---

## Scenario C6 — Cancel storm idempotency

**Invariant tested**: I4 across larger scale

### CODE
- Arrange: pre-populate 1000 bookings on a 1000-capacity concert
- Act: 1000 threads each cancel one (unique) booking
- Assert: `successes == 1000`, `failures == 0`, final `booked == 0`
- Then re-run the same 1000 cancellations → assert all 1000 return `BookingNotFoundException`

### JMETER
- Same setup; same threads
- Run twice in a row:
  - 1st run: **exactly 1000** of `204`
  - 2nd run: **exactly 1000** of `404`

**Failure modes**:
- 2nd run has any `204` → double-cancellation succeeded
- 1st run has any `404` → bookings disappeared from list before cancellation reached them

---

## Scenario C7 — Concurrent concert creation with same name

**Invariant tested**: clean concert creation under contention

### CODE
- Act: 100 threads simultaneously call `createConcert("Same", 100)`
- Assert: Decide your contract first:
  - **Option A**: every call returns the SAME concert (idempotent). Then assert `service.getStats("Same").capacity() == 100` and no duplicates.
  - **Option B**: every call CREATES a new concert. Then there's a uniqueness problem (your current impl uses name as key, so this can't happen).
  - **Option C**: only one succeeds, the rest get `ConcertAlreadyExistsException`. (You'd need to add this exception.)

Pick a contract and write the assertion to match.

### JMETER
- Thread Group: 100 threads, ramp-up 1s, loop 1
- POST `/concerts` body `{"name":"Same","capacity":100}`
- After: `curl /concerts/Same/stats` returns a sane response (not 500)

**Failure modes**:
- 500 errors → HashMap corruption from concurrent `put` (Java 7 infinite loop, Java 8 entry overwrites)
- Stats returns `null` fields → object visible in map before fully constructed

---

## Recap — Coverage matrix

| Scenario | Invariant(s) | Best for surfacing |
|---|---|---|
| C1 Overbooking | I2 | Check-then-act in `reserve()` |
| C2 Stats consistency | I1, I5 | Non-atomic multi-field reads |
| C3 Double cancel | I4 | Check-then-act in cancel |
| C4 Mixed book+cancel | I1, audit | Counter/collection drift |
| C5 List vs counter | I3 | Atomicity boundary mismatch |
| C6 Cancel storm idempotency | I4 | Same as C3 at scale |
| C7 Concert creation race | (your choice) | HashMap put corruption |

Start with **C1** — it's the simplest and hits the most fundamental bug. Build out from there.

---

## When a test fails — what to do

1. Don't immediately fix.
2. Re-read your code and **think first**: which two threads, doing what, in what order, could produce the observed bad state? Walk through it line by line.
3. Write down your hypothesis.
4. Come back to chat with: the failing scenario, the actual numbers/errors observed, your code, and your hypothesis.
5. I'll confirm or push back with probing questions.
6. THEN you fix.

This is the loop that builds real intuition. Skipping straight to fixes (Googling
"thread-safe counter Java") teaches you nothing.
