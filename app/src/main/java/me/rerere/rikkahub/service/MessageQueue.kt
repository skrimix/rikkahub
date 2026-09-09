package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageDelivery
import me.rerere.rikkahub.data.model.localFileUrls
import kotlin.uuid.Uuid

data class QueuedMessage(
    val id: Uuid = Uuid.random(),
    val parts: List<UIMessagePart>,
    val answer: Boolean = true,
    val isEditing: Boolean = false,
    val delivery: MessageDelivery = MessageDelivery.NEXT_TURN,
    // Optional in-memory observer; null result means the queued message was withdrawn.
    val reply: CompletableDeferred<String?>? = null,
)

data class MessageQueueState(
    val messages: List<QueuedMessage> = emptyList(),
    val paused: Boolean = false,
)

internal fun unreferencedQueuedAttachmentUrls(
    previous: QueuedMessage,
    conversations: List<Conversation>,
    pendingMessages: List<QueuedMessage>,
): Set<String> {
    val retainedParts = conversations.flatMap { conversation ->
        conversation.messageNodes.flatMap { node -> node.messages.flatMap { it.parts } }
    } + pendingMessages.flatMap { it.parts }
    return previous.parts.localFileUrls() - retainedParts.localFileUrls()
}

/** Pending input is kept outside conversation history until dispatched. */
class MessageQueuePausedException : IllegalStateException()

class MessageQueue {
    private val mutableState = MutableStateFlow(MessageQueueState())
    val state = mutableState.asStateFlow()
    private var acceptsSteering = false
    private var claimedSteering = emptyList<QueuedMessage>()

    @Synchronized
    fun enqueue(
        parts: List<UIMessagePart>,
        answer: Boolean = true,
        reply: CompletableDeferred<String?>? = null,
        delivery: MessageDelivery = MessageDelivery.NEXT_TURN,
    ) {
        if (parts.isEmptyInputMessage()) {
            reply?.complete(null)
            return
        }
        // A new steering message must not overtake an earlier message waiting for the next turn.
        val canSteer = delivery == MessageDelivery.STEER && acceptsSteering &&
                !state.value.paused && answer && reply == null &&
                state.value.messages.all { it.delivery == MessageDelivery.STEER }
        mutableState.value = state.value.copy(
            messages = state.value.messages + QueuedMessage(
                parts = parts.toList(),
                answer = answer,
                reply = reply,
                delivery = if (canSteer) MessageDelivery.STEER else MessageDelivery.NEXT_TURN,
            ),
        )
    }

    @Synchronized
    fun takeNext(): QueuedMessage? {
        val current = state.value
        if (current.paused) return null
        val next = current.messages.firstOrNull()?.takeUnless { it.isEditing } ?: return null
        mutableState.value = current.copy(messages = current.messages.drop(1))
        return next
    }

    @Synchronized
    fun remove(id: Uuid): QueuedMessage? {
        val removed = state.value.messages.find { it.id == id } ?: return null
        mutableState.value =
            state.value.copy(messages = state.value.messages.filterNot { it.id == id })
        removed.reply?.complete(null)
        return removed
    }

    @Synchronized
    fun beginEdit(id: Uuid): QueuedMessage? {
        val message = state.value.messages.find { it.id == id && !it.isEditing } ?: return null
        mutableState.value = state.value.copy(
            messages = state.value.messages.map { if (it.id == id) it.copy(isEditing = true) else it },
        )
        return message
    }

    @Synchronized
    fun finishEdit(id: Uuid, parts: List<UIMessagePart>? = null): QueuedMessage? {
        if (parts != null && parts.isEmptyInputMessage()) return null
        val previous = state.value.messages.find { it.id == id } ?: return null
        mutableState.value = state.value.copy(
            messages = state.value.messages.map {
                if (it.id == id) it.copy(
                    parts = parts?.toList() ?: it.parts,
                    isEditing = false
                ) else it
            },
        )
        // 取消编辑只释放占位，不能清理原附件。
        return previous.takeIf { parts != null }
    }

    @Synchronized
    fun pause() {
        closeSteering()
        mutableState.value = state.value.copy(paused = true)
        state.value.messages.forEach { it.reply?.completeExceptionally(MessageQueuePausedException()) }
    }

    fun failReplyWaiters(message: String) {
        state.value.messages.forEach {
            it.reply?.completeExceptionally(IllegalStateException(message))
        }
    }

    @Synchronized
    fun resume() {
        mutableState.value = state.value.copy(paused = false)
    }

    @Synchronized
    fun openSteering() {
        acceptsSteering = !state.value.paused
    }

    @Synchronized
    fun closeSteering() {
        acceptsSteering = false
        mutableState.value = state.value.copy(
            messages = state.value.messages.map { it.copy(delivery = MessageDelivery.NEXT_TURN) },
        )
    }

    /** Claims input at a model step boundary, preserving order and any edit in progress. */
    @Synchronized
    fun claimSteering(closeIfEmpty: Boolean): List<QueuedMessage> {
        if (!acceptsSteering || state.value.paused) return emptyList()
        val messages = state.value.messages.takeWhile {
            it.delivery == MessageDelivery.STEER && !it.isEditing
        }
        if (messages.isEmpty() && closeIfEmpty) {
            closeSteering()
        } else if (messages.isNotEmpty()) {
            claimedSteering += messages
            mutableState.value = state.value.copy(messages = state.value.messages.drop(messages.size))
        }
        return messages
    }

    /** Removes claimed input only after it has been saved in conversation history. */
    @Synchronized
    fun acknowledgeSteering(ids: Set<Uuid>) {
        claimedSteering = claimedSteering.filterNot { it.id in ids }
    }

    @Synchronized
    fun releaseClaimedSteering() {
        val messages = claimedSteering.map {
            if (acceptsSteering && !state.value.paused) it else it.copy(delivery = MessageDelivery.NEXT_TURN)
        }
        mutableState.value = state.value.copy(messages = messages + state.value.messages)
        claimedSteering = emptyList()
    }

    /** Includes input being saved, so its attachments remain referenced. */
    @Synchronized
    fun pendingMessages(): List<QueuedMessage> = claimedSteering + state.value.messages
}
