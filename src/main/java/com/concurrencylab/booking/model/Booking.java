package com.concurrencylab.booking.model;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;


@Getter
@Setter
@AllArgsConstructor
public final class Booking {

    private String uuid;
    private Integer seats = 0;
    private Instant createdAt;

}
