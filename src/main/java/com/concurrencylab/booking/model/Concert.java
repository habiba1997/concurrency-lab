package com.concurrencylab.booking.model;

/**
 * A Concert holds:
 *   - an id (immutable)
 *   - a name (immutable)
 *   - a capacity (immutable)
 *   - a collection of active bookings (mutable)
 *   - running counters: how many seats are booked, totalBookings, totalCancellations
 *
 * Design decisions YOU need to make:
 *   - How will you store bookings? (think: many concurrent reads, many concurrent writes)
 *   - How will you track "booked seats" so that booking + reading stats from
 *     different threads always agree?
 *   - When a booking is attempted, the "is there room? if yes, book" check must
 *     happen as one indivisible step. How will you guarantee that?
 *   - When stats are read, the 5 returned numbers must all reflect the SAME moment.
 *     How will you guarantee that?
 *
 * Fill in the fields and methods. Keep this class focused on Concert state only;
 * IDs and external orchestration belong in the Service.
 */
public class Concert {

    private final String id;
    private final String name;
    private final int capacity;


    public Concert(String id, String name, int capacity) {
        this.id = id;
        this.name = name;
        this.capacity = capacity;
    }

    public String getId()    { return id; }
    public String getName()  { return name; }
    public int getCapacity() { return capacity; }

}
