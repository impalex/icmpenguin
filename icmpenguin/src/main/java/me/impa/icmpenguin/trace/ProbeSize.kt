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
 * Defines the size of the probe packets used in traceroute.
 *
 * This sealed interface allows for specifying either a fixed static size for the probes
 * or opting for MTU (Maximum Transmission Unit) discovery to determine the optimal probe size.
 */
sealed interface ProbeSize {
    /**
     * Represents a static probe size.
     *
     * @property size The size of the probe in bytes.
     */
    data class Static(val size: Int) : ProbeSize
    /**
     * Represents a probe size mode where the probe size is dynamically adjusted
     * during the trace to discover the Path MTU (Maximum Transmission Unit).
     * This is typically used in conjunction with setting the Don't Fragment (DF)
     * bit in the IP header. The tracer will start with large probe packets.
     * Upon receiving an EMSGSIZE error (or an ICMP "Fragmentation Needed" message),
     * the probe size will be adapted based on the information provided in the error,
     * effectively narrowing down the MTU of the path. This process continues until
     * a stable MTU is determined or a configured limit is reached.
     */
    data object MtuDiscovery : ProbeSize
}