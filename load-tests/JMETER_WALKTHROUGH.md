# JMeter Walkthrough — Scenario A (Overbooking)

Step-by-step click-by-click guide. Assumes JMeter is **open right now** with a
blank test plan.

> **Before you start**: the Spring Boot app must be running, and your
> `BookingServiceImpl` must be implemented (at least `createConcert` and
> `createBooking`). Until then JMeter will hit the app and get HTTP 500.

---

## Step 0 — Start the app and create a concert

In a terminal (separate from JMeter):

```bash
cd /Users/habiba.ahmed/Desktop/concurrency-lab
mvn spring-boot:run
```

In another terminal:

```bash
curl -sX POST http://localhost:8080/concerts \
  -H 'Content-Type: application/json' \
  -d '{"name":"Overbooking Test","capacity":100}'
```

**Copy the `concertName` from the response — you'll paste it into JMeter.**
Example response:
```json
{"concertName":"3f9b8c12-8a4d-...","name":"Overbooking Test","capacity":100}
```

---

## Step 1 — Rename the Test Plan

Top-left tree: click **Test Plan** (the root node).
On the right panel:
- **Name**: change to `Overbooking Test`

---

## Step 2 — Add a Thread Group

In the left tree, **right-click `Overbooking Test` (Test Plan)**:
- **Add → Threads (Users) → Thread Group**

A new `Thread Group` appears under Test Plan. Click it. On the right panel set:

| Field | Value |
|---|---|
| Name | `500 Bookers` |
| Number of Threads (users) | `500` |
| Ramp-up period (seconds) | `1` |
| Loop Count | `1` |

Leave everything else at defaults.

> What this means: JMeter will launch 500 virtual users within 1 second, each
> one runs the requests under this Thread Group exactly once, then stops.

---

## Step 3 — Add an HTTP Header Manager

Right-click `500 Bookers` (Thread Group):
- **Add → Config Element → HTTP Header Manager**

Click the new `HTTP Header Manager`. In the right panel:
- Click **Add** at the bottom
- A new row appears. Fill in:
  - **Name**: `Content-Type`
  - **Value**: `application/json`

---

## Step 4 — Add the HTTP Request Sampler

Right-click `500 Bookers` (Thread Group) again:
- **Add → Sampler → HTTP Request**

Click the new `HTTP Request`. In the right panel:

| Field | Value |
|---|---|
| Name | `POST /concerts/{id}/bookings` |
| Protocol | `http` |
| Server Name or IP | `localhost` |
| Port Number | `8080` |
| HTTP Method | `POST` |
| Path | `/concerts/<PASTE_YOUR_CONCERT_ID_HERE>/bookings` |

Scroll down to the **Body Data** tab (it's a tab at the bottom of the request
panel, next to "Parameters" and "Files Upload"). Paste exactly:

```json
{"seats": 1}
```

> If JMeter shows a warning like "Body Data is only sent when Method is POST/PUT
> and Parameters tab is empty", that's fine — the Parameters tab IS empty.

---

## Step 5 — Add Listeners (so you can see the results)

Right-click `500 Bookers` (Thread Group):
- **Add → Listener → Summary Report**

Right-click again:
- **Add → Listener → View Results in Table**

Right-click one more time:
- **Add → Listener → Aggregate Report**

You now have 3 listeners. The two important ones:
- **View Results in Table** — shows every single request with its response code
- **Summary Report** — totals and averages

> Don't add "View Results Tree" — for 500 requests it works fine, but for
> larger tests it eats memory. Skip it.

---

## Step 6 — Verify the tree

Your left-side tree should now look like this:

```
Overbooking Test (Test Plan)
└── 500 Bookers (Thread Group)
    ├── HTTP Header Manager
    ├── POST /concerts/{id}/bookings (HTTP Request)
    ├── Summary Report
    ├── View Results in Table
    └── Aggregate Report
```

If yours doesn't match, drag-and-drop items in the tree to reorder them, or
right-click → Remove and re-add.

