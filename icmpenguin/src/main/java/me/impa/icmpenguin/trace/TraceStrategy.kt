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

/**
 * Defines the strategy for sending trace probes.
 *
 * This sealed interface represents different approaches to how trace probes are dispatched
 * and managed during a network trace operation.
 */
sealed interface TraceStrategy {
    /**
     * Stepped strategy for tracing.
     *
     * This strategy sends a fixed number of probes for each hop, with a defined concurrency level,
     * up to a maximum number of hops.
     *
     * @property probesPerHop The number of probes to send for each hop.
     * @property concurrency The number of probes that can be sent concurrently.
     * @property maxHops The maximum number of hops to trace.
     */
    data class Stepped(
        val probesPerHop: Int = DEFAULT_PROBES_PER_HOP,
        val concurrency: Int = DEFAULT_CONCURRENCY,
        val maxHops: Int = DEFAULT_MAX_HOPS,
    ) : TraceStrategy {
        companion object {
            const val DEFAULT_PROBES_PER_HOP = 3
            const val DEFAULT_CONCURRENCY = 5
            const val DEFAULT_MAX_HOPS = 30
        }
    }

    /**
     * Concurrent tracing strategy where probes are sent to all hops simultaneously.
     *
     * This strategy sends out probes to all potential hops (up to `maxHops`) at the same time.
     * The process is repeated for a specified number of `cycles`.
     * If `cycles` is set to `INFINITE`, it will keep sending probes at the specified `interval` indefinitely.
     *
     * @property cycles The number of times to send a set of probes. Use `INFINITE` for continuous operation.
     * @property interval The time interval in milliseconds between sending sets of probes.
     * @property maxHops The maximum number of hops to probe.
     */
    data class Concurrent(
        val cycles: Int = DEFAULT_CYCLES,
        val interval: Long = DEFAULT_INTERVAL,
        val maxHops: Int = DEFAULT_MAX_HOPS,
    ) : TraceStrategy {
        companion object {
            const val DEFAULT_INTERVAL = 1000L
            const val DEFAULT_MAX_HOPS = 30
            const val DEFAULT_CYCLES = 5
            const val INFINITE = -1
        }
    }
}