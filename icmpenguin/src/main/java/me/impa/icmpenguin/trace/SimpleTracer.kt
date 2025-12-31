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

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.impa.icmpenguin.ProbeResult
import me.impa.icmpenguin.ProbeType

/**
 * A simplified tracer that provides a higher-level abstraction over the [Tracer] class.
 *
 * This class encapsulates the logic for managing hop states and provides a convenient way
 * to perform trace operations with sensible defaults.
 *
 * @property host The target host for the trace operation.
 * @property probeType The type of probe to use (e.g., ICMP, UDP). Defaults to [ProbeType.ICMP].
 * @property timeout The timeout for each probe in milliseconds. Defaults to [DEFAULT_TIMEOUT].
 * @property maxHops The maximum number of hops to trace. Defaults to 30.
 * @property probesPerHop The number of probes to send for each hop. Defaults to 3.
 * @property concurrency The number of concurrent probes to send. Defaults to 5.
 * @property portStrategy The strategy for selecting ports when using UDP probes.
 *  Defaults to [PortStrategy.Sequential].
 * @property probeSize The size of the probe packets. Defaults to [ProbeSize.MtuDiscovery]
 */
@Suppress("LongParameterList")
class SimpleTracer(
    val host: String,
    val probeType: ProbeType = ProbeType.ICMP,
    val timeout: Int = DEFAULT_TIMEOUT,
    val maxHops: Int = 30,
    val probesPerHop: Int = 3,
    val concurrency: Int = 5,
    val portStrategy: PortStrategy = PortStrategy.Sequential(),
    val probeSize: ProbeSize = ProbeSize.MtuDiscovery,
    ) {

    private val semaphore = Semaphore(1)

    /**
     * Executes the trace operation and invokes the provided callback with the status of each hop.
     *
     * The trace operation sends probes to the target host, incrementing the time-to-live (TTL)
     * value for each hop. The callback is invoked asynchronously as each hop is processed,
     * providing real-time updates on the trace progress.
     *
     * The `cutoff` mechanism is used to optimize the trace by stopping further probes beyond
     * a hop that has successfully reached the destination or encountered a definitive error
     * (e.g., connection refused, host unreachable where the remote host is the offender).
     *
     * @param callback A suspend function that will be invoked for each hop with its [HopStatus].
     *                 The callback is responsible for handling the hop status, such as displaying it
     *                 to the user or logging it.
     */
    @Suppress("ComplexCondition")
    suspend fun trace(
        callback: suspend (HopStatus) -> Unit
    ) {
        val state = sortedMapOf<Int, HopStatus>()
        var cutoff = Int.MAX_VALUE
        val tracer = Tracer(
            host = host,
            probeType = probeType,
            traceStrategy = TraceStrategy.Stepped(
                probesPerHop = probesPerHop,
                maxHops = maxHops,
                concurrency = concurrency
            ),
            timeout = timeout,
            probeSize = probeSize,
            portStrategy = portStrategy
        )
        tracer.trace { hop, result ->
            semaphore.withPermit {
                if (result is ProbeResult.Success
                    || result is ProbeResult.ConnectionRefused
                    || (result is ProbeResult.HostUnreachable && result.offender == result.remote)
                ) {
                    cutoff = hop
                    state.filter { it.key > hop }.forEach { state.remove(it.key) }
                }
                if (hop <= cutoff) {
                    val hopStatus = state.getOrPut(hop) { HopStatus(num = hop) }
                    hopStatus.addProbeResult(result, probeSize is ProbeSize.MtuDiscovery, hop == cutoff).let {
                        state[hop] = it
                        callback(it)
                    }
                }
            }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT = 5000
    }
}