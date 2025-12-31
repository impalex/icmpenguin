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

import me.impa.icmpenguin.ProbeResult

/**
 * Represents the status of a single hop in a traceroute operation.
 *
 * This data class encapsulates information about a specific hop, including its number,
 * the IP addresses encountered at this hop, the results of individual probes sent to this hop,
 * and whether this hop is the final destination.
 *
 * @property num The hop number (e.g., 1 for the first hop, 2 for the second, etc.).
 * @property ips A set of unique IP addresses identified at this hop. It's a set because
 *               multiple probes to the same hop might return different IP addresses due to
 *               load balancing or other network configurations.
 * @property probes A list of [Response] objects, where each object represents the outcome
 *                  of a single probe sent to this hop. This allows for tracking the success
 *                  or failure and timing of individual probe attempts.
 * @property isLast A boolean flag indicating whether this hop is the final destination
 *                  of the traceroute. True if it is the last hop, false otherwise.
 */
data class HopStatus(
    val num: Int,
    val ips: Set<String> = emptySet(),
    val probes: List<Response> = emptyList(),
    val isLast: Boolean = false
)

private fun HopStatus.addInfo(ip: String?, result: Response, isLast: Boolean): HopStatus {
    return this.copy(
        ips = ip?.let { this.ips + ip } ?: this.ips,
        probes = this.probes + result,
        isLast = isLast
    )
}

internal fun HopStatus.addProbeResult(result: ProbeResult, calcMtu: Boolean, isLast: Boolean): HopStatus {
    return when (result) {
        is ProbeResult.ConnectionRefused -> addInfo(
            result.offender, Response.Success(
                result.elapsedUsec,
                if (calcMtu) result.probeSize + result.overhead else 0
            ), isLast
        )

        is ProbeResult.HostUnreachable -> addInfo(
            result.offender, Response.Success(
                result.elapsedUsec,
                if (calcMtu) result.probeSize + result.overhead else 0
            ), isLast
        )

        is ProbeResult.NetError -> addInfo(result.offender, Response.Error, isLast)
        is ProbeResult.NetUnreachable -> addInfo(
            result.offender, Response.Success(
                result.elapsedUsec,
                if (calcMtu) result.probeSize + result.overhead else 0
            ), isLast
        )

        is ProbeResult.Unknown -> addInfo(null, Response.Error, isLast)
        is ProbeResult.Success -> addInfo(
            result.remote, Response.Success(
                result.elapsedUsec,
                if (calcMtu) result.probeSize + result.overhead else 0
            ), isLast
        )

        is ProbeResult.Timeout -> addInfo(null, Response.Error, isLast)
    }
}

