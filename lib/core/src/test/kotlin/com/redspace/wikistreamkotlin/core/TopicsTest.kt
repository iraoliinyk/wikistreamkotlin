package com.redspace.wikistreamkotlin.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TopicsTest {
    @Test
    fun topicNamesAreStable() {
        assertEquals("wiki.recentchange.raw", Topics.RAW)
        assertEquals("wiki.recentchange.dlq", Topics.DLQ)
    }
}

