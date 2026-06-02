package com.redspace.wikistreamkotlin.core.exception

/**
 * Framework-agnostic error logging interface.
 * Implementations: ConsumerErrorLogger (spring), ProducerErrorLogger (spring), etc.
 */
interface ErrorLogger {
    fun log(
        error: AppError,
        level: ErrorLogLevel = ErrorLogLevel.ERROR,
        context: Map<String, Any?> = emptyMap(),
    )
}

enum class ErrorLogLevel {
    WARN,
    ERROR,
}