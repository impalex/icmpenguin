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
 * Represents a strategy for selecting port numbers.
 *
 * This sealed interface defines different approaches to choosing ports,
 * such as using a fixed port, a sequence of ports, or a random port
 * within a specified range.
 */
sealed interface PortStrategy {
    /**
     * A port strategy that uses a fixed port number.
     *
     * @property port The fixed port number to use.
     */
    data class Fixed(val port: Int = 33434) : PortStrategy
    /**
     * Represents a sequential port strategy.
     *
     * This strategy starts with a given port number and increments it by a specified step for each subsequent hop.
     *
     * @property start The initial port number.
     * @property step The increment value for the port number.
     */
    data class Sequential(val start: Int = 33434, val step: Int = 1) : PortStrategy
    /**
     * A port strategy that selects a random port within a specified range.
     *
     * This strategy generates a random port number between `min` (inclusive) and `max` (inclusive).
     * It also allows for excluding specific port numbers from being selected.
     *
     * @property min The minimum port number in the range (inclusive).
     * @property max The maximum port number in the range (inclusive).
     * @property exclude A set of port numbers to exclude from the random selection.
     */
    data class Random(val min: Int = 1024, val max: Int = 65535, val exclude: Set<Int> = setOf()) : PortStrategy
}