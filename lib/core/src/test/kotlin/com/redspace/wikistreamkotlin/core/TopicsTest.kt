package com.redspace.wikistreamkotlin.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TopicsTest {
    @Test
    fun topicNamesAreStable() {
        assertEquals("wiki.recentchange.proto", Topics.PROTO)
        assertEquals("wiki.recentchange.dlq", Topics.DLQ)
        assertEquals("wiki.recentchange.validated", Topics.VALIDATED)
    }
}

