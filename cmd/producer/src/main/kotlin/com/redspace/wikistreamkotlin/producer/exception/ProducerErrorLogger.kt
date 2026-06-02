package com.redspace.wikistreamkotlin.producer.exception

import com.redspace.wikistreamkotlin.core.exception.AppError
import com.redspace.wikistreamkotlin.core.exception.ErrorLogger
import com.redspace.wikistreamkotlin.core.exception.ErrorLogLevel
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class ProducerErrorLogger : ErrorLogger {
    private val logger = LoggerFactory.getLogger(ProducerErrorLogger::class.java)

    override fun log(
        error: AppError,
        level: ErrorLogLevel,
        context: Map<String, Any?>,
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
            ErrorLogLevel.WARN -> logger.warn(message, throwable)
            ErrorLogLevel.ERROR -> logger.error(message, throwable)
        }
    }
}