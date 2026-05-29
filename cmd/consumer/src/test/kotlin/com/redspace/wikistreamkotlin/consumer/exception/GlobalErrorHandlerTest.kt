package com.redspace.wikistreamkotlin.consumer.exception

import com.redspace.wikistreamkotlin.core.exception.StatsSnapshotError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.web.server.ResponseStatusException

class GlobalErrorHandlerTest {
    private val handler = GlobalErrorHandler(AppErrorLogger())

    @Test
    fun `handleAppError maps typed error to response`() {
        val request = MockServerHttpRequest.get("/v1/stats").build()
        val error = StatsSnapshotError("Failed to fetch stats snapshot", IllegalStateException("boom"))

        val response = handler.handleAppError(error, request)

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), response.status)
        assertEquals("stats_snapshot_error", response.title)
        assertEquals("Failed to fetch stats snapshot", response.detail)
        assertEquals("stats_snapshot_error", response.properties?.get("type"))
        assertEquals("/v1/stats", response.properties?.get("path"))
        assertNotNull(response.properties?.get("timestamp"))
    }

    @Test
    fun `handleUnexpectedError maps generic exception to unexpected app error response`() {
        val request = MockServerHttpRequest.get("/v1/stats").build()
        val response = handler.handleUnexpectedError(IllegalArgumentException("invalid"), request)

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), response.status)
        assertEquals("unexpected_app_error", response.title)
        assertEquals("Unexpected error while processing request", response.detail)
        assertEquals("unexpected_app_error", response.properties?.get("type"))
        assertEquals("/v1/stats", response.properties?.get("path"))
        assertNotNull(response.properties?.get("timestamp"))
    }

    @Test
    fun `handleResponseStatusException keeps http status instead of converting to 500`() {
        val request = MockServerHttpRequest.post("/v1/auth/register").build()
        val response = handler.handleResponseStatusException(ResponseStatusException(HttpStatus.NOT_FOUND), request)

        assertEquals(HttpStatus.NOT_FOUND.value(), response.status)
        assertEquals("not_found_error", response.title)
        assertEquals("Requested endpoint was not found", response.detail)
        assertEquals("not_found_error", response.properties?.get("type"))
        assertEquals("/v1/auth/register", response.properties?.get("path"))
        assertNotNull(response.properties?.get("timestamp"))
    }
}
