package com.redspace.wikistreamkotlin.core.exception

import org.springframework.http.HttpStatus

sealed class AppError(
    val type: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

sealed class HttpAppError(
    type: String,
    val status: HttpStatus,
    message: String,
    cause: Throwable? = null,
) : AppError(
        type = type,
        message = message,
        cause = cause,
    )

class WikiStreamConfigurationError(
    message: String,
    cause: Throwable? = null,
) : AppError(
        type = "wiki_stream_configuration_error",
        message = message,
        cause = cause,
    )

class WikiStreamConnectionError(
    message: String,
    cause: Throwable? = null,
) : AppError(
        type = "wiki_stream_connection_error",
        message = message,
        cause = cause,
    )

class WikiEventParsingError(
    message: String,
    cause: Throwable? = null,
) : AppError(
        type = "wiki_event_parsing_error",
        message = message,
        cause = cause,
    )

class RepositoryWriteError(
    message: String,
    cause: Throwable? = null,
) : AppError(
        type = "repository_write_error",
        message = message,
        cause = cause,
    )

class RepositoryReadError(
    message: String,
    cause: Throwable? = null,
) : HttpAppError(
        type = "repository_read_error",
        status = HttpStatus.INTERNAL_SERVER_ERROR,
        message = message,
        cause = cause,
    )

class StatsRecordingError(
    message: String,
    cause: Throwable? = null,
) : AppError(
        type = "stats_recording_error",
        message = message,
        cause = cause,
    )

class StatsSnapshotError(
    message: String,
    cause: Throwable? = null,
) : HttpAppError(
        type = "stats_snapshot_error",
        status = HttpStatus.INTERNAL_SERVER_ERROR,
        message = message,
        cause = cause,
    )

class UserAlreadyExistsError(
    message: String,
    cause: Throwable? = null,
) : HttpAppError(
        type = "user_already_exists_error",
        status = HttpStatus.CONFLICT,
        message = message,
        cause = cause,
    )

class InvalidCredentialsError(
    message: String,
    cause: Throwable? = null,
) : HttpAppError(
        type = "invalid_credentials_error",
        status = HttpStatus.UNAUTHORIZED,
        message = message,
        cause = cause,
    )

class AuthValidationError(
    message: String,
    cause: Throwable? = null,
) : HttpAppError(
        type = "auth_validation_error",
        status = HttpStatus.BAD_REQUEST,
        message = message,
        cause = cause,
    )

class MissingTokenSubjectError(
    message: String,
    cause: Throwable? = null,
) : HttpAppError(
        type = "missing_token_subject_error",
        status = HttpStatus.BAD_REQUEST,
        message = message,
        cause = cause,
    )

class UnexpectedAppError(
    message: String,
    cause: Throwable? = null,
) : HttpAppError(
        type = "unexpected_app_error",
        status = HttpStatus.INTERNAL_SERVER_ERROR,
        message = message,
        cause = cause,
    )
/**
 * Error for malformed Kafka records (invalid JSON, parsing failures).
 * These are data quality issues from upstream and should not be retried.
 */
class MalformedKafkaRecordError(
    message: String,
    cause: Throwable? = null,
) : AppError(
    type = "malformed_kafka_record",
    message = message,
    cause = cause,
)

/**
 * Error for failures during Kafka record processing (DB errors, network issues, etc.).
 * These are transient issues and should trigger retry.
 */
class KafkaProcessingError(
    message: String,
    cause: Throwable? = null,
) : AppError(
    type = "kafka_processing_error",
    message = message,
    cause = cause,
)

/**
* Error when SSE stream fails after max retries.
* Indicates upstream data source is unavailable.
*/
class ProducerStreamError(
    message: String,
    cause: Throwable? = null,
) : AppError(
    type = "producer_stream_error",
    message = message,
    cause = cause,
)

/**
 * Error when Kafka publishing fails.
 * Indicates downstream broker is unavailable or rejection.
 */
class ProducerPublishError(
    message: String,
    cause: Throwable? = null,
) : AppError(
    type = "producer_publish_error",
    message = message,
    cause = cause,
)

/**
 * Error when event parsing fails.
 * Indicates malformed upstream data.
 */
class ProducerParsingError(
    message: String,
    cause: Throwable? = null,
) : AppError(
    type = "producer_parsing_error",
    message = message,
    cause = cause,
)