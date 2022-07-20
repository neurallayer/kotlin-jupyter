package org.jetbrains.kotlinx.jupyter.messaging

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.kotlinx.jupyter.api.libraries.Comm
import org.jetbrains.kotlinx.jupyter.api.libraries.CommCloseCallback
import org.jetbrains.kotlinx.jupyter.api.libraries.CommManager
import org.jetbrains.kotlinx.jupyter.api.libraries.CommMsgCallback
import org.jetbrains.kotlinx.jupyter.api.libraries.CommOpenCallback
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

interface CommManagerInternal : CommManager {
    fun processCommOpen(message: Message, content: CommOpen): Comm?
    fun processCommMessage(message: Message, content: CommMsg)
    fun processCommClose(message: Message, content: CommClose)
}

class CommManagerImpl(private val connection: JupyterConnectionInternal) : CommManagerInternal {
    private val iopub get() = connection.iopub

    private val commOpenCallbacks = ConcurrentHashMap<String, CommOpenCallback>()
    private val commTargetToIds = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()
    private val commIdToComm = ConcurrentHashMap<String, CommImpl>()

    override fun openComm(target: String, data: JsonObject): Comm {
        val id = UUID.randomUUID().toString()
        val newComm = registerNewComm(target, id)

        // send comm_open
        iopub.sendSimpleMessage(
            MessageType.COMM_OPEN,
            CommOpen(newComm.id, newComm.target, data)
        )

        return newComm
    }

    override fun processCommOpen(message: Message, content: CommOpen): Comm? {
        val target = content.targetName
        val id = content.commId
        val data = content.data

        val callback = commOpenCallbacks[target]
        if (callback == null) {
            // If no callback is registered, we should send `comm_close` immediately in response.
            iopub.sendSimpleMessage(
                MessageType.COMM_CLOSE,
                CommClose(id, commFailureJson("Target $target was not registered"))
            )
            return null
        }

        val newComm = registerNewComm(target, id)
        try {
            callback(newComm, data)
        } catch (e: Throwable) {
            iopub.sendSimpleMessage(
                MessageType.COMM_CLOSE,
                CommClose(id, commFailureJson("Unable to crete comm $id (with target $target), exception was thrown: ${e.stackTraceToString()}"))
            )
            removeComm(id)
        }

        return newComm
    }

    private fun registerNewComm(target: String, id: String): Comm {
        val commIds = commTargetToIds.getOrPut(target) { CopyOnWriteArrayList() }
        val newComm = CommImpl(target, id)
        commIds.add(id)
        commIdToComm[id] = newComm
        return newComm
    }

    override fun closeComm(id: String, data: JsonObject) {
        val comm = commIdToComm[id] ?: return
        comm.close(data, notifyClient = true)
    }

    override fun processCommClose(message: Message, content: CommClose) {
        val comm = commIdToComm[content.commId] ?: return
        comm.close(content.data, notifyClient = false)
    }

    fun removeComm(id: String) {
        val comm = commIdToComm[id] ?: return
        val commIds = commTargetToIds[comm.target]!!
        commIds.remove(id)
        commIdToComm.remove(id)
    }

    override fun getComms(target: String?): Collection<Comm> {
        return if (target == null) {
            commIdToComm.values.toList()
        } else {
            commTargetToIds[target].orEmpty().mapNotNull { commIdToComm[it] }
        }
    }

    override fun processCommMessage(message: Message, content: CommMsg) {
        commIdToComm[content.commId]?.messageReceived(message, content.data)
    }

    override fun registerCommTarget(target: String, callback: (Comm, JsonObject) -> Unit) {
        commOpenCallbacks[target] = callback
    }

    override fun unregisterCommTarget(target: String) {
        commOpenCallbacks.remove(target)
    }

    inner class CommImpl(
        override val target: String,
        override val id: String
    ) : Comm {

        private val onMessageCallbacks = mutableListOf<CommMsgCallback>()
        private val onCloseCallbacks = mutableListOf<CommCloseCallback>()
        private var closed = false

        private fun assertOpen() {
            if (closed) {
                throw AssertionError("Comm '$target' has been already closed")
            }
        }
        override fun send(data: JsonObject) {
            assertOpen()
            iopub.sendSimpleMessage(
                MessageType.COMM_MSG,
                CommMsg(id, data)
            )
        }

        override fun onMessage(action: CommMsgCallback): CommMsgCallback {
            assertOpen()
            onMessageCallbacks.add(action)
            return action
        }

        override fun removeMessageCallback(callback: CommMsgCallback) {
            onMessageCallbacks.remove(callback)
        }

        override fun onClose(action: CommCloseCallback): CommCloseCallback {
            assertOpen()
            onCloseCallbacks.add(action)
            return action
        }

        override fun removeCloseCallback(callback: CommCloseCallback) {
            onCloseCallbacks.remove(callback)
        }

        override fun close(data: JsonObject, notifyClient: Boolean) {
            assertOpen()
            closed = true
            onMessageCallbacks.clear()

            removeComm(id)

            onCloseCallbacks.forEach { it(data) }

            if (notifyClient) {
                iopub.sendSimpleMessage(
                    MessageType.COMM_CLOSE,
                    CommClose(id, data)
                )
            }
        }

        fun messageReceived(message: Message, data: JsonObject) {
            if (closed) return

            connection.doWrappedInBusyIdle(message) {
                for (callback in onMessageCallbacks) {
                    callback(data)
                }
            }
        }
    }

    companion object {
        private fun commFailureJson(errorMessage: String): JsonObject {
            return JsonObject(
                mapOf(
                    "error" to JsonPrimitive(errorMessage)
                )
            )
        }
    }
}