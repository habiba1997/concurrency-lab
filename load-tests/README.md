# JMeter Test Scenarios — Build These Yourself

This file describes WHAT to test. You build the JMeter test plan in the GUI.

Each scenario lists: setup, what to configure, expected result, and what to look for.

---

## Launching JMeter

```bash
jmeter
```

The GUI opens. Always use GUI mode to **build** the test plan. Always use
non-GUI mode (`jmeter -n -t plan.jmx -l results.jtl`) to **run** a real load test
(GUI mode adds overhead that distorts measurements).

---

## Scenario A — Overbooking

**The canonical test. Start here.**

### Setup (in your shell, before running JMeter)
```bash
# Start the app
cd /Users/habiba.ahmed/Desktop/concurrency-lab
mvn spring-boot:run

# In another terminal, create a fresh concert
curl -sX POST http://localhost:8080/concerts \
  -H 'Content-Type: application/json' \
  -d '{"name":"Overbooking Test","capacity":100}'
# Copy the concertName from the response.
```

### JMeter test plan structure
```
Test Plan
└── Thread Group
    ├── Number of Threads:   500
    ├── Ramp-up Period:      1 second   (all 500 start within 1s)
    ├── Loop Count:          1
    ├── HTTP Header Manager
    │   └── Content-Type: application/json
    ├── HTTP Request Sampler
    │   ├── Server Name:     localhost
    │   ├── Port:            8080
    │   ├── Method:          POST
    │   ├── Path:            /concerts/<YOUR_CONCERT_ID>/bookings
    │   ├── Body Data:       {"seats": 1}
    └── Listeners
        ├── Aggregate Report      (overall stats)
        ├── View Results in Table (per-request log of response codes)
        └── Summary Report
```

### Expected result
- **HTTP 201** count: exactly **100**
- **HTTP 409** count: exactly **400**
- **HTTP 500** count: **0**

Then verify the stats:
```bash
curl -s http://localhost:8080/concerts/<YOUR_CONCERT_ID>/stats
```
- `booked` must be `100`
- `available` must be `0`
- `booked + available` must equal `100` (capacity)

### Common failure signals
| What you see | What it means |
|---|---|
| `201` count > 100 | OVERSELL — invariant I2 violated. Your check-and-update isn't atomic. |
| `500` errors | Unhandled exception — likely `ConcurrentModificationException` or NPE from an unsafe collection. |
| `201` count < 100 | Lost bookings — writes are clobbering each other. |
| `booked + available != 100` | Counters drifted apart from the bookings collection. |

---

## Scenario B — Mixed booking + cancel (consistency under churn)

### Setup
Create a fresh concert with capacity = 1000. Pre-populate 500 bookings via a
small `for` loop:
```bash
CID=<concertName>
for i in {1..500}; do
  curl -sX POST http://localhost:8080/concerts/$CID/bookings \
    -H 'Content-Type: application/json' -d '{"seats":1}' > /dev/null
done
```
Now collect all booking IDs:
```bash
curl -s http://localhost:8080/concerts/$CID/bookings | jq -r '.[].bookingId' > /tmp/booking_ids.csv
```

### JMeter test plan
Two Thread Groups in parallel (both children of Test Plan):

**Thread Group 1 — Bookers**
- 500 threads, ramp-up 1s, loop 1
- POST `/concerts/<CID>/bookings` with `{"seats": 1}`

**Thread Group 2 — Cancellers**
- 500 threads, ramp-up 1s, loop 1
- Add a **CSV Data Set Config** pointing to `/tmp/booking_ids.csv`, variable name `BID`
- DELETE `/concerts/<CID>/bookings/${BID}`

Run both groups together. Make sure the Test Plan's "Run Thread Groups
consecutively" checkbox is **unchecked**.

### Expected result
- No HTTP 500 errors in either group.
- Final stats: `booked + available == 1000`.
- Final `booked` count matches what's actually returned by `GET /bookings`.

### Common failure signal
- Bookings count != actual list size → counter and collection drifted.

---

## Scenario C — Stats reader consistency under write storm

### JMeter test plan
Two Thread Groups in parallel:

**Thread Group 1 — Writers**
- 100 threads, ramp-up 1s, loop 10
- POST `/concerts/<CID>/bookings` with `{"seats": 1}`

**Thread Group 2 — Readers**
- 100 threads, ramp-up 0, loop 100
- GET `/concerts/<CID>/stats`
- Add a **Response Assertion** to the GET request:
  - Field to Test: **Response Body**
  - Pattern Matching: **Substring** (just be sure status is 200)

To check the invariant programmatically, add a **JSR223 PostProcessor** under
the GET request with Groovy:
```groovy
import groovy.json.JsonSlurper
def json = new JsonSlurper().parseText(prev.getResponseDataAsString())
def sum = json.booked + json.available
if (sum != json.capacity) {
    prev.setSuccessful(false)
    prev.setResponseMessage("INVARIANT I1 VIOLATED: booked=" + json.booked +
                            " + available=" + json.available + " != capacity=" + json.capacity)
}
```

### Expected result
- Zero failed reads on the assertion. **Even one** failure means invariant I5
  is broken and your stats endpoint returns inconsistent snapshots.

---

## Scenario D — Big-request contention

### Setup
Create a concert with capacity = 10.

### JMeter test plan
- 100 threads, ramp-up 1s, loop 1
- POST `/concerts/<CID>/bookings` with `{"seats": 10}` (asking for the whole concert in one shot)

### Expected result
- **Exactly one** HTTP 201 success.
- **Exactly 99** HTTP 409 failures.
- Zero 500s.

If you see 2+ successes, your "check seats then deduct" is interleaving.

---

## Scenario E — Cancel storm

### Setup
Pre-populate 1000 bookings (1 seat each) on a concert with capacity ≥ 1000.
Export the IDs into `/tmp/booking_ids.csv`.

### JMeter test plan
- 1000 threads, ramp-up 1s, loop 1
- CSV Data Set Config: `/tmp/booking_ids.csv`, variable `BID`, Recycle on EOF: **False**
- DELETE `/concerts/<CID>/bookings/${BID}`

### Expected result
- **Exactly 1000** HTTP 204 successes.
- `booked` returns to 0; `available` returns to capacity.
- Try running the test **twice in a row** — the second run should produce
  **exactly 1000** HTTP 404s (all bookings already cancelled).

If the second run produces any 204s, you have a double-cancellation bug.

---

## Running in non-GUI mode (for honest measurements)

Once you've built and saved a plan as `myplan.jmx`:
```bash
jmeter -n -t myplan.jmx -l results.jtl -e -o report/
```
- `-n` = non-GUI (no overhead)
- `-l results.jtl` = raw results file
- `-e -o report/` = generate the HTML dashboard in `report/`

Then open `report/index.html` in your browser.

Quick CLI summary:
```bash
awk -F',' 'NR>1 {print $4}' results.jtl | sort | uniq -c
# Example output:
#  100 201
#  400 409
```

---

## Helpful Listener: View Results Tree (for debugging only)

While building the plan, add **View Results Tree** to see exact request/response
for each call. **Disable or delete it before real load tests** — it records every
request and will OOM with 1000 threads.

---

## Reference: which scenario tests which invariant

| Scenario | Tests Invariants |
|---|---|
| A. Overbooking | I1 (booked+available=capacity), I2 (no oversell) |
| B. Mixed | I1, I3 (no lost bookings) |
| C. Stats reader storm | I1, I5 (coherent snapshot) |
| D. Big request | I2 (no oversell on multi-seat requests) |
| E. Cancel storm | I4 (no double cancellation) |
