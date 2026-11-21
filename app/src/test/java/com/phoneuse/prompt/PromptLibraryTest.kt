package com.phoneuse.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptLibraryTest {
    private val now = 1_700_000_000_000L

    private fun prompt(
        id: String,
        profileId: String = PromptProfiles.GENERAL,
        name: String = id,
        content: String = "content of $id",
        enabled: Boolean = true,
        isDefault: Boolean = false,
    ) = SystemPrompt(id, profileId, name, "", content, enabled, isDefault, createdAt = now, updatedAt = now)

    @Test fun defaultsHaveThreeProfilesAndAnActivePromptEach() {
        val lib = PromptDefaults.library(now)
        assertEquals(3, lib.profiles.size)
        assertEquals(listOf(PromptProfiles.GENERAL, PromptProfiles.FACEBOOK, PromptProfiles.CUSTOM), lib.profiles.map { it.id })
        for (p in lib.profiles) {
            assertTrue(p.builtIn)
            val active = lib.activePrompt(p.id)
            assertNotNull(active)
            assertTrue(active!!.enabled)
            assertTrue(active.isDefault)
        }
    }

    @Test fun defaultPromptsFollowTheUserLanguageAndNeverDrift() {
        for (id in PromptProfiles.BUILT_IN) {
            val content = PromptDefaults.content(id)
            assertTrue(id, "language of the user's" in content)
            assertTrue(id, "Never switch to an unrelated language" in content)
        }
    }

    @Test fun createEditAndDeleteCustomPrompt() {
        var lib = PromptDefaults.library(now)
        lib = lib.upsert(prompt("p1", name = "Mine"))
        assertEquals("Mine", lib.prompts.first { it.id == "p1" }.name)
        lib = lib.upsert(prompt("p1", name = "Renamed", content = "new text"))
        assertEquals(1, lib.prompts.count { it.id == "p1" })
        assertEquals("new text", lib.prompts.first { it.id == "p1" }.content)
        lib = lib.delete("p1")
        assertNull(lib.prompts.firstOrNull { it.id == "p1" })
    }

    @Test fun theBuiltInDefaultCannotBeDeletedButCanBeDisabled() {
        var lib = PromptDefaults.library(now)
        val defaultId = lib.activePrompt(PromptProfiles.GENERAL)!!.id
        assertTrue(lib.prompts.first { it.id == defaultId }.isDefault)
        assertEquals(lib, lib.delete(defaultId))

        lib = lib.setEnabled(defaultId, false)
        val active = lib.activePrompt(PromptProfiles.GENERAL)
        // A disabled default is never sent; with no other prompt the profile reports none rather than a disabled one.
        assertTrue(active == null || active.enabled)
    }

    @Test fun selectingADisabledPromptFallsBackToAnEnabledOne() {
        var lib = PromptDefaults.library(now)
        lib = lib.upsert(prompt("p1", name = "First"))
        lib = lib.upsert(prompt("p2", name = "Second"))
        lib = lib.setActive(PromptProfiles.GENERAL, "p2")
        assertEquals("p2", lib.activePrompt(PromptProfiles.GENERAL)!!.id)
        lib = lib.setEnabled("p2", false)
        assertNotEquals("p2", lib.activePrompt(PromptProfiles.GENERAL)!!.id)
    }

    @Test fun duplicateKeepsTheOriginalAndGetsAUniqueName() {
        var lib = PromptDefaults.library(now)
        lib = lib.upsert(prompt("p1", name = "Copy me", content = "long\ntext"))
        lib = lib.duplicate("p1", now, "p1-copy")
        assertEquals(2, lib.prompts.count { it.name.startsWith("Copy me") })
        val copy = lib.prompts.first { it.id == "p1-copy" }
        assertFalse(copy.isDefault)
        assertEquals("long\ntext", copy.content)
    }

    @Test fun setDefaultMovesTheFlagAndRestoreBringsTheOriginalTextBack() {
        var lib = PromptDefaults.library(now)
        lib = lib.upsert(prompt("p1", name = "My default"))
        lib = lib.setDefault(PromptProfiles.GENERAL, "p1")
        assertEquals(1, lib.prompts.count { it.profileId == PromptProfiles.GENERAL && it.isDefault })
        assertEquals("p1", lib.prompts.first { it.profileId == PromptProfiles.GENERAL && it.isDefault }.id)
        assertEquals("p1", lib.activePrompt(PromptProfiles.GENERAL)!!.id)

        // Edit the profile's default text, then restore it.
        val builtIn = lib.prompts.first { it.profileId == PromptProfiles.GENERAL && it.id.startsWith("default-") }
        lib = lib.upsert(builtIn.copy(content = "edited"))
        assertTrue(lib.prompts.any { it.content == "edited" })
        lib = lib.restoreDefault(PromptProfiles.GENERAL, now, "fresh")
        val restored = lib.prompts.first { it.profileId == PromptProfiles.GENERAL && it.isDefault }
        assertEquals(PromptDefaults.content(PromptProfiles.GENERAL), restored.content)
    }

    @Test fun profilesKeepTheirOwnSelectionWhenSwitching() {
        var lib = PromptDefaults.library(now)
        lib = lib.upsert(prompt("fb", profileId = PromptProfiles.FACEBOOK, name = "FB custom"))
        lib = lib.setActive(PromptProfiles.FACEBOOK, "fb")
        lib = lib.copy(activeProfileId = PromptProfiles.GENERAL)
        assertEquals("fb", lib.activePrompt(PromptProfiles.FACEBOOK)!!.id)
        assertEquals(PromptProfiles.GENERAL, lib.activePrompt()!!.profileId)

        lib = lib.copy(activeProfileId = PromptProfiles.FACEBOOK)
        assertEquals("FB custom", lib.activePrompt()!!.name)
    }

    @Test fun customProfilesCanBeAddedRenamedAndDeletedWithTheirPrompts() {
        var lib = PromptDefaults.library(now)
        lib = lib.addProfile("Work", now, "profile-work", "prompt-work")
        val work = lib.profile("profile-work")!!
        assertFalse(work.builtIn)
        assertEquals("Work", work.name)
        assertEquals("prompt-work", lib.activePrompt("profile-work")!!.id)
        // A duplicate name is refused rather than creating two "Work" profiles.
        assertEquals(lib, lib.addProfile("work", now, "profile-two", "prompt-two"))

        lib = lib.renameProfile("profile-work", "Office")
        assertEquals("Office", lib.profile("profile-work")!!.name)

        lib = lib.deleteProfile("profile-work")
        assertNull(lib.profile("profile-work"))
        assertTrue(lib.prompts.none { it.profileId == "profile-work" })
        // Built-in profiles cannot be removed.
        assertEquals(lib, lib.deleteProfile(PromptProfiles.GENERAL))
    }

    @Test fun repairedLibraryDropsDanglingSelections() {
        val lib = PromptDefaults.library(now).copy(
            activeProfileId = "gone",
            activePromptByProfile = mapOf(PromptProfiles.GENERAL to "missing"),
        ).repaired()
        assertEquals(PromptProfiles.GENERAL, lib.activeProfileId)
        assertNotNull(lib.activePrompt(PromptProfiles.GENERAL))
    }

    @Test fun importedPromptIsNeverProtectedAndNeverSilentlyOverwrites() {
        val text = PromptTransfer.exportText(prompt("p1", name = "My prompt", content = "Line one\nLine two"))
        val imported = PromptTransfer.importPrompt(text, "my-prompt.txt", PromptProfiles.GENERAL, now)
        assertEquals("My prompt", imported.name)
        assertEquals("Line one\nLine two", imported.content)
        assertFalse(imported.isDefault)
        assertTrue(imported.enabled)

        val plain = PromptTransfer.importPrompt("just text\nwith lines", "notes.txt", PromptProfiles.CUSTOM, now)
        assertEquals("notes", plain.name)
        assertEquals("just text\nwith lines", plain.content)
    }
}
