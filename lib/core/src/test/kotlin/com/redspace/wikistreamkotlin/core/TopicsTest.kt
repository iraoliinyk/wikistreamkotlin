package com.redspace.wikistreamkotlin.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TopicsTest {
    @Test
    fun topicNamesAreStable() {
        @Suppress("DEPRECATION")
        assertEquals("wiki.recentchange.raw", Topics.RAW)
        assertEquals("wiki.recentchange.proto", Topics.PROTO)
        assertEquals("wiki.recentchange.dlq", Topics.DLQ)
    }
}

