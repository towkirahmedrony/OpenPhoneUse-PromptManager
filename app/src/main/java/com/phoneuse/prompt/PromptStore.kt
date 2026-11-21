package com.phoneuse.prompt

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.promptDataStore by preferencesDataStore(name = "prompts")

/**
 * System prompts, kept on this device only.
 *
 * The whole library is one JSON document in a private DataStore file (`prompts.preferences_pb`): no server, no
 * account, no cloud and no new dependency. A missing, empty or corrupted document falls back to the built-in
 * defaults instead of failing a run, and a bad document is never overwritten silently until the next edit.
 */
class PromptStore internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.promptDataStore)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    val library: Flow<PromptLibrary> = store.data
        .map { prefs -> decode(prefs[KEY]) }
        .catch { e ->
            Log.w(TAG, "reading prompts failed; using defaults", e)
            emit(PromptDefaults.library())
        }

    /** The prompts as they are now; never null, never an unreadable document. */
    suspend fun snapshot(): PromptLibrary = try {
        decode(store.data.first()[KEY])
    } catch (e: Exception) {
        Log.w(TAG, "reading prompts failed; using defaults", e)
        PromptDefaults.library()
    }

    /** The prompt the next model request uses: the selected prompt of the active profile. */
    suspend fun activePrompt(): SystemPrompt {
        val lib = snapshot().repaired()
        val profileId = lib.activeProfileId
        return lib.activePrompt() ?: PromptDefaults.defaultPrompt(
            profileId,
            now = 0L,
            id = "default-$profileId",
        )
    }

    /** The active profile's prompt content, ready for the system prompt's first layer. */
    suspend fun activeContent(): String = activePrompt().content

    /**
     * Writes a change. [block] receives the current library (defaults when the stored document is unreadable) and
     * returns the new one; the result is repaired so a profile always points at a prompt that can actually run.
     */
    suspend fun update(block: (PromptLibrary) -> PromptLibrary) {
        store.edit { prefs ->
            val current = decode(prefs[KEY])
            val next = block(current).repaired()
            prefs[KEY] = encode(next)
        }
    }

    suspend fun create(prompt: SystemPrompt) = update { lib -> lib.upsert(prompt).repaired() }

    suspend fun save(prompt: SystemPrompt) = update { lib -> lib.upsert(prompt.copy(updatedAt = System.currentTimeMillis())) }

    suspend fun delete(promptId: String) = update { lib -> lib.delete(promptId) }

    suspend fun duplicate(promptId: String, now: Long = System.currentTimeMillis()) = update { lib ->
        lib.duplicate(promptId, now, PromptDefaults.newId("prompt", now))
    }

    suspend fun setEnabled(promptId: String, enabled: Boolean) = update { lib -> lib.setEnabled(promptId, enabled) }

    suspend fun setActive(profileId: String, promptId: String) = update { lib -> lib.setActive(profileId, promptId) }

    suspend fun setDefault(profileId: String, promptId: String) = update { lib -> lib.setDefault(profileId, promptId) }

    suspend fun restoreDefault(profileId: String, now: Long = System.currentTimeMillis()) = update { lib ->
        lib.restoreDefault(profileId, now, PromptDefaults.newId("prompt", now))
    }

    suspend fun setActiveProfile(profileId: String) = update { lib ->
        if (lib.profiles.any { it.id == profileId }) lib.copy(activeProfileId = profileId) else lib
    }

    suspend fun addProfile(name: String, now: Long = System.currentTimeMillis()) = update { lib ->
        lib.addProfile(name, now, PromptDefaults.newId("profile", now), PromptDefaults.newId("prompt", now))
    }

    suspend fun renameProfile(profileId: String, name: String) = update { lib -> lib.renameProfile(profileId, name) }

    suspend fun deleteProfile(profileId: String) = update { lib -> lib.deleteProfile(profileId) }

    private fun decode(raw: String?): PromptLibrary {
        if (raw.isNullOrBlank()) return PromptDefaults.library()
        return try {
            json.decodeFromString(PromptLibrary.serializer(), raw)
                .takeIf { it.profiles.isNotEmpty() && it.prompts.isNotEmpty() }
                ?.repaired()
                ?: PromptDefaults.library()
        } catch (e: Exception) {
            Log.w(TAG, "stored prompts are unreadable; using defaults", e)
            PromptDefaults.library()
        }
    }

    private fun encode(lib: PromptLibrary): String = json.encodeToString(PromptLibrary.serializer(), lib)

    private companion object {
        const val TAG = "PromptStore"
        val KEY = stringPreferencesKey("library_json")
    }
}

/** Reading and writing a prompt as a plain .txt file, without touching anything else on disk. */
object PromptTransfer {
    /** The text written to a .txt export: the prompt itself, with its name and description as comment lines. */
    fun exportText(prompt: SystemPrompt): String = buildString {
        append("# ").append(prompt.name).append('\n')
        if (prompt.description.isNotBlank()) append("# ").append(prompt.description).append('\n')
        append("# Profile: ").append(prompt.profileId).append('\n')
        append("# Updated: ").append(prompt.updatedAt).append('\n')
        append('\n')
        append(prompt.content)
    }

    /**
     * Turns an imported file into a prompt in [profileId]. Leading `#` lines written by [exportText] are read back
     * as the name and description; any other text is the prompt content, unchanged.
     */
    fun importPrompt(text: String, fileName: String, profileId: String, now: Long): SystemPrompt {
        val lines = text.replace("\r\n", "\n").split('\n')
        val baseName = fileName.substringBeforeLast('.').trim().ifEmpty { "Imported prompt" }
        val meta = mutableListOf<String>()
        var index = 0
        while (index < lines.size && lines[index].startsWith("#")) {
            meta += lines[index].removePrefix("#").trim()
            index++
        }
        // Drop the blank separator line after the header.
        if (index < lines.size && lines[index].isBlank()) index++
        val name = meta.firstOrNull { it.isNotBlank() }?.take(80) ?: baseName.take(80)
        val description = meta.drop(1)
            .firstOrNull { it.isNotBlank() && !it.startsWith("Profile:") && !it.startsWith("Updated:") }
            ?.take(200)
            .orEmpty()
        val content = lines.drop(index).joinToString("\n").trim('\n').ifBlank { text.trim() }
        return SystemPrompt(
            id = PromptDefaults.newId("prompt", now),
            profileId = profileId,
            name = name,
            description = description.ifBlank { "Imported from $fileName" },
            content = content,
            enabled = true,
            isDefault = false,
            createdAt = now,
            updatedAt = now,
        )
    }
}
