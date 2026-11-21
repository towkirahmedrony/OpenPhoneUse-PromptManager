package com.phoneuse.app

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The "OpenAI-compatible" provider ([AuthMode.COMPATIBLE]): any server that speaks Chat Completions, reached at a
 * base URL the user enters or picks from [PRESETS]. Plain http is allowed only for this phone itself (and the
 * emulator's host), the hosts res/xml/network_security_config.xml lets through; anything else needs https.
 */
object CompatibleEndpoints {
    /** A known server: [baseUrl] ends with a slash; [needsKey] false for local servers that take none. */
    data class Preset(val name: String, val baseUrl: String, val needsKey: Boolean = true)

    val PRESETS = listOf(
        Preset("OpenRouter", "https://openrouter.ai/api/v1/"),
        Preset("Groq", "https://api.groq.com/openai/v1/"),
        Preset("Mistral", "https://api.mistral.ai/v1/"),
        Preset("Together", "https://api.together.xyz/v1/"),
        Preset("DeepSeek", "https://api.deepseek.com/v1/"),
        Preset("Ollama", "http://localhost:11434/v1/", needsKey = false),
        Preset("LM Studio", "http://localhost:1234/v1/", needsKey = false),
    )

    /** The hosts plain http may reach; keep in step with network_security_config.xml. */
    val CLEARTEXT_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

    sealed interface Parsed {
        data class Ok(val baseUrl: String) : Parsed
        data object Invalid : Parsed
        /** http to a host other than [CLEARTEXT_HOSTS]: Android would refuse it, so ask for https. */
        data object NeedsHttps : Parsed
    }

    /**
     * [raw] as a base URL ending with a slash: trimmed, a pasted `…/chat/completions` or `…/models` cut back to
     * the base, query and fragment dropped.
     */
    fun parse(raw: String): Parsed {
        val text = raw.trim()
        if (text.isEmpty()) return Parsed.Invalid
        val url = text.toHttpUrlOrNull() ?: return Parsed.Invalid
        if (url.scheme == "http" && url.host.lowercase() !in CLEARTEXT_HOSTS) return Parsed.NeedsHttps
        val segments = url.pathSegments.filter { it.isNotEmpty() }.toMutableList()
        when {
            segments.takeLast(2) == listOf("chat", "completions") -> repeat(2) { segments.removeAt(segments.lastIndex) }
            segments.lastOrNull() == "models" -> segments.removeAt(segments.lastIndex)
        }
        val path = segments.joinToString("/") { it }
        val base = url.newBuilder().query(null).fragment(null).encodedPath("/" + if (path.isEmpty()) "" else "$path/").build()
        return Parsed.Ok(base.toString())
    }

    /** The host shown in Settings and the drawer ("openrouter.ai", "localhost:11434"). */
    fun displayHost(baseUrl: String): String {
        val url = baseUrl.toHttpUrlOrNull() ?: return baseUrl
        val defaultPort = if (url.scheme == "https") 443 else 80
        return if (url.port == defaultPort) url.host else "${url.host}:${url.port}"
    }
}
