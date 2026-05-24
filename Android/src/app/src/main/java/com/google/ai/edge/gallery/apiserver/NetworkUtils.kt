/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.apiserver

import java.net.Inet4Address
import java.net.NetworkInterface

internal object NetworkUtils {
  /**
   * Returns the first non-loopback IPv4 address bound to a network interface.
   *
   * Prefers interfaces whose name starts with "wlan" (WiFi) so that ESP32 / phone-tethered
   * devices on the same WLAN get an address they can actually reach. Falls back to any
   * non-loopback IPv4 address, then to "127.0.0.1".
   */
  fun getLanIpAddress(): String {
    val candidates = mutableListOf<Pair<String, String>>()
    try {
      val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
      for (nif in interfaces) {
        if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue
        val name = nif.name?.lowercase().orEmpty()
        for (addr in nif.inetAddresses) {
          if (addr is Inet4Address && !addr.isLoopbackAddress) {
            candidates.add(name to addr.hostAddress.orEmpty())
          }
        }
      }
    } catch (_: Exception) {
      return "127.0.0.1"
    }
    return candidates.firstOrNull { it.first.startsWith("wlan") }?.second
      ?: candidates.firstOrNull { it.first.startsWith("eth") }?.second
      ?: candidates.firstOrNull()?.second
      ?: "127.0.0.1"
  }
}
