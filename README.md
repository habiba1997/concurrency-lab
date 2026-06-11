# Concurrency Lab — Project 1: Ticket Booking Service

A Spring Boot service for booking concert seats. **No database, no UI** — pure
in-memory backend so you can experience real concurrency bugs and learn the JVM
primitives that fix them.

---

## Current API (matches the implemented code)

| Method | Path | Body | Success | Errors |
|---|---|---|---|---|
| POST | `/concerts` | `{name, capacity}` | `201` returns full `Concert` object | `400` validation |
| POST | `/concerts/{name}/bookings` | `{seats}` | `201` returns full `Booking` object | `404` no concert, `409` not enough seats |
| DELETE | `/concerts/{name}/bookings/{bookingId}` | — | `204` | `404` no concert or no booking |
| GET | `/concerts/{name}/stats` | — | `200 {capacity, reservedSeats, availableSeats, totalBookings, canceledSeats}` | `404` |
| GET | `/concerts/{name}/bookings` | — | `200 [Booking...]` | `404` |

**Note**: lookup key is the **concert name** (not a generated ID). Path variable
is still named `concertName` in the controller — that's misleading and worth
renaming.

### Stats interpretation
- `reservedSeats` = currently booked seats
- `availableSeats` = `capacity - reservedSeats`
- `totalBookings` = `reservedSeats + canceledSeats` (sum of all seats ever booked)
- `canceledSeats` = sum of seats cancelled to date

---

## Invariants that MUST hold under any load

- **I1**: `reservedSeats + availableSeats == capacity` at all observable moments
- **I2**: `reservedSeats` never exceeds `capacity` (no oversell)
- **I3**: Every successful booking has an ID visible in `listBookings`
- **I4**: Concurrent double-cancellation produces exactly one success and one 404
- **I5**: `getStats` returns numbers that agree with each other (coherent snapshot)
- **I6**: 1000 RPS for 60 seconds produces zero HTTP 500 errors

---

## Build & run

```bash
cd /Users/habiba.ahmed/Desktop/concurrency-lab
mvn clean package
mvn spring-boot:run
```

The app boots at `http://localhost:8080`. In another terminal:

```bash
# 1. Create a concert (the NAME is the key)
curl -sX POST http://localhost:8080/concerts \
  -H 'Content-Type: application/json' \
  -d '{"name":"BeethovenNight","capacity":100}' | jq .

# 2. Book 2 seats
curl -sX POST http://localhost:8080/concerts/BeethovenNight/bookings \
  -H 'Content-Type: application/json' \
  -d '{"seats":2}' | jq .
# → response includes the booking "id" (used to cancel)

# 3. Stats
curl -s http://localhost:8080/concerts/BeethovenNight/stats | jq .

# 4. Cancel
curl -sX DELETE http://localhost:8080/concerts/BeethovenNight/bookings/<bookingId> -i

# 5. List bookings
curl -s http://localhost:8080/concerts/BeethovenNight/bookings | jq .
```

---

## Testing strategy (two layers)

### Layer 1 — HTTP integration tests (single-threaded)
Validate every endpoint's happy path and error responses. Use MockMvc.
Fast, deterministic. **Run these first** — they catch basic bugs before
concurrency comes into play.

File: `src/test/java/com/concurrencylab/booking/BookingControllerTest.java`

### Layer 2 — Concurrency tests (multi-threaded)
Hammer the service directly with an `ExecutorService` and assert invariants.
Non-deterministic — run each test multiple times.

File: `src/test/java/com/concurrencylab/booking/BookingServiceConcurrencyTest.java`

Run both:
```bash
mvn test
```

Run just one class:
```bash
mvn -Dtest=BookingControllerTest test
mvn -Dtest=BookingServiceConcurrencyTest test
```

Run a concurrency test 20 times to catch flaky bugs:
```bash
for i in {1..20}; do mvn -q -Dtest=BookingServiceConcurrencyTest test || { echo "FAILED on run $i"; break; }; done
```

---

## Load test with JMeter

Once unit + concurrency tests pass, the real proof is JMeter at 500 threads.

See:
- `load-tests/JMETER_WALKTHROUGH.md` — step-by-step click guide for Scenario A
- `load-tests/README.md` — descriptions of Scenarios A through E

---

## How to debug when something breaks

1. **Read the response codes.** Test reports and JMeter both tell you exactly
   how many of each code came back.
2. **Re-read `createBooking` and `reserve()`**. If you wrote something like:
   ```
   if (available >= seats) { reservedSeats += seats; }
   ```
   ask yourself: between the `if` and the `+=`, can another thread sneak in?
3. **Thread dump while load is running**:
   ```bash
   curl -s http://localhost:8080/actuator/threaddump | jq '.threads[] | {name: .threadName, state: .threadState}'
   ```
4. **Run concurrency tests in a loop** — they reproduce most bugs faster than
   JMeter.

---

## When to come back to chat

- After basic bugs fixed (the non-concurrency ones flagged in chat) and
  HTTP layer tests pass.
- After concurrency tests reveal failures — paste the output and your code.
- After JMeter Scenario A — paste the response code counts.

Don't try to fix concurrency bugs by guessing. Surface them, then we'll diagnose
together.