---

## Step 7 — Save the test plan

**File → Save Test Plan As…**

Save it as `/Users/habiba.ahmed/Desktop/concurrency-lab/load-tests/overbooking.jmx`

This is your file — you control it.

---

## Step 8 — Run it

Top toolbar: click the **green play button (▶)**.
Or menu: **Run → Start**.

It will complete in 1–3 seconds (500 quick HTTP requests).

When done, the small "running" indicator in the top-right turns off and you'll
see numbers appear in the listeners.

---

## Step 9 — Read the results

### Click "View Results in Table"
You'll see 500 rows. Look at the **Response Code** column.

Sort by it (click the column header). Expected:
- 100 rows with `201` (booking succeeded)
- 400 rows with `409` (insufficient seats)
- 0 rows with `500`

### Click "Summary Report"
Look at the `# Samples` and `Error %` columns.
- `# Samples` = 500
- `Error %` should be 80.00% (400 of 500 are 409s, which JMeter counts as "errors"
  because the response code is ≥ 400 — that's expected for this test).

> JMeter's default "error" definition is response code ≥ 400. For us, 409 is a
> CORRECT response (server correctly rejected over-capacity bookings). So
> `Error %` being 80% does NOT mean your code is broken — read the actual codes.

### Click "Aggregate Report"
Shows throughput (`Throughput` column, requests/sec) and percentile latencies.
For Scenario A you mostly care about the response codes; latency matters more
in the heavier scenarios.

---

## Step 10 — Verify the invariants

JMeter only tells you about HTTP responses. You still need to check the app's
state afterwards. In your terminal:

```bash
curl -s http://localhost:8080/concerts/<YOUR_CONCERT_ID>/stats
```

Expected output:
```json
{
  "capacity": 100,
  "booked": 100,
  "available": 0,
  "totalBookings": 100,
  "totalCancellations": 0
}
```

**Pass criteria:**
- `booked == 100`
- `available == 0`
- `booked + available == 100` (matches capacity)
- `totalBookings == 100`

If any of these are off, your implementation has a concurrency bug, even if
JMeter showed exactly 100 of 201.

---

## Step 11 — Interpret what you saw

| JMeter result | App stats | Diagnosis |
|---|---|---|
| 100×201 + 400×409 | `booked=100, available=0` | ✅ **PASS** — your implementation is thread-safe under this load |
| >100 of 201 | `booked > 100` | ❌ **OVERSELL** — your "check-then-book" isn't atomic |
| 100 of 201 but `booked + available != 100` | drift | ❌ Counter and collection are out of sync |
| Any 500s | n/a | ❌ Unhandled exception — likely `ConcurrentModificationException` |
| <100 of 201 | `booked < 100` | ❌ Lost bookings (rare, but possible) |

---

## Step 12 — Re-run the test (concurrency bugs are non-deterministic!)

A single passing run is **not enough**. Concurrency bugs come and go. To re-run:

1. **Reset the concert** — create a NEW concert (the current one is full):
   ```bash
   curl -sX POST http://localhost:8080/concerts \
     -H 'Content-Type: application/json' \
     -d '{"name":"Overbooking Test","capacity":100}'
   ```
2. Update the **Path** field in the JMeter HTTP Request with the new concertName.
3. Click the green play button.
4. Clear old results: **Run → Clear All** (or `Ctrl+E`) between runs, otherwise
   listeners accumulate from previous runs.

**Run at least 10 times** before trusting "PASS".

---

## When the test passes 10× in a row

Come back to the chat with:
- A copy/paste of `Concert.java` and `BookingServiceImpl.java`
- A note saying "passed 10/10 runs"

I'll do the design review: ask you why you chose what you chose, push you on
edge cases the test didn't cover, and confirm the lesson learned.

---

## When the test fails

Come back with:
- The actual response code counts JMeter showed
- The stats output from `curl /stats`
- Your `BookingServiceImpl.java`

I won't give you the fix. I'll ask questions until you spot it yourself.
