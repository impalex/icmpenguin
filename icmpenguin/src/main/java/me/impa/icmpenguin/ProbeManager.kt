/*
 * Copyright (c) 2025 Alexander Yaburov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package me.impa.icmpenguin

import android.system.OsConstants.EMSGSIZE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.lang.System.loadLibrary
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

internal class ProbeManager(host: String) : AutoCloseable {

    private val instance: Long

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val callbacks = mutableMapOf<Int, suspend (ProbeResult) -> Unit>()

    private val callbackId = AtomicInteger(0)

    private fun addCallback(callback: suspend (ProbeResult) -> Unit): Int {
        val id = callbackId.getAndIncrement()
        callbacks[id] = callback
        return id
    }

    @Suppress("LongParameterList")
    fun sendProbe(
        type: ProbeType, port: Int, sequence: Int, ttl: Int, timeout: Int,
        size: Int, detectMtu: Boolean, pattern: ByteArray, callback: suspend (ProbeResult) -> Unit
    ) {
        val callbackId = addCallback(
            if (detectMtu) {
                { result ->
                    if (result is ProbeResult.NetError && result.errNo == EMSGSIZE) {
                        scope.launch {
                            sendProbe(
                                type,
                                port,
                                sequence,
                                ttl,
                                timeout,
                                result.errInfo - result.overhead,
                                detectMtu,
                                pattern,
                                callback
                            )
                        }
                        Unit
                    } else callback.invoke(result)
                }
            } else callback)
        sendProbe(
            instance,
            callbackId,
            type.code,
            port,
            sequence,
            ttl,
            timeout,
            size,
            detectMtu,
            pattern
        )
    }

    suspend fun waitForCompletion() {
        while (getQueueSize(instance) > 0) {
            delay(WAIT_RESOLUTION)
        }
    }

    fun getQueueSize(): Int {
        return getQueueSize(instance)
    }

    @Suppress("unused")
    fun probeCallback(probeId: Int, probeResult: ProbeResult) {
        callbacks[probeId]?.also {
            runBlocking(scope.coroutineContext) {
                it(probeResult)
            }
            callbacks.remove(probeId)
        }
    }

    init {
        val address = InetAddress.getByName(host)
        instance = create(requireNotNull(address.hostAddress))
    }

    override fun close() {
        delete(instance)
    }

    @Suppress("unused")
    private external fun create(ip: String): Long

    @Suppress("unused")
    private external fun delete(ptr: Long)

    @Suppress("unused")
    private external fun getQueueSize(ptr: Long): Int

    @Suppress("LongParameterList", "unused")
    private external fun sendProbe(
        ptr: Long, id: Int, type: Int, port: Int, sequence: Int, ttl: Int,
        timeout: Int, size: Int, detectMtu: Boolean, pattern: ByteArray
    ): Int

    companion object {
        const val WAIT_RESOLUTION = 100L

        init {
            loadLibrary("icmpenguin")
        }
    }
}