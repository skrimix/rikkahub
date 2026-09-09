package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.model.MessageDelivery
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SteeringMessageQueueTest {
    private fun text(value: String) = listOf(UIMessagePart.Text(value))

    @Test
    fun `steering preference persists and old settings default to steering`() {
        assertEquals(MessageDelivery.STEER, JsonInstant.decodeFromString<DisplaySetting>("{}").messageDelivery)
        val settings = DisplaySetting(messageDelivery = MessageDelivery.NEXT_TURN)
        assertEquals(settings, JsonInstant.decodeFromString<DisplaySetting>(JsonInstant.encodeToString(settings)))
    }

    @Test
    fun `steering is consumed in order only while a turn accepts it`() {
        val queue = MessageQueue()
        queue.enqueue(text("idle"), delivery = MessageDelivery.STEER)
        assertEquals(MessageDelivery.NEXT_TURN, queue.takeNext()!!.delivery)
        queue.openSteering()
        queue.enqueue(text("first"), delivery = MessageDelivery.STEER)
        queue.enqueue(text("second"), delivery = MessageDelivery.STEER)

        val claimed = queue.claimSteering(closeIfEmpty = false)
        assertEquals(listOf(text("first"), text("second")), claimed.map { it.parts })
        assertTrue(queue.state.value.messages.isEmpty())
        assertEquals(claimed, queue.pendingMessages())
        queue.acknowledgeSteering(claimed.mapTo(mutableSetOf()) { it.id })
        assertTrue(queue.pendingMessages().isEmpty())
    }

    @Test
    fun `next turn input is not consumed at a steering boundary`() {
        val queue = MessageQueue()
        queue.openSteering()
        queue.enqueue(text("next"), delivery = MessageDelivery.NEXT_TURN)
        assertTrue(queue.claimSteering(closeIfEmpty = false).isEmpty())
        assertEquals(text("next"), queue.takeNext()!!.parts)
    }

    @Test
    fun `switching preference cannot jump past earlier queued input`() {
        val queue = MessageQueue()
        queue.openSteering()
        queue.enqueue(text("first"), delivery = MessageDelivery.STEER)
        queue.enqueue(text("second"), delivery = MessageDelivery.NEXT_TURN)
        queue.enqueue(text("third"), delivery = MessageDelivery.STEER)

        assertEquals(listOf(text("first")), queue.claimSteering(closeIfEmpty = false).map { it.parts })
        assertEquals(listOf(text("second"), text("third")), queue.state.value.messages.map { it.parts })
        assertTrue(queue.state.value.messages.all { it.delivery == MessageDelivery.NEXT_TURN })
    }

    @Test
    fun `messages sent after the final drain fall back to the next turn`() {
        val queue = MessageQueue()
        queue.openSteering()
        assertTrue(queue.claimSteering(closeIfEmpty = true).isEmpty())
        queue.enqueue(text("late"), delivery = MessageDelivery.STEER)
        assertTrue(queue.claimSteering(closeIfEmpty = false).isEmpty())
        assertEquals(MessageDelivery.NEXT_TURN, queue.takeNext()!!.delivery)
    }

    @Test
    fun `editing steering blocks consumption until the edit finishes`() {
        val queue = MessageQueue()
        queue.openSteering()
        queue.enqueue(text("first"), delivery = MessageDelivery.STEER)
        queue.enqueue(text("second"), delivery = MessageDelivery.STEER)
        val id = queue.state.value.messages.first().id
        queue.beginEdit(id)

        assertTrue(queue.claimSteering(closeIfEmpty = false).isEmpty())
        queue.finishEdit(id, text("edited"))
        assertEquals(listOf(text("edited"), text("second")), queue.claimSteering(false).map { it.parts })
    }

    @Test
    fun `a turn finishing during an edit retains both messages for later`() {
        val queue = MessageQueue()
        queue.openSteering()
        queue.enqueue(text("first"), delivery = MessageDelivery.STEER)
        queue.enqueue(text("second"), delivery = MessageDelivery.STEER)
        val id = queue.state.value.messages.first().id
        queue.beginEdit(id)

        assertTrue(queue.claimSteering(closeIfEmpty = true).isEmpty())
        assertNull(queue.takeNext())
        queue.finishEdit(id, text("edited"))
        assertEquals(MessageDelivery.NEXT_TURN, queue.state.value.messages.first().delivery)
        assertEquals(text("edited"), queue.takeNext()!!.parts)
        assertEquals(text("second"), queue.takeNext()!!.parts)
    }

    @Test
    fun `claimed messages cannot be edited or removed and release is idempotent`() {
        val queue = MessageQueue()
        queue.openSteering()
        queue.enqueue(text("claimed"), delivery = MessageDelivery.STEER)
        val claimed = queue.claimSteering(false).single()
        assertNull(queue.beginEdit(claimed.id))
        assertNull(queue.remove(claimed.id))
        queue.finishEdit(claimed.id, text("late edit"))
        queue.releaseClaimedSteering()
        queue.releaseClaimedSteering()
        assertEquals(listOf(claimed), queue.state.value.messages)
        assertEquals(claimed, queue.remove(claimed.id))
    }

    @Test
    fun `stop retains unsaved and pending input but never replays saved input`() {
        val queue = MessageQueue()
        queue.openSteering()
        queue.enqueue(text("saved"), delivery = MessageDelivery.STEER)
        val saved = queue.claimSteering(false).single()
        queue.acknowledgeSteering(setOf(saved.id))
        val attachment = listOf(UIMessagePart.Image("file:///pending.png"))
        queue.enqueue(attachment, delivery = MessageDelivery.STEER)
        val unsaved = queue.claimSteering(false).single()
        queue.enqueue(text("later"), delivery = MessageDelivery.STEER)

        queue.pause()
        queue.releaseClaimedSteering()
        assertTrue(queue.state.value.paused)
        assertNull(queue.takeNext())
        assertTrue(queue.claimSteering(false).isEmpty())
        assertEquals(listOf(attachment, text("later")), queue.pendingMessages().map { it.parts })
        queue.resume()
        val resumed = queue.takeNext()!!
        assertEquals(unsaved.id, resumed.id)
        assertEquals(MessageDelivery.NEXT_TURN, resumed.delivery)
        assertEquals(attachment, resumed.parts)
        assertEquals(text("later"), queue.takeNext()!!.parts)
        assertNull(queue.takeNext())
    }

    @Test
    fun `voice and send without answer always wait for a separate turn`() {
        val queue = MessageQueue()
        val reply = CompletableDeferred<String?>()
        queue.openSteering()
        queue.enqueue(text("voice"), reply = reply, delivery = MessageDelivery.STEER)
        queue.enqueue(text("no answer"), answer = false, delivery = MessageDelivery.STEER)
        assertTrue(queue.claimSteering(false).isEmpty())
        assertTrue(queue.takeNext()!!.reply === reply)
        assertFalse(reply.isCompleted)
        assertFalse(queue.takeNext()!!.answer)
    }

    @Test
    fun `new input and editing cannot resume stopped steering`() {
        val queue = MessageQueue()
        queue.openSteering()
        queue.enqueue(text("keep"), delivery = MessageDelivery.STEER)
        val id = queue.state.value.messages.single().id
        queue.pause()
        queue.openSteering()
        queue.enqueue(text("new"), delivery = MessageDelivery.STEER)
        queue.beginEdit(id)
        queue.finishEdit(id, text("edited"))
        assertTrue(queue.state.value.paused)
        assertTrue(queue.claimSteering(false).isEmpty())
        assertNull(queue.takeNext())
    }
}
