package com.redspace.wikistreamkotlin.repository

import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.Test

class SessionRepositoryTest {
    private val mockSessionRepository: SessionRepository = mock()

    @Test
    fun `markLoggedIn delegates to SessionRepository`() {
        whenever(mockSessionRepository.isActive(any())).thenReturn(false)

        mockSessionRepository.markLoggedIn("alice@example.com")

        verify(mockSessionRepository).markLoggedIn("alice@example.com", emptyMap())
    }
}
