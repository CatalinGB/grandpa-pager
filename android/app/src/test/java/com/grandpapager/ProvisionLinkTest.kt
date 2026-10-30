package com.grandpapager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The setup link is a contract shared with `setup.html` (and the admin QR
 * builder), so the parameter names and validation are pinned here.
 */
class ProvisionLinkTest {

    private val token = "AbCdEf0123456789AbCdEf0123456789AbCdEf0123456789AbCdEf0123456789"

    @Test
    fun parsesAFullReceiverLink() {
        val p = ProvisionLink.parse(
            "grandpapager://setup?host=mqtt.flespi.io&port=8883&tls=1" +
                "&user=$token&pass=&role=receiver&name=Grandpa"
        )!!
        assertEquals("mqtt.flespi.io", p.host)
        assertEquals(8883, p.port)
        assertEquals(true, p.tls)
        assertEquals(token, p.username)
        assertEquals("", p.password)
        assertEquals(Role.RECEIVER, p.role)
        assertEquals("Grandpa", p.senderName)
    }

    @Test
    fun appliesSensibleDefaults() {
        val p = ProvisionLink.parse("grandpapager://setup?host=broker.local")!!
        assertEquals(8883, p.port)
        assertEquals(true, p.tls)
        assertEquals("", p.username)
        assertEquals(Role.RECEIVER, p.role)
        assertNull(p.senderName)
    }

    @Test
    fun acceptsTheAliasesAndAnExplicitTlsOff() {
        val p = ProvisionLink.parse(
            "grandpapager://setup?broker=mqtts://example.org:1234&port=1883&tls=false" +
                "&username=admin&password=secret&role=admin"
        )!!
        assertEquals("example.org", p.host) // scheme and embedded port are stripped
        assertEquals(1883, p.port)
        assertEquals(false, p.tls)
        assertEquals("admin", p.username)
        assertEquals("secret", p.password)
        assertEquals(Role.SENDER_ADMIN, p.role)
    }

    @Test
    fun decodesPercentEncodedCredentialsAndNames() {
        val p = ProvisionLink.parse(
            "grandpapager://setup?host=mqtt.flespi.io&user=abc%2Bdef%2Fghi&pass=p%40ss%20word" +
                "&name=Ionu%C8%9B%20Popescu"
        )!!
        assertEquals("abc+def/ghi", p.username)
        assertEquals("p@ss word", p.password)
        assertEquals("Ionuț Popescu", p.senderName)
    }

    @Test
    fun aLiteralPlusInATokenIsNotASpace() {
        val p = ProvisionLink.parse("grandpapager://setup?host=h&user=a+b")!!
        assertEquals("a+b", p.username)
    }

    @Test
    fun anEmbeddedPortIsUsedWhenNoPortParameterIsGiven() {
        val p = ProvisionLink.parse("grandpapager://setup?host=mqtts://example.org:8884")!!
        assertEquals("example.org", p.host)
        assertEquals(8884, p.port)
        // An explicit parameter still wins.
        val q = ProvisionLink.parse("grandpapager://setup?host=example.org:8884&port=1883")!!
        assertEquals(1883, q.port)
    }

    @Test
    fun rejectsMalformedOrDangerousLinks() {
        assertNull(ProvisionLink.parse(null))
        assertNull(ProvisionLink.parse(""))
        assertNull(ProvisionLink.parse("https://example.com/setup?host=x"))
        assertNull(ProvisionLink.parse("grandpapager://other?host=x"))
        assertNull(ProvisionLink.parse("grandpapager://setup/extra?host=x"))
        assertNull(ProvisionLink.parse("grandpapager://setup?host="))
        assertNull(ProvisionLink.parse("grandpapager://setup?host=has%20space"))
        assertNull(ProvisionLink.parse("grandpapager://setup?host=x&port=0"))
        assertNull(ProvisionLink.parse("grandpapager://setup?host=x&port=70000"))
        assertNull(ProvisionLink.parse("grandpapager://setup?host=x&port=abc"))
        assertNull(ProvisionLink.parse("grandpapager://setup?host=x&role=root"))
    }

    @Test
    fun ignoresUnknownParametersSoTheLinkCanGrow() {
        val p = ProvisionLink.parse("grandpapager://setup?host=x&v=2&future=1")!!
        assertEquals("x", p.host)
    }

    @Test
    fun schemeAndAuthorityAreCaseInsensitive() {
        val p = ProvisionLink.parse("GrandpaPager://SETUP?host=x")!!
        assertEquals("x", p.host)
    }
}
