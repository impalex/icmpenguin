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

private const val MAX_PORT = 65535

internal fun PortStrategy.resolve(hop: Int): Int = when (this) {
    is PortStrategy.Fixed -> port
    is PortStrategy.Random -> {
        var port: Int
        do {
            port = (min.coerceAtLeast(1)..max.coerceAtMost(MAX_PORT) + 1).random()
        } while (port in exclude)
        port
    }

    is PortStrategy.Sequential -> start + (hop - 1) * step
}