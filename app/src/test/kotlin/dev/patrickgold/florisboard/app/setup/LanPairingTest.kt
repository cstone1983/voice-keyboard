/*
 * Copyright (C) 2026 Voice Keyboard contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.app.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LanPairingTest {
    @Test
    fun `a typed server address becomes the pairing url`() {
        assertEquals("http://192.168.1.20:8080/v1/", LanPairing.pairUrlFrom("192.168.1.20"))
        assertEquals("http://voice.local:8080/v1/", LanPairing.pairUrlFrom(" voice.local "))
        assertEquals("http://voice.local:9000/v1/", LanPairing.pairUrlFrom("voice.local:9000"))
        assertEquals("http://10.0.0.5:8080/api/", LanPairing.pairUrlFrom("http://10.0.0.5:8080/api/"))
        assertNull(LanPairing.pairUrlFrom("   "))
    }
}
