# Concurrency Lab

An in-memory concert-ticket booking service for exploring Java concurrency: overselling, lost updates, double cancellation, and consistent statistics under contention.

**Technologies:** Java 17, Spring Boot 3.3, JUnit 5, MockMvc, Lombok, and JMeter. No database or frontend is required.

## Run locally

```sh
mvn spring-boot:run
```

The API runs at http://localhost:8080. Create concerts with `POST /concerts`, book with `POST /concerts/{name}/bookings`, and inspect `GET /concerts/{name}/stats`.

Run `mvn test` for the concurrency tests. JMeter plans are in `load-tests/`; detailed scenarios are in `CONCURRENCY_SCENARIOS.md`.
