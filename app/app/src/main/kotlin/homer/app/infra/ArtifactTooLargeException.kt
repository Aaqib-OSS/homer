package homer.app.infra

/** A file of [size] bytes against the [maxBytes] cap its reader was given. */
class ArtifactTooLargeException(
    val size: Long,
    val maxBytes: Long,
) : RuntimeException("Artifact size $size exceeds maximum $maxBytes")
