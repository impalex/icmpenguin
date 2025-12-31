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

sealed interface ProbeResult {
    val sequence: Int
    val remote: String
    val probeSize: Int
    val overhead: Int

    /**
     * Represents a successful probe.
     *
     * @property sequence The sequence number of the packet.
     * @property remote The remote host's IP address.
     * @property probeSize The size of the probe packet.
     * @property overhead The overhead of the probe packet.
     * @property elapsedUsec The time elapsed for the probe in microseconds.
     * @property ttl The Time To Live value from the received packet.
     * @property data The data payload received in the reply.
     */
    data class Success(
        override val sequence: Int,
        override val remote: String,
        override val probeSize: Int,
        override val overhead: Int,
        val elapsedUsec: Int,
        val ttl: Int, val data: ByteArray
    ) : ProbeResult {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Success

            if (sequence != other.sequence) return false
            if (elapsedUsec != other.elapsedUsec) return false
            if (ttl != other.ttl) return false
            if (remote != other.remote) return false
            if (!data.contentEquals(other.data)) return false
            if (probeSize != other.probeSize) return false
            if (overhead != other.overhead) return false

            return true
        }

        override fun hashCode(): Int {
            var result = sequence
            result = 31 * result + elapsedUsec
            result = 31 * result + ttl
            result = 31 * result + remote.hashCode()
            result = 31 * result + data.contentHashCode()
            result = 31 * result + probeSize
            result = 31 * result + overhead
            return result
        }
    }

    /**
     * Represents a timeout event for a probe.
     *
     * This class indicates that a response was not received within the expected time frame.
     *
     * @property sequence The sequence number of the probe.
     * @property remote The remote host address.
     * @property probeSize The size of the probe packet.
     * @property overhead The overhead of the probe packet.
     */
    data class Timeout(
        override val sequence: Int, override val remote: String,
        override val probeSize: Int, override val overhead: Int
    ) : ProbeResult

    /**
     * Represents a connection refused error during a probe.
     *
     * This error typically indicates that the target host actively rejected the connection attempt.
     *
     * @property sequence The sequence number of the probe.
     * @property remote The remote host address.
     * @property probeSize The size of the probe packet.
     * @property overhead The overhead of the probe packet.
     * @property offender The IP address of the host that reported the connection refused error.
     * @property elapsedUsec The time elapsed in microseconds until the error was received.
     */
    data class ConnectionRefused(
        override val sequence: Int, override val remote: String,
        override val probeSize: Int, override val overhead: Int, val offender: String, val elapsedUsec: Int
    ) : ProbeResult

    /**
     * Represents a "Host Unreachable" ICMP error.
     *
     * This error indicates that the destination host is unreachable.
     *
     * @property sequence The sequence number of the probe.
     * @property remote The remote host address.
     * @property probeSize The size of the probe packet.
     * @property overhead The overhead of the probe packet.
     * @property offender The IP address of the host that reported the error.
     * @property elapsedUsec The time elapsed in microseconds until the error was received.
     */
    data class HostUnreachable(
        override val sequence: Int, override val remote: String,
        override val probeSize: Int, override val overhead: Int, val offender: String, val elapsedUsec: Int
    ) : ProbeResult

    /**
     * Represents a network unreachable error encountered during a probe.
     *
     * This typically occurs when a router along the path determines that the destination network is not reachable.
     *
     * @property sequence The sequence number of the probe.
     * @property remote The remote host address.
     * @property probeSize The size of the probe packet.
     * @property overhead The overhead of the probe packet.
     * @property offender The IP address of the host (usually a router) that reported the network as unreachable.
     * @property elapsedUsec The time elapsed in microseconds until the error was received.
     */
    data class NetUnreachable(
        override val sequence: Int, override val remote: String,
        override val probeSize: Int, override val overhead: Int, val offender: String, val elapsedUsec: Int
    ) : ProbeResult

    /**
     * Represents a network error that occurred during probing.
     *
     * This class encapsulates details about a network error, such as the error number,
     * error code, and error type, along with the sequence number, remote host, and offending host.
     *
     * @property sequence The sequence number of the probe.
     * @property remote The remote host being probed.
     * @property probeSize The size of the probe packet.
     * @property overhead The overhead of the probe packet.
     * @property offender The host that reported the error.
     * @property errNo The error number.
     * @property errCode The error code.
     * @property errType The error type.
     * @property errInfo Additional information about the error.
     */
    data class NetError(
        override val sequence: Int, override val remote: String, override val probeSize: Int,
        override val overhead: Int, val offender: String,
        val errNo: Int, val errCode: Int, val errType: Int, val errInfo: Int
    ) : ProbeResult

    /**
     * Represents an unknown error that occurred during the probe.
     *
     * @property sequence The sequence number of the probe.
     * @property remote The remote host to which the probe was sent.
     * @property probeSize The size of the probe packet.
     * @property overhead The overhead of the probe packet.
     * @property error A string describing the unknown error.
     */
    data class Unknown(
        override val sequence: Int, override val remote: String, override val probeSize: Int,
        override val overhead: Int, val error: String
    ) : ProbeResult
}

