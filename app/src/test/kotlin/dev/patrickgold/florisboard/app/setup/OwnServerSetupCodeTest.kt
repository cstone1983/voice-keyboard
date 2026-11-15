package dev.patrickgold.florisboard.app.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OwnServerSetupCodeTest {
    private val key = "0123456789abcdef".repeat(4)

    @Test
    fun `reads the server's v1 code`() {
        val code = parseOwnServerSetupCode("""{"v":1,"base_url":"https://voice.example.com/v1/","api_key":"$key"}""")
        assertEquals("https://voice.example.com/v1/", code?.baseUrl)
        assertEquals(key, code?.apiKey)
        assertNull(code?.transcriptionModel)
    }

    @Test
    fun `ignores unknown fields, adds the trailing slash and reads optional models`() {
        val code = parseOwnServerSetupCode(
            """ {"v":1,"base_url":"https://voice.example.com/v1","api_key":"$key","extra":{"a":1},"stt_model":"w","chat_model":"c"} """,
        )
        assertEquals("https://voice.example.com/v1/", code?.baseUrl)
        assertEquals("w", code?.transcriptionModel)
        assertEquals("c", code?.chatModel)
    }

    @Test
    fun `rejects text that is not a setup code`() {
        assertNull(parseOwnServerSetupCode("hello"))
        assertNull(parseOwnServerSetupCode("""{"v":1,"api_key":"$key"}"""))
        assertNull(parseOwnServerSetupCode("""{"v":1,"base_url":"ftp://x/v1/","api_key":"$key"}"""))
    }
}

class ServerUrlPolicyTest {
    @Test
    fun `https always, http only on the home network`() {
        kotlin.test.assertTrue(isAllowedServerUrl("https://voice.example.com/v1/"))
        kotlin.test.assertTrue(isAllowedServerUrl("http://10.10.11.113:8000/v1/"))
        kotlin.test.assertTrue(isAllowedServerUrl("http://voice.lan/v1/"))
        kotlin.test.assertTrue(isAllowedServerUrl("http://192.168.1.5/v1/"))
        kotlin.test.assertFalse(isAllowedServerUrl("http://voice.example.com/v1/"))
        kotlin.test.assertFalse(isAllowedServerUrl("http://8.8.8.8/v1/"))
    }

    @Test
    fun `setup code user_id is the identity, user is display only`() {
        val code = parseOwnServerSetupCode("""{"v":1,"base_url":"https://x.example/v1/","api_key":"k","user":"Chris","user_id":"chris"}""")
        assertEquals("chris", code?.userId)
        assertEquals("Chris", code?.user)
    }
}
