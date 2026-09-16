package me.rerere.ai.core

import kotlinx.serialization.Serializable

@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    val totalTokens: Int = 0,
)

/** Merges cumulative usage updates from the same request. */
fun TokenUsage?.merge(other: TokenUsage): TokenUsage {
    val promptTokens = if (other.promptTokens > 0) {
        other.promptTokens
    } else {
        this?.promptTokens ?: 0
    }
    val completionTokens = if (other.completionTokens > 0) {
        other.completionTokens
    } else {
        this?.completionTokens ?: 0
    }
    val totalTokens = promptTokens + completionTokens
    val cachedTokens = if (other.cachedTokens > 0) {
        other.cachedTokens
    } else {
        this?.cachedTokens ?: 0
    }
    return TokenUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        cachedTokens = cachedTokens
    )
}

/** Adds usage from separate requests, preserving missing usage as null. */
fun TokenUsage?.sum(other: TokenUsage?): TokenUsage? {
    if (this == null && other == null) return null
    return TokenUsage(
        promptTokens = (this?.promptTokens ?: 0) + (other?.promptTokens ?: 0),
        completionTokens = (this?.completionTokens ?: 0) + (other?.completionTokens ?: 0),
        cachedTokens = (this?.cachedTokens ?: 0) + (other?.cachedTokens ?: 0),
        totalTokens = this.totalOrInferred() + other.totalOrInferred(),
    )
}

private fun TokenUsage?.totalOrInferred(): Int {
    if (this == null) return 0
    return totalTokens.takeIf { it > 0 } ?: (promptTokens + completionTokens)
}
