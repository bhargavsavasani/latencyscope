@file:Suppress("UNUSED_PARAMETER", "unused")

package io.github.latencyscope.ktor

import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.createClientPlugin

/**
 * No-op mirror of [LatencyScopeKtorConfig].
 *
 * The flag is still settable so an `install(LatencyScopeKtorPlugin) { ... }`
 * block in shared code keeps compiling; nothing reads it.
 */
public class LatencyScopeKtorConfig {
    public var deferToEngine: Boolean = false
}

/**
 * No-op mirror of the real plugin.
 *
 * Still a real `ClientPlugin`, because `install(...)` demands one, but it
 * registers no hooks at all. A plugin with no handlers adds nothing to the
 * request pipeline, so an installed-but-inert plugin costs a client one entry
 * in its attribute map at construction and nothing per call.
 */
public val LatencyScopeKtorPlugin: ClientPlugin<LatencyScopeKtorConfig> =
    createClientPlugin("LatencyScope", ::LatencyScopeKtorConfig) {
        // No Send hook: the exchange is not wrapped and nothing is timed.
    }
