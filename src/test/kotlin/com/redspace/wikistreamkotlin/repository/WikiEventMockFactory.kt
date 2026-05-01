package com.redspace.wikistreamkotlin.repository

import com.redspace.wikistreamkotlin.domain.WikiEvent
import com.redspace.wikistreamkotlin.domain.WikiEventMeta
import java.time.Instant
import java.util.UUID

object WikiEventMockFactory {

    fun createWikiEvent(
        id: Long = 123456789L,
        title: String = "Kotlin (programming language)",
        user: String = "TestUser",
        bot: Boolean = false,
        meta: WikiEventMeta = createWikiEventMeta()
    ): WikiEvent {
        return WikiEvent(
            schema = "mediawiki/recentchange/1.0.0",
            meta = meta,
            id = id,
            type = "edit",
            namespace = 0,
            title = title,
            titleUrl = "https://en.wikipedia.org/wiki/$title",
            comment = "Fixed a typo in the introduction",
            timestamp = Instant.now().epochSecond,
            user = user,
            bot = bot,
            notifyUrl = "https://en.wikipedia.org/w/index.php?diff=123&oldid=122",
            serverUrl = "https://en.wikipedia.org",
            serverName = "en.wikipedia.org",
            serverScriptPath = "/w",
            wiki = "enwiki",
            parsedComment = "Fixed a typo in the introduction"
        )
    }

    fun createWikiEventMeta(
        requestId: String = UUID.randomUUID().toString(),
        domain: String = "en.wikipedia.org",
        stream: String = "mediawiki.recentchange"
    ): WikiEventMeta {
        return WikiEventMeta(
            uri = "https://en.wikipedia.org/wiki/Main_Page",
            requestId = requestId,
            id = UUID.randomUUID().toString(),
            domain = domain,
            stream = stream,
            dt = Instant.now().toString(),
            topic = "eqiad.mediawiki.recentchange",
            partition = "0",
            offset = 1001L
        )
    }
}