package com.redspace.wikistreamkotlin.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ActiveUserSessionServiceTest {

    private val service = ActiveUserSessionService()

    @Test
    fun `markLoggedIn adds email to active users`() {
        service.markLoggedIn("alice@example.com")

        assertTrue(service.listActiveUsers().contains("alice@example.com"))
    }

    @Test
    fun `markLoggedOut removes logged-in email`() {
        service.markLoggedIn("alice@example.com")
        service.markLoggedOut("alice@example.com")

        assertFalse(service.listActiveUsers().contains("alice@example.com"))
    }

    @Test
    fun `markLoggedOut with null email does nothing`() {
        service.markLoggedIn("alice@example.com")
        service.markLoggedOut(null)

        assertTrue(service.listActiveUsers().contains("alice@example.com"))
    }

    @Test
    fun `markLoggedOut with blank email does nothing`() {
        service.markLoggedIn("alice@example.com")
        service.markLoggedOut("   ")

        assertTrue(service.listActiveUsers().contains("alice@example.com"))
    }

    @Test
    fun `listActiveUsers returns a snapshot unaffected by subsequent mutations`() {
        service.markLoggedIn("alice@example.com")
        val snapshotBefore = service.listActiveUsers()

        service.markLoggedIn("bob@example.com")

        assertFalse(snapshotBefore.contains("bob@example.com"))
    }

    @Test
    fun `multiple users can be tracked simultaneously`() {
        service.markLoggedIn("alice@example.com")
        service.markLoggedIn("bob@example.com")
        service.markLoggedIn("carol@example.com")

        val active = service.listActiveUsers()
        assertEquals(3, active.size)
        assertTrue(active.containsAll(listOf("alice@example.com", "bob@example.com", "carol@example.com")))
    }

    @Test
    fun `markLoggedIn is idempotent for the same email`() {
        service.markLoggedIn("alice@example.com")
        service.markLoggedIn("alice@example.com")

        assertEquals(1, service.listActiveUsers().size)
    }

    @Test
    fun `markLoggedOut for non-registered email does nothing`() {
        service.markLoggedOut("ghost@example.com")

        assertTrue(service.listActiveUsers().isEmpty())
    }
}

