package com.relay.auth

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalEncodingApi::class)
class JwtClaimsTest {

    @Test
    fun extractsSubjectFromValidJwt() {
        val header = encodeSegment("""{"alg":"RS256","typ":"JWT"}""")
        val payload = encodeSegment("""{"sub":"user-123","preferred_username":"anton"}""")
        assertEquals("user-123", JwtClaims.subjectOf("$header.$payload.signature"))
    }

    @Test
    fun returnsNullForGarbage() {
        assertNull(JwtClaims.subjectOf("not-a-jwt"))
        assertNull(JwtClaims.subjectOf(""))
        assertNull(JwtClaims.subjectOf("a.%%%%.c"))
    }

    @Test
    fun returnsNullWhenSubMissing() {
        val header = encodeSegment("""{"alg":"none"}""")
        val payload = encodeSegment("""{"iss":"relay"}""")
        assertNull(JwtClaims.subjectOf("$header.$payload."))
    }

    private fun encodeSegment(json: String): String =
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
            .encode(json.encodeToByteArray())
}
