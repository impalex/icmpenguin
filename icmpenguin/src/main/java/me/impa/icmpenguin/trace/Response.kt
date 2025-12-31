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
 * Represents the response from a trace operation.
 *
 * This sealed interface can be either a [Success] or an [Error].
 */
sealed interface Response {
    /**
     * Represents a successful response from a traceroute operation.
     *
     * @property timeUsec The round-trip time in microseconds.
     * @property mtu The Maximum Transmission Unit (MTU) size for the path.
     */
    data class Success(val timeUsec: Int, val mtu: Int) : Response
    /**
     * Represents an error response from a traceroute operation.
     */
    data object Error : Response
}