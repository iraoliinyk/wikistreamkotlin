package com.redspace.wikistreamkotlin.exception

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant

@RestControllerAdvice
class GlobalErrorHandler(
    private val appErrorLogger: AppErrorLogger
) {

    @ExceptionHandler(AppError::class)
    fun handleAppError(
        error: AppError,
        request: ServerHttpRequest
    ): ProblemDetail {
        appErrorLogger.log(error, context = mapOf("path" to request.path.value()))
        return error.toProblemDetail(request)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpectedError(
        exception: Exception,
        request: ServerHttpRequest
    ): ProblemDetail {
        val error = UnexpectedAppError(
            message = "Unexpected error while processing request",
            cause = exception
        )
        appErrorLogger.log(error, context = mapOf("path" to request.path.value()))
        return error.toProblemDetail(request)
    }

    private fun AppError.toProblemDetail(request: ServerHttpRequest): ProblemDetail {
        val status = if (this is HttpAppError) this.status else HttpStatus.INTERNAL_SERVER_ERROR
        return ProblemDetail.forStatusAndDetail(status, message ?: "Application error").apply {
            title = this@toProblemDetail.type
            setProperty("type", this@toProblemDetail.type)
            setProperty("path", request.path.value())
            setProperty("timestamp", Instant.now().toString())
        }
    }
}

