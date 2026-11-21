package com.phoneuse.app

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.phoneuse.app.CompatibleEndpoints.Parsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibleEndpointsTest {
    @Test fun aBaseUrlGetsItsTrailingSlash() {
        assertEquals(Parsed.Ok("https://openrouter.ai/api/v1/"), CompatibleEndpoints.parse("  https://openrouter.ai/api/v1 "))
        assertEquals(Parsed.Ok("https://openrouter.ai/api/v1/"), CompatibleEndpoints.parse("https://openrouter.ai/api/v1/"))
    }

    @Test fun aPastedEndpointIsCutBackToTheBase() {
        assertEquals(Parsed.Ok("https://api.groq.com/openai/v1/"), CompatibleEndpoints.parse("https://api.groq.com/openai/v1/chat/completions"))
        assertEquals(Parsed.Ok("https://api.groq.com/openai/v1/"), CompatibleEndpoints.parse("https://api.groq.com/openai/v1/models?x=1#y"))
    }

    @Test fun aHostWithoutAPathIsTheRoot() {
        assertEquals(Parsed.Ok("https://llm.example.com/"), CompatibleEndpoints.parse("https://llm.example.com"))
    }

    @Test fun plainHttpIsOnlyForThisPhone() {
        assertEquals(Parsed.Ok("http://localhost:11434/v1/"), CompatibleEndpoints.parse("http://localhost:11434/v1"))
        assertEquals(Parsed.Ok("http://127.0.0.1:1234/v1/"), CompatibleEndpoints.parse("http://127.0.0.1:1234/v1"))
        assertEquals(Parsed.Ok("http://10.0.2.2:11434/v1/"), CompatibleEndpoints.parse("http://10.0.2.2:11434/v1"))
        assertEquals(Parsed.NeedsHttps, CompatibleEndpoints.parse("http://192.168.0.10:11434/v1"))
        assertEquals(Parsed.NeedsHttps, CompatibleEndpoints.parse("http://openrouter.ai/api/v1"))
    }

    @Test fun nonsenseIsInvalid() {
        assertEquals(Parsed.Invalid, CompatibleEndpoints.parse(""))
        assertEquals(Parsed.Invalid, CompatibleEndpoints.parse("openrouter"))
        assertEquals(Parsed.Invalid, CompatibleEndpoints.parse("ftp://example.com"))
    }

    @Test fun everyPresetParsesToItself() {
        for (p in CompatibleEndpoints.PRESETS) assertEquals(p.name, Parsed.Ok(p.baseUrl), CompatibleEndpoints.parse(p.baseUrl))
    }

    @Test fun theHostShownLeavesOutTheDefaultPort() {
        assertEquals("openrouter.ai", CompatibleEndpoints.displayHost("https://openrouter.ai/api/v1/"))
        assertEquals("localhost:11434", CompatibleEndpoints.displayHost("http://localhost:11434/v1/"))
    }

    @Test fun aCompatibleServerHasNoDefaultModelUntilItListsJustOne() {
        val prefs = mutablePreferencesOf()
        assertEquals("", ModelProviders.chosenModel(prefs, AuthMode.COMPATIBLE))
        assertEquals("llama3.2-vision" to null, ModelProviders.upgrades(AuthMode.COMPATIBLE, listOf("llama3.2-vision")))
        assertEquals(null to null, ModelProviders.upgrades(AuthMode.COMPATIBLE, listOf("a/x", "b/y")))
        assertNull(ModelProviders.lightModel(prefs, AuthMode.COMPATIBLE))
    }

    @Test fun aCompatibleServerHasNoFastModeOrHostedSearch() {
        assertFalse(ModelProviders.supportsFast(AuthMode.COMPATIBLE))
        assertFalse(ModelProviders.supportsWebSearch(AuthMode.COMPATIBLE))
        assertTrue(ModelProviders.supportsWebSearch(AuthMode.CLAUDE))
    }
}
