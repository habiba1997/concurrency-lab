# Concurrency Lab — Project 1: Ticket Booking Service

A deliberately-broken-by-default Spring Boot skeleton. Your job is to make the
five endpoints thread-safe under heavy concurrent load, **without a database**,
and then prove it with JMeter.

---

## What you need to implement

Open these files, in this order:

1. `src/main/java/com/concurrencylab/booking/model/Concert.java` — design the state.
2. `src/main/java/com/concurrencylab/booking/BookingServiceImpl.java` — implement five methods.

Everything else (controller, exceptions, HTTP mapping, DTOs, JMeter plan) is done.

### The five service methods

| Method | What it does | Throws |
|---|---|---|
| `createConcert(name, capacity)` | Returns new concert ID | — |
| `createBooking(concertId, seats)` | Returns new booking ID | `ConcertNotFoundException`, `InsufficientSeatsException` |
| `cancelBooking(concertId, bookingId)` | Cancels a booking | `ConcertNotFoundException`, `BookingNotFoundException` |
| `getStats(concertId)` | Returns 5 numbers — must be a single coherent snapshot | `ConcertNotFoundException` |
| `listBookings(concertId)` | Returns all active bookings | `ConcertNotFoundException` |

### Invariants that MUST hold under any load

- **I1**: `booked + available == capacity` at all observable moments
- **I2**: `booked` never exceeds `capacity` (no oversell)
- **I3**: Every successful `createBooking` returns an ID visible in `listBookings`
- **I4**: Concurrent double-cancellation produces exactly one success and one 404
- **I5**: `getStats` returns numbers that agree with each other
- **I6**: 1000 RPS for 60 seconds produces zero HTTP 500 errors

---

## Build & run

```bash
cd /Users/habiba.ahmed/Desktop/concurrency-lab
mvn clean package
mvn spring-boot:run
```

The app boots at `http://localhost:8080`. While running, in another terminal:

```bash
# 1. Create a concert
curl -sX POST http://localhost:8080/concerts \
  -H 'Content-Type: application/json' \
  -d '{"name":"Beethoven Night","capacity":100}' | jq .
# → {"concertId":"<UUID>","name":"Beethoven Night","capacity":100}

# 2. Book 2 seats
curl -sX POST http://localhost:8080/concerts/<UUID>/bookings \
  -H 'Content-Type: application/json' \
  -d '{"seats":2}' | jq .

# 3. Stats
curl -s http://localhost:8080/concerts/<UUID>/stats | jq .

# 4. Cancel
curl -sX DELETE http://localhost:8080/concerts/<UUID>/bookings/<bookingId> -i

# 5. List bookings
curl -s http://localhost:8080/concerts/<UUID>/bookings | jq .
```

Until you implement the methods, each call returns HTTP 500 with
`UnsupportedOperationException` — that's expected.

---

## Running the unit-level concurrency tests

```bash
mvn -Dtest=BookingServiceConcurrencyTest test
```

Tests are `@Disabled` by default. Remove the annotation on each test once you've
implemented the corresponding method. Run the suite **at least 20 times** before
trusting a "pass":

```bash
for i in {1..20}; do mvn -q -Dtest=BookingServiceConcurrencyTest test || { echo "FAILED on run $i"; break; }; done
```

---

## Load test with JMeter

This is where it gets real. Implementations that pass single-threaded tests can
still fail catastrophically here.

You build the JMeter test plans yourself. See `load-tests/README.md` for the
full set of scenarios (A–E), each with:
- exact configuration (threads, ramp-up, samplers, listeners)
- expected pass/fail signals
- which acceptance invariant each scenario stresses

Quickstart: open JMeter (`jmeter` in terminal), build the plan for **Scenario A
— Overbooking** following the structure in `load-tests/README.md`, point it at
a freshly-created concert with capacity 100, and run with 500 threads.

---

## How to debug when your implementation breaks

1. **Read the actual response codes.** JMeter's summary tells you exactly how
   many 201s vs 409s vs 500s came back.
2. **Re-read your `createBooking`.** If you wrote something like:
   ```
   if (concert.getAvailable() >= seats) { concert.book(seats); }
   ```
   ask yourself: between the `if` and the `book`, can another thread sneak in?
3. **Take a thread dump** while a test is running:
   ```bash
   curl -s http://localhost:8080/actuator/threaddump | jq '.threads[] | select(.threadState != "WAITING" and .threadState != "TIMED_WAITING") | {name: .threadName, state: .threadState}'
   ```
4. **Run the unit tests in a loop** (see above). They reproduce most bugs
   faster than JMeter.

---

## When to come back to chat

Come back with results when you've either:
- Implemented all five methods AND the JMeter test reports exactly 100 successes
  and 400 failures, **OR**
- Implemented all five methods but you're seeing one of the failure modes above.

Either way I'll walk you through the design review questions and you can
explain what choices you made and why.
