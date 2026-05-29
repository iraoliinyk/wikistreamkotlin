package com.redspace.wikistreamkotlin.exception

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

enum class AppErrorLogLevel {
    WARN,
    ERROR,
}

@Component
class AppErrorLogger {
    private val logger = LoggerFactory.getLogger(AppErrorLogger::class.java)

    fun log(
        error: AppError,
        level: AppErrorLogLevel = AppErrorLogLevel.ERROR,
        context: Map<String, Any?> = emptyMap(),
    ) {
        val throwable = error.cause ?: error
        val contextPart =
            if (context.isEmpty()) {
                ""
            } else {
                context.entries.joinToString(prefix = ", context={", postfix = "}") { (key, value) ->
                    "$key=$value"
                }
            }
        val message =
            "type=${error.type}, " +
                "message=${error.message}, " +
                "exception=${throwable::class.qualifiedName}$contextPart"

        when (level) {
            AppErrorLogLevel.WARN -> logger.warn(message, throwable)
            AppErrorLogLevel.ERROR -> logger.error(message, throwable)
        }
    }
}
