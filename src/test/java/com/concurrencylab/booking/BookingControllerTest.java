package com.concurrencylab.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Layer 1 — HTTP integration tests. Single-threaded.
 *
 * IMPORTANT: BookingServiceImpl is a Spring singleton, so its in-memory state
 * (the concerts HashMap) persists between tests. To keep tests independent, each
 * test generates a UNIQUE concert name via uniqueName().
 */
@SpringBootTest
@AutoConfigureMockMvc
class BookingControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private String uniqueName() {
        return "Concert-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String createConcert(String name, int capacity) throws Exception {
        String body = String.format("{\"name\":\"%s\",\"capacity\":%d}", name, capacity);
        mockMvc.perform(post("/concerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
        return name;
    }

    private String createBookingAndGetId(String concertName, int seats) throws Exception {
        MvcResult result = mockMvc.perform(post("/concerts/" + concertName + "/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seats\":" + seats + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("uuid").asText();
    }

    // ====================================================================
    // POST /concerts
    // ====================================================================

    @Test
    void createConcert_validInput_returns201() throws Exception {
        String name = uniqueName();
        String body = String.format("{\"name\":\"%s\",\"capacity\":100}", name);

        mockMvc.perform(post("/concerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.capacity").value(100));
    }

    @Test
    void createConcert_blankName_returns400() throws Exception {
        String body = "{\"name\":\"\",\"capacity\":100}";

        mockMvc.perform(post("/concerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createConcert_zeroCapacity_returns400() throws Exception {
        String body = "{\"name\":\"X\",\"capacity\":0}";

        mockMvc.perform(post("/concerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createConcert_negativeCapacity_returns400() throws Exception {
        String body = "{\"name\":\"X\",\"capacity\":-5}";

        mockMvc.perform(post("/concerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ====================================================================
    // POST /concerts/{name}/bookings
    // ====================================================================

    @Test
    void createBooking_validRequest_returns201WithBookingId() throws Exception {
        String name = createConcert(uniqueName(), 100);

        mockMvc.perform(post("/concerts/" + name + "/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seats\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uuid").exists())
                .andExpect(jsonPath("$.seats").value(2))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void createBooking_concertNotFound_returns404() throws Exception {
        mockMvc.perform(post("/concerts/DoesNotExist/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seats\":1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createBooking_insufficientSeats_returns409() throws Exception {
        String name = createConcert(uniqueName(), 5);

        mockMvc.perform(post("/concerts/" + name + "/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seats\":6}"))
                .andExpect(status().isConflict());
    }

    @Test
    void createBooking_zeroSeats_returns400() throws Exception {
        String name = createConcert(uniqueName(), 10);

        mockMvc.perform(post("/concerts/" + name + "/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seats\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createBooking_negativeSeats_returns400() throws Exception {
        String name = createConcert(uniqueName(), 10);

        mockMvc.perform(post("/concerts/" + name + "/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seats\":-1}"))
                .andExpect(status().isBadRequest());
    }

    // ====================================================================
    // GET /concerts/{name}/stats
    // ====================================================================

    @Test
    void getStats_freshConcert_returnsEmptyState() throws Exception {
        String name = createConcert(uniqueName(), 50);

        mockMvc.perform(get("/concerts/" + name + "/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(50))
                .andExpect(jsonPath("$.booked").value(0))
                .andExpect(jsonPath("$.available").value(50))
                .andExpect(jsonPath("$.totalBookings").value(0))
                .andExpect(jsonPath("$.totalCancellations").value(0));
    }

    @Test
    void getStats_afterBooking_reflectsReservedSeats() throws Exception {
        String name = createConcert(uniqueName(), 10);
        createBookingAndGetId(name, 3);

        mockMvc.perform(get("/concerts/" + name + "/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(10))
                .andExpect(jsonPath("$.booked").value(3))
                .andExpect(jsonPath("$.available").value(7));
    }

    @Test
    void getStats_concertNotFound_returns404() throws Exception {
        mockMvc.perform(get("/concerts/Nope/stats"))
                .andExpect(status().isNotFound());
    }

    // ====================================================================
    // DELETE /concerts/{name}/bookings/{bookingId}
    // ====================================================================

    @Test
    void cancelBooking_validBooking_returns204AndStatsUpdated() throws Exception {
        String name = createConcert(uniqueName(), 10);
        String bookingId = createBookingAndGetId(name, 2);

        mockMvc.perform(delete("/concerts/" + name + "/bookings/" + bookingId))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/concerts/" + name + "/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booked").value(0))
                .andExpect(jsonPath("$.available").value(10))
                .andExpect(jsonPath("$.totalCancellations").value(2));
    }

    @Test
    void cancelBooking_bookingNotFound_returns404() throws Exception {
        String name = createConcert(uniqueName(), 10);

        mockMvc.perform(delete("/concerts/" + name + "/bookings/999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelBooking_concertNotFound_returns404() throws Exception {
        mockMvc.perform(delete("/concerts/Nope/bookings/1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelBooking_alreadyCancelled_returns404() throws Exception {
        String name = createConcert(uniqueName(), 10);
        String bookingId = createBookingAndGetId(name, 1);

        mockMvc.perform(delete("/concerts/" + name + "/bookings/" + bookingId))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/concerts/" + name + "/bookings/" + bookingId))
                .andExpect(status().isNotFound());
    }

    // ====================================================================
    // GET /concerts/{name}/bookings
    // ====================================================================

    @Test
    void listBookings_emptyConcert_returnsEmptyArray() throws Exception {
        String name = createConcert(uniqueName(), 10);

        mockMvc.perform(get("/concerts/" + name + "/bookings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void listBookings_returnsAllActiveBookings() throws Exception {
        String name = createConcert(uniqueName(), 20);
        createBookingAndGetId(name, 2);
        createBookingAndGetId(name, 3);
        createBookingAndGetId(name, 5);

        mockMvc.perform(get("/concerts/" + name + "/bookings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));
    }

    @Test
    void listBookings_afterCancellation_excludesCancelledOnes() throws Exception {
        String name = createConcert(uniqueName(), 20);
        String first = createBookingAndGetId(name, 2);
        createBookingAndGetId(name, 3);

        mockMvc.perform(delete("/concerts/" + name + "/bookings/" + first))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/concerts/" + name + "/bookings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void listBookings_concertNotFound_returns404() throws Exception {
        mockMvc.perform(get("/concerts/Nope/bookings"))
                .andExpect(status().isNotFound());
    }
}
