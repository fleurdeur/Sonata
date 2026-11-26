package com.sonata.core.work.pipeline

import com.sonata.core.domain.model.ImportFailureKind

/** Typed import failure surfaced to the queue with actionable retry policy. */
class ImportFailureException(
    val kind: ImportFailureKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
