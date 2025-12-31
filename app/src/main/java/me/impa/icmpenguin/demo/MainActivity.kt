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

package me.impa.icmpenguin.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.impa.icmpenguin.ProbeResult
import me.impa.icmpenguin.demo.ui.theme.IcmpenguinTheme
import me.impa.icmpenguin.ping.Pinger
import me.impa.icmpenguin.trace.HopStatus
import me.impa.icmpenguin.trace.SimpleTracer

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            IcmpenguinTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DemoContent(
                        modifier = Modifier
                            .padding(innerPadding)
                            .fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
fun DemoContent(modifier: Modifier = Modifier) {
    var host by rememberSaveable { mutableStateOf("") }
    var results by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var currentAction by rememberSaveable { mutableStateOf<Action?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(key1 = currentAction) {
        when (currentAction) {
            is Action.Ping -> pingAction(host) { results = it }
            is Action.Trace -> traceAction(host) { results = it }
            else -> Unit
        }
        currentAction = null
    }

    Column(modifier = modifier.then(Modifier.padding(8.dp)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Host") },
            modifier = Modifier.fillMaxWidth(),
            enabled = currentAction == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { currentAction = Action.Ping(host) },
                modifier = Modifier.weight(1f),
                enabled = currentAction == null && host.isNotBlank()
            ) {
                Text(text = "Ping")
            }
            Button(
                onClick = { currentAction = Action.Trace(host) },
                modifier = Modifier.weight(1f),
                enabled = currentAction == null && host.isNotBlank()
            ) {
                Text(text = "Traceroute")
            }
            Button(
                onClick = { currentAction = null },
                modifier = Modifier.weight(1f),
                enabled = currentAction != null
            ) {
                Text(text = "Stop")
            }
        }
        LazyColumn(state = listState) {
            items(items = results) {
                Text(text = it)
            }
        }
    }
}

suspend fun pingAction(host: String, onUpdateResults: (List<String>) -> Unit) {
    val packets = mutableListOf<ProbeResult>()
    onUpdateResults(emptyList())
    Pinger(host).ping { probeResult ->
        packets.add(probeResult)
        onUpdateResults(packets.sortedBy { it.sequence }.map { it.toString() })
    }
}

suspend fun traceAction(host: String, onUpdateResults: (List<String>) -> Unit) {
    val hops = mutableMapOf<Int, HopStatus>()
    onUpdateResults(emptyList())
    SimpleTracer(host).trace { hopStatus ->
        hops[hopStatus.num] = hopStatus
        onUpdateResults(hops.values.sortedBy { it.num }.map { it.toString() })
    }
}
