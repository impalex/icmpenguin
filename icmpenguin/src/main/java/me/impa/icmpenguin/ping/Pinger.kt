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

package me.impa.icmpenguin.ping

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.impa.icmpenguin.ProbeManager
import me.impa.icmpenguin.ProbeResult
import me.impa.icmpenguin.ProbeType
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Provides functionality to send ICMP echo requests to a specified host.
 *
 * This class handles the entire lifecycle of a ping session, from resolving the hostname
 * to sending ICMP packets and reporting the results. It operates asynchronously using
 * coroutines.
 *
 * Example usage:
 * ```kotlin
 * val pinger = Pinger(host = "google.com", maxPingCount = 4)
 * CoroutineScope(Dispatchers.IO).launch {
 *     pinger.ping { result ->
 *         println("Ping result: $result")
 *     }
 * }
 * ```
 *
 * @property host The hostname or IP address to ping.
 * @property ttl Time To Live for the ICMP packets. A value of `-1` (default) allows the system to use its default TTL.
 * @property timeout Timeout in milliseconds for each ping request.
 * @property maxPingCount Maximum number of ping requests to send. Use [INFINITE] for continuous pinging.
 * @property interval Interval in milliseconds between sending each ping request.
 * @property probeSize The size of the ICMP packet's data payload in bytes.
 * @property pattern An optional byte array to use as the data payload. If null, a zero-filled byte array of `probeSize` will be used.
 * @property sourceIp The source IP address to use for sending packets. If empty, the system will choose automatically.
 */
@Suppress("LongParameterList")
class Pinger(
    val host: String,
    val ttl: Int = DEFAULT_TTL,
    val timeout: Int = DEFAULT_TIMEOUT,
    val maxPingCount: Int = DEFAULT_PING_COUNT,
    val interval: Int = DEFAULT_INTERVAL,
    val probeSize: Int = DEFAULT_PROBE_SIZE,
    val pattern: ByteArray? = null,
    val sourceIp: String = ""
) {

    private val _isActive = AtomicBoolean(false)

    /**
     * Starts the ping process.
     *
     * This function initiates the sending of ICMP echo requests to the configured host.
     * It handles the creation and configuration of the probe, and sends ping requests
     * repeatedly according to the specified parameters. The results of each ping are
     * delivered via the provided callback.
     *
     * The ping process will continue until `maxPingCount` is reached, or indefinitely
     * if `maxPingCount` is set to [INFINITE], or if the coroutine is cancelled.
     *
     * If the ping process is already active, this function will return immediately.
     *
     * @param callback A lambda function that will be invoked with the [ProbeResult] for each ping attempt.
     */
    suspend fun ping(callback: (ProbeResult) -> Unit) {
        if (_isActive.get())
            return
        _isActive.set(true)
        try {
            withContext(Dispatchers.IO) {
                val address = InetAddress.getByName(host)
                ProbeManager(requireNotNull(address.hostAddress), sourceIp).use { manager ->
                    var pingCount = 0
                    while (_isActive.get() && (pingCount++ < maxPingCount || maxPingCount == INFINITE)) {
                        launch {
                            manager.sendProbe(
                                ProbeType.ICMP,
                                0,
                                pingCount,
                                ttl,
                                timeout,
                                probeSize,
                                false,
                                pattern ?: ByteArray(probeSize)
                            ) { callback(it) }
                        }.join()
                        delay(interval.toLong())
                    }
                    manager.waitForCompletion()
                }
            }
        } finally {
            _isActive.set(false)
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT = 5000
        const val DEFAULT_PROBE_SIZE = 32
        const val INFINITE = -1
        const val DEFAULT_INTERVAL = 1000
        const val DEFAULT_PING_COUNT = 4
        const val DEFAULT_TTL = -1
    }
}