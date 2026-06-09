package com.concurrencylab.booking.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateConcertRequest(
        @NotBlank String name,
        @Min(1) int capacity) {}
