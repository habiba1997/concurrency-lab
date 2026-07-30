# Concurrency Lab — Ticket Booking Service

A small Spring Boot service for booking concert seats, built as a hands-on lab for
Java concurrency. No database and no UI — all state lives in memory so that real
race conditions (oversell, lost updates, torn reads) can be reproduced and fixed
with plain JVM primitives.

The point of the repo is not the booking domain. It's the test suite: a set of
multithreaded JUnit scenarios plus JMeter load plans that assert invariants which
only break under contention.

## Stack

- Java 17
- Spring Boot 3.3.4 (web, validation, actuator)
- Lombok
- JUnit 5 + MockMvc
- JMeter (load tests, plans built by hand)

## API

| Method | Path | Body | Success | Errors |
|---|---|---|---|---|
| POST | `/concerts` | `{name, capacity}` | `201` + `Concert` | `400` validation |
| POST | `/concerts/{name}/bookings` | `{seats}` | `201` + `Booking` | `404` no concert, `409` not enough seats |
| DELETE | `/concerts/{name}/bookings/{bookingId}` | — | `204` | `404` no concert or booking |
| GET | `/concerts/{name}/stats` | — | `200` + `StatsResponse` | `404` |
| GET | `/concerts/{name}/bookings` | — | `200` + `[Booking]` | `404` |

The lookup key is the concert **name**, not a generated id. Booking ids are UUID
strings.

`POST /concerts` on an existing name is idempotent-ish: it raises the capacity to
the new value, and rejects with `400` if the new capacity is lower than the
current one.

### Stats payload

```json
{ "capacity": 100, "booked": 40, "available": 60, "totalBookings": 45, "totalCancellations": 5 }
```

- `booked` — seats currently held
- `available` — `capacity - booked`
- `totalBookings` — `booked + totalCancellations`, i.e. every seat ever booked
- `totalCancellations` — seats released to date

## Invariants under test

| Id | Invariant |
|---|---|
| I1 | `booked + available == capacity` at every observable moment |
| I2 | `booked` never exceeds `capacity` (no oversell) |
| I3 | Every successful booking is visible in `listBookings` |
| I4 | Concurrent double-cancellation yields exactly one success and one 404 |
| I5 | `getStats` returns a coherent snapshot (fields agree with each other) |
| I6 | Sustained load produces zero HTTP 500s |

## How thread safety is implemented

- `Concert.reservedSeats` and `canceledSeats` are `AtomicInteger`s.
- `Concert.reserve` uses a CAS retry loop, so the capacity check and the seat
  deduction are one atomic step — this is what prevents oversell (I2).
- `Concert.bookings` is a `ConcurrentHashMap`; cancellation relies on
  `remove()` returning the previous value to decide the single winner (I4).
- Booking ids are UUIDs rather than an incrementing counter, so two bookings
  created microseconds apart on different threads can't collide and silently
  overwrite each other.

Known gap kept on purpose: `BookingServiceImpl.concerts` is still a plain
`HashMap`. Creating concerts concurrently is not safe yet, and it is one of the
open exercises (scenario C7).

## Build and run

```bash
mvn clean package
mvn spring-boot:run
```

The app listens on `http://localhost:8080`. Tomcat is configured for 200 max
threads and the actuator `threaddump` endpoint is exposed, which is useful while
load testing.

```bash
# create a concert
curl -sX POST http://localhost:8080/concerts \
  -H 'Content-Type: application/json' \
  -d '{"name":"BeethovenNight","capacity":100}' | jq .

# book 2 seats — response contains the booking id
curl -sX POST http://localhost:8080/concerts/BeethovenNight/bookings \
  -H 'Content-Type: application/json' \
  -d '{"seats":2}' | jq .

curl -s http://localhost:8080/concerts/BeethovenNight/stats | jq .
curl -s http://localhost:8080/concerts/BeethovenNight/bookings | jq .
curl -sX DELETE http://localhost:8080/concerts/BeethovenNight/bookings/<bookingId> -i
```

## Tests

Two layers.

**Layer 1 — HTTP integration tests** (`BookingControllerTest`). Single-threaded
MockMvc coverage of every endpoint's happy path and error codes. Fast and
deterministic; run these first so basic bugs don't get blamed on concurrency.

**Layer 2 — Concurrency tests** (`BookingServiceConcurrencyTest`). Each test
drives the service with an `ExecutorService`, releases all threads at once with a
`CountDownLatch` starting gate, waits on a finish-line latch, and then asserts the
invariants. Results are non-deterministic by nature, so run them repeatedly.

```bash
mvn test
mvn -Dtest=BookingControllerTest test
mvn -Dtest=BookingServiceConcurrencyTest test

# hunt for flakiness
for i in {1..20}; do mvn -q -Dtest=BookingServiceConcurrencyTest test \
  || { echo "FAILED on run $i"; break; }; done
```

### Concurrency scenarios covered

| Scenario | What it does | Invariants |
|---|---|---|
| C1 Overbooking | 500 threads book 1 seat on a 100-seat concert | I2 |
| C2 Stats consistency | 100 writers interleaved with 100 readers × 100 reads | I1, I5 |
| C3 Double cancel | 2 threads cancel the same booking | I4 |
| C4 Mixed book + cancel | 500 bookers vs 500 cancellers, audit equation | I1 |
| C5 List vs counter parity | 500 bookings, list size must equal `booked` | I3 |
| C6 Cancel storm | 1000 unique cancels twice; 2nd pass must be all 404 | I4 |
| C7 Concert creation race | 100 threads create the same concert name | — |

Full descriptions, expected counts, and failure-mode interpretation live in
[`CONCURRENCY_SCENARIOS.md`](CONCURRENCY_SCENARIOS.md).

## Load testing

Unit and concurrency tests bypass HTTP. JMeter exercises the full path including
Tomcat's request thread pool, which surfaces a different class of bug.

- [`load-tests/README.md`](load-tests/README.md) — scenarios A through E with
  expected response-code distributions
- [`load-tests/JMETER_WALKTHROUGH.md`](load-tests/JMETER_WALKTHROUGH.md) —
  step-by-step build of scenario A

Build plans in the GUI, run them headless for honest numbers:

```bash
jmeter -n -t myplan.jmx -l results.jtl -e -o report/
awk -F',' 'NR>1 {print $4}' results.jtl | sort | uniq -c
```

## Debugging a failure

1. Read the response-code counts first; both the test output and the JMeter
   report tell you exactly how many of each code came back.
2. Re-read the critical section. If it looks like
   `if (available >= seats) { reservedSeats += seats; }`, ask what another thread
   can do between the check and the update.
3. Dump threads while load is running:
   ```bash
   curl -s http://localhost:8080/actuator/threaddump \
     | jq '.threads[] | {name: .threadName, state: .threadState}'
   ```
4. Form a hypothesis about which two threads interleaved and how, before
   changing any code.

## Layout

```
src/main/java/com/concurrencylab/
├── ConcurrencyLabApplication.java
└── booking/
    ├── BookingController.java
    ├── BookingService.java / BookingServiceImpl.java
    ├── model/       Concert (atomics + CAS reserve), Booking
    ├── dto/         CreateConcertRequest, CreateBookingRequest, StatsResponse
    └── exceptions/  domain exceptions + GlobalExceptionHandler
src/test/java/com/concurrencylab/booking/
├── BookingControllerTest.java             HTTP layer
└── BookingServiceConcurrencyTest.java     C1–C7
load-tests/                                JMeter scenario docs
```
