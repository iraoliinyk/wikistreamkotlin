package com.redspace.wikistreamkotlin.service

import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class ActiveUserSessionService {
    private val activeUsers = ConcurrentHashMap.newKeySet<String>()

    fun markLoggedIn(email: String) {
        activeUsers.add(email)
    }

    fun markLoggedOut(email: String?) {
        if (email.isNullOrBlank()) {
            return
        }
        activeUsers.remove(email)
    }

    fun listActiveUsers(): Set<String> = activeUsers.toSet()
}

