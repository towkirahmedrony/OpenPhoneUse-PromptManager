package com.phoneuse.prompt

import kotlinx.serialization.Serializable

/** The three built-in prompt profiles; users may add more. */
object PromptProfiles {
    const val GENERAL = "general"
    const val FACEBOOK = "facebook"
    const val CUSTOM = "custom"

    /** Ids of the built-in profiles, in the order the picker shows them. */
    val BUILT_IN = listOf(GENERAL, FACEBOOK, CUSTOM)

    fun defaultName(id: String): String = when (id) {
        GENERAL -> "General Assistant"
        FACEBOOK -> "Facebook Automation"
        CUSTOM -> "Custom Agent"
        else -> id
    }
}

/** A group of prompts (General Assistant, Facebook Automation, Custom Agent, or a profile the user added). */
@Serializable
data class PromptProfile(
    val id: String,
    val name: String,
    val builtIn: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/** One editable system prompt. [content] is stored verbatim, newlines included, with no length cap. */
@Serializable
data class SystemPrompt(
    val id: String,
    val profileId: String,
    val name: String,
    val description: String = "",
    val content: String,
    val enabled: Boolean = true,
    /** The profile's original prompt, restored by "Restore default". */
    val isDefault: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/**
 * Everything the Prompt Manager owns, stored as one JSON document in local storage. [activePromptByProfile] is
 * the selected prompt of each profile, so switching profiles keeps every profile's choice.
 */
@Serializable
data class PromptLibrary(
    val version: Int = VERSION,
    val activeProfileId: String = PromptProfiles.GENERAL,
    val profiles: List<PromptProfile> = emptyList(),
    val prompts: List<SystemPrompt> = emptyList(),
    val activePromptByProfile: Map<String, String> = emptyMap(),
) {
    fun profile(id: String): PromptProfile? = profiles.firstOrNull { it.id == id }

    fun promptsOf(profileId: String): List<SystemPrompt> =
        prompts.filter { it.profileId == profileId }.sortedWith(compareByDescending<SystemPrompt> { it.isDefault }.thenBy { it.createdAt })

    /** The prompt selected for [profileId]; a disabled, deleted or missing one falls back to the profile's default. */
    fun activePrompt(profileId: String = activeProfileId): SystemPrompt? {
        val chosen = activePromptByProfile[profileId]?.let { id -> prompts.firstOrNull { it.id == id && it.profileId == profileId } }
        if (chosen != null && chosen.enabled) return chosen
        val own = promptsOf(profileId)
        // Only an enabled prompt is ever sent. When everything in a profile is off, the caller falls back to the
        // profile's built-in default rather than quietly sending a prompt the user disabled.
        return own.firstOrNull { it.isDefault && it.enabled }
            ?: own.firstOrNull { it.enabled }
    }

    /** A prompt that was disabled, deleted or lost: point the profile at one that still works. */
    fun repaired(): PromptLibrary = copy(
        activeProfileId = activeProfileId.takeIf { p -> profiles.any { it.id == p } } ?: PromptProfiles.GENERAL,
        activePromptByProfile = activePromptByProfile.filter { (profileId, promptId) ->
            prompts.any { it.id == promptId && it.profileId == profileId && it.enabled }
        },
    )

    fun upsert(prompt: SystemPrompt): PromptLibrary =
        copy(prompts = prompts.filterNot { it.id == prompt.id } + prompt)

    /** Custom prompts can be deleted; a profile's built-in default stays (it can be disabled instead). */
    fun delete(promptId: String): PromptLibrary {
        val target = prompts.firstOrNull { it.id == promptId } ?: return this
        if (target.isDefault) return this
        return copy(
            prompts = prompts.filterNot { it.id == promptId },
            activePromptByProfile = activePromptByProfile.mapValues { (profileId, id) -> if (id == promptId) "" else id },
        ).repaired()
    }

    fun duplicate(promptId: String, now: Long, newId: String): PromptLibrary {
        val source = prompts.firstOrNull { it.id == promptId } ?: return this
        val copy = source.copy(
            id = newId,
            name = uniqueName(source.profileId, "${source.name} (copy)"),
            isDefault = false,
            enabled = true,
            createdAt = now,
            updatedAt = now,
        )
        return upsert(copy)
    }

    fun setEnabled(promptId: String, enabled: Boolean): PromptLibrary {
        val target = prompts.firstOrNull { it.id == promptId } ?: return this
        return upsert(target.copy(enabled = enabled, updatedAt = System.currentTimeMillis())).repaired()
    }

    fun setActive(profileId: String, promptId: String): PromptLibrary {
        val target = prompts.firstOrNull { it.id == promptId && it.profileId == profileId && it.enabled } ?: return this
        return copy(activePromptByProfile = activePromptByProfile + (profileId to target.id))
    }

    fun setDefault(profileId: String, promptId: String): PromptLibrary {
        val target = prompts.firstOrNull { it.id == promptId && it.profileId == profileId } ?: return this
        val updated = copy(
            prompts = prompts.map {
                when {
                    it.id == target.id -> it.copy(isDefault = true)
                    it.profileId == profileId && it.isDefault -> it.copy(isDefault = false)
                    else -> it
                }
            },
        )
        return updated.copy(activePromptByProfile = updated.activePromptByProfile + (profileId to target.id))
    }

    /**
     * Restores a profile's original default prompt: content, name, description and enabled state.
     *
     * The built-in defaults keep a stable id (`default-<profile>`), so the restore finds them even after another
     * prompt was made the default. A profile the user added falls back to whichever prompt carries the default flag.
     */
    fun restoreDefault(profileId: String, now: Long, newId: String): PromptLibrary {
        val existing = prompts.firstOrNull { it.profileId == profileId && it.id == "default-$profileId" }
            ?: prompts.firstOrNull { it.profileId == profileId && it.isDefault }
        val id = existing?.id ?: newId
        val fresh = PromptDefaults.defaultPrompt(
            profileId,
            now = now,
            id = id,
            name = profile(profileId)?.name ?: PromptProfiles.defaultName(profileId),
        )
        val cleared = prompts.map {
            if (it.profileId == profileId && it.isDefault && it.id != id) it.copy(isDefault = false) else it
        }
        val restored = copy(prompts = cleared.filterNot { it.id == id } + fresh)
        return restored.copy(activePromptByProfile = restored.activePromptByProfile + (profileId to id))
    }

    fun addProfile(name: String, now: Long, profileId: String, promptId: String): PromptLibrary {
        val clean = name.trim().take(60).ifEmpty { "Profile" }
        if (profiles.any { it.name.equals(clean, ignoreCase = true) }) return this
        val profile = PromptProfile(id = profileId, name = clean, builtIn = false, createdAt = now, updatedAt = now)
        val prompt = PromptDefaults.defaultPrompt(profileId, now, promptId, name = clean)
        return copy(
            profiles = profiles + profile,
            prompts = prompts + prompt,
            activePromptByProfile = activePromptByProfile + (profileId to prompt.id),
        )
    }

    fun renameProfile(profileId: String, name: String): PromptLibrary {
        val clean = name.trim().take(60).ifEmpty { return this }
        return copy(profiles = profiles.map { if (it.id == profileId && !it.builtIn) it.copy(name = clean) else it })
    }

    /** A custom profile can be deleted with its prompts; built-in profiles stay. */
    fun deleteProfile(profileId: String): PromptLibrary {
        val p = profiles.firstOrNull { it.id == profileId } ?: return this
        if (p.builtIn) return this
        return copy(
            profiles = profiles.filterNot { it.id == profileId },
            prompts = prompts.filterNot { it.profileId == profileId },
            activePromptByProfile = activePromptByProfile - profileId,
        ).repaired()
    }

    private fun uniqueName(profileId: String, wanted: String): String {
        val taken = promptsOf(profileId).mapTo(HashSet()) { it.name }
        if (wanted !in taken) return wanted
        var n = 2
        while ("$wanted $n" in taken) n++
        return "$wanted $n"
    }

    companion object {
        const val VERSION = 1
    }
}
