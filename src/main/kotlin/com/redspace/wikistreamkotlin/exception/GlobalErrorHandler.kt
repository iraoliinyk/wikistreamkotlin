package com.redspace.wikistreamkotlin.exception

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.server.ServerWebInputException
import java.time.Instant

@RestControllerAdvice
class GlobalErrorHandler(
    private val appErrorLogger: AppErrorLogger,
) {
    @ExceptionHandler(AppError::class)
    fun handleAppError(
        error: AppError,
        request: ServerHttpRequest,
    ): ProblemDetail {
        appErrorLogger.log(error, context = mapOf("path" to request.path.value()))
        return error.toProblemDetail(request)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpectedError(
        exception: Exception,
        request: ServerHttpRequest,
    ): ProblemDetail {
        val error =
            UnexpectedAppError(
                message = "Unexpected error while processing request",
                cause = exception,
            )
        appErrorLogger.log(error, context = mapOf("path" to request.path.value()))
        return error.toProblemDetail(request)
    }

    @ExceptionHandler(WebExchangeBindException::class, ServerWebInputException::class)
    fun handleBadRequest(request: ServerHttpRequest): ProblemDetail =
        request.badRequestProblemDetail(
            detail = "Invalid request payload",
        )

    private fun AppError.toProblemDetail(request: ServerHttpRequest): ProblemDetail {
        val status = if (this is HttpAppError) this.status else HttpStatus.INTERNAL_SERVER_ERROR
        return ProblemDetail.forStatusAndDetail(status, message ?: "Application error").apply {
            title = this@toProblemDetail.type
            setProperty("type", this@toProblemDetail.type)
            setProperty("path", request.path.value())
            setProperty("timestamp", Instant.now().toString())
        }
    }

    private fun ServerHttpRequest.badRequestProblemDetail(detail: String): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail).apply {
            title = "request_validation_error"
            setProperty("type", "request_validation_error")
            setProperty("path", this@badRequestProblemDetail.path.value())
            setProperty("timestamp", Instant.now().toString())
        }
}
