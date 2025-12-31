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

package me.impa.icmpenguin.trace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.impa.icmpenguin.ProbeManager
import me.impa.icmpenguin.ProbeResult
import me.impa.icmpenguin.ProbeType
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * Class for performing traceroute operations.
 *
 * This class provides functionality to trace the route to a specified host using different probing strategies.
 * It supports both concurrent and stepped tracing methods.
 *
 * @property host The hostname or IP address of the target.
 * @property probeType The type of probe to use for tracing (e.g., ICMP, UDP).
 * @property traceStrategy The strategy to use for sending probes (e.g., [TraceStrategy.Stepped],
 *  [TraceStrategy.Concurrent]). Defaults to [TraceStrategy.Stepped].
 * @property portStrategy The strategy to use for selecting ports when [probeType] is UDP.
 *  Defaults to [PortStrategy.Sequential].
 * @property probeSize The size of the probe packets. Defaults to [ProbeSize.Static] with size [DEFAULT_PROBE_SIZE].
 * @property timeout The timeout for each probe in milliseconds. Defaults to [DEFAULT_TIMEOUT].
 *  The value will be coerced to be within [MIN_TIME_OUT] and [MAX_TIME_OUT].
 */
class Tracer(
    val host: String,
    val probeType: ProbeType,
    val traceStrategy: TraceStrategy = TraceStrategy.Stepped(),
    val portStrategy: PortStrategy = PortStrategy.Sequential(),
    val probeSize: ProbeSize = ProbeSize.Static(size = DEFAULT_PROBE_SIZE),
    val timeout: Int = DEFAULT_TIMEOUT,
) {

    private var cutoff = AtomicInteger(Int.MAX_VALUE)
    private val _isActive = AtomicBoolean(false)

    @Suppress("LongParameterList")
    private suspend fun concurrentTrace(
        ip: String,
        hops: Int,
        cycles: Int,
        interval: Long,
        portStrategy: PortStrategy?,
        probeSize: ProbeSize,
        callback: suspend (Int, ProbeResult) -> Unit
    ) {
        var cycle = 0
        val size = AtomicInteger(if (probeSize is ProbeSize.Static) probeSize.size else MAX_PACKET_SIZE)
        coroutineScope {
            ProbeManager(ip).use { manager ->
                while (_isActive.get() && (cycles == TraceStrategy.Concurrent.INFINITE || cycle < cycles)) {
                    for (hop in 1..hops) {
                        manager.sendProbe(
                            probeType,
                            portStrategy?.resolve(hop) ?: 0,
                            cycle,
                            hop,
                            timeout,
                            size.get(),
                            probeSize is ProbeSize.MtuDiscovery,
                            ByteArray(0)
                        ) {
                            if (it is ProbeResult.Success || it is ProbeResult.ConnectionRefused) {
                                cutoff.set(min(hop, cutoff.get()))
                            }
                            if (probeSize is ProbeSize.MtuDiscovery) {
                                size.getAndUpdate { old -> if (old > it.probeSize) it.probeSize else old }
                            }
                            if (hop <= cutoff.get())
                                callback(hop, it)
                        }
                    }
                    cycle++
                    delay(interval)
                }
            }
        }
    }

    @Suppress("LongParameterList", "LoopWithTooManyJumpStatements")
    private suspend fun steppedTrace(
        ip: String,
        probesPerHop: Int,
        hops: Int,
        concurrency: Int,
        portStrategy: PortStrategy?,
        probeSize: ProbeSize,
        callback: suspend (Int, ProbeResult) -> Unit
    ) {
        val probeCounter = AtomicInteger(0)
        val maxConcurrentProbes = concurrency.coerceAtLeast(1)
        val size = AtomicInteger(if (probeSize is ProbeSize.Static) probeSize.size else MAX_PACKET_SIZE)

        coroutineScope {
            ProbeManager(ip).use { manager ->
                while (_isActive.get()) {
                    if (manager.getQueueSize() > maxConcurrentProbes) {
                        delay(WAIT_RESOLUTION)
                        continue
                    }
                    val currentProbe = probeCounter.getAndIncrement()
                    val currentHop = currentProbe / probesPerHop + 1
                    if (currentHop > min(hops, cutoff.get()))
                        break
                    val port = portStrategy?.resolve(currentHop) ?: 0
                    withContext(Dispatchers.IO) {
                        manager.sendProbe(
                            probeType,
                            port,
                            currentProbe,
                            currentHop,
                            timeout,
                            size.get(),
                            probeSize is ProbeSize.MtuDiscovery,
                            ByteArray(0)
                        ) {
                            if (it is ProbeResult.Success || it is ProbeResult.ConnectionRefused) {
                                cutoff.set(min(currentHop, cutoff.get()))
                            }
                            if (probeSize is ProbeSize.MtuDiscovery) {
                                size.getAndUpdate { old -> if (old > it.probeSize) it.probeSize else old }
                            }
                            if (currentHop <= cutoff.get())
                                callback(currentHop, it)
                        }
                    }
                }
                ensureActive()
                manager.waitForCompletion()
            }
        }
    }

    /**
     * Starts the traceroute operation.
     *
     * This function initiates the traceroute process based on the configured [traceStrategy].
     * The results of each probe are reported through the provided [callback].
     *
     * If a trace operation is already active, this function will return without starting a new one.
     *
     * @param callback A suspend function that will be invoked for each probe result.
     *                 It takes the hop number (Int) and the [ProbeResult] as arguments.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun trace(
        callback: suspend (Int, ProbeResult) -> Unit
    ) {
        if (_isActive.get())
            return
        _isActive.set(true)
        try {
            coroutineScope {
                withContext(Dispatchers.IO) {
                    val address = InetAddress.getByName(host)
                    when (traceStrategy) {
                        is TraceStrategy.Concurrent -> concurrentTrace(
                            requireNotNull(address.hostAddress),
                            traceStrategy.maxHops,
                            traceStrategy.cycles,
                            traceStrategy.interval,
                            portStrategy,
                            probeSize,
                            callback
                        )

                        is TraceStrategy.Stepped -> steppedTrace(
                            requireNotNull(address.hostAddress),
                            traceStrategy.probesPerHop,
                            traceStrategy.maxHops,
                            traceStrategy.concurrency,
                            portStrategy,
                            probeSize,
                            callback
                        )
                    }
                }
            }
        } finally {
            _isActive.set(false)
        }
    }

    companion object {
        private const val WAIT_RESOLUTION = 100L
        private const val MAX_PACKET_SIZE = 65487 // 65535 - 40 (IPv6 IP header) - 8 (UDP header)
        const val DEFAULT_TIMEOUT = 5000
        const val DEFAULT_PROBE_SIZE = 32
        const val MIN_TIME_OUT = 1
        const val MAX_TIME_OUT = 10000
    }
}