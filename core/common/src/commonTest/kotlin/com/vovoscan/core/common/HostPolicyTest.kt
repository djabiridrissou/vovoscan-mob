package com.vovoscan.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A source never makes the phone reach the reader's own network. */
class HostPolicyTest {
    @Test
    fun publicNamesAndAddressesPass() {
        for (host in listOf("pages.inkwell.example", "www.gutenberg.org", "comics.inkwell.example", "8.8.8.8", "1.1.1.1", "Example.COM.")) {
            assertTrue(HostPolicy.isPublic(host), host)
        }
    }

    @Test
    fun localNetworksAreRefusedHoweverTheyAreWritten() {
        val names = listOf("localhost", "LOCALHOST.", "api.localhost", "printer.local", "nas.lan", "db.internal", "router.home.arpa")
        val ipv4 = listOf("127.0.0.1", "127.1", "2130706433", "0x7f.0.0.1", "0177.0.0.1", "10.0.0.8", "172.16.0.1", "172.31.255.255")
        val more = listOf("192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "224.0.0.1", "255.255.255.255")
        val odd = listOf("[::1]", "[fe80::1]", "[::ffff:127.0.0.1]", "intranet", "a b.example", "evil.example%2f", "")
        val refused = names + ipv4 + more + odd
        for (host in refused) assertFalse(HostPolicy.isPublic(host), host)
    }

    @Test
    fun theHostOfAUrlNeverHidesBehindUserInfo() {
        assertEquals("api.example", HostPolicy.hostOf("https://API.example:8443/x?y#z"))
        assertEquals("[::1]", HostPolicy.hostOf("http://[::1]:80/"))
        assertNull(HostPolicy.hostOf("https://api.example:x@127.0.0.1/"))
        assertNull(HostPolicy.hostOf("https://api.example\\@127.0.0.1/"))
        assertNull(HostPolicy.hostOf("not a url"))
        assertFalse(HostPolicy.isPublicUrl("https://192.168.0.10/catalog"))
        assertTrue(HostPolicy.isPublicUrl("https://gutendex.com/books"))
    }
}
