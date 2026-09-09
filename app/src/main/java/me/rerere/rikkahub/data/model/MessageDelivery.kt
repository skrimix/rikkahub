package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class MessageDelivery {
    STEER,
    NEXT_TURN,
}
