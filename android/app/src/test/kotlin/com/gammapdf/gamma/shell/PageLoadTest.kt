package com.gammapdf.gamma.shell

import android.webkit.WebViewClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A failed main-frame load, as the launcher says it (shell/PageLoad.kt). */
class PageLoadTest {
    @Test
    fun theCodesAreAndroids() {
        assertEquals(WebViewClient.ERROR_HOST_LOOKUP, PageLoad.ERROR_HOST_LOOKUP)
        assertEquals(WebViewClient.ERROR_CONNECT, PageLoad.ERROR_CONNECT)
        assertEquals(WebViewClient.ERROR_IO, PageLoad.ERROR_IO)
        assertEquals(WebViewClient.ERROR_TIMEOUT, PageLoad.ERROR_TIMEOUT)
        assertEquals(WebViewClient.ERROR_REDIRECT_LOOP, PageLoad.ERROR_REDIRECT_LOOP)
        assertEquals(WebViewClient.ERROR_UNSUPPORTED_SCHEME, PageLoad.ERROR_UNSUPPORTED_SCHEME)
        assertEquals(WebViewClient.ERROR_FAILED_SSL_HANDSHAKE, PageLoad.ERROR_FAILED_SSL_HANDSHAKE)
        assertEquals(WebViewClient.ERROR_BAD_URL, PageLoad.ERROR_BAD_URL)
    }

    @Test
    fun aFailureSaysWhatWentWrongAndChromiumsCode() {
        assertEquals("Could not connect to the server. (net::ERR_CONNECTION_REFUSED)",
                     PageLoad.failure(PageLoad.ERROR_CONNECT, "net::ERR_CONNECTION_REFUSED"))
        assertEquals("A server with the specified hostname could not be found. (net::ERR_NAME_NOT_RESOLVED)",
                     PageLoad.failure(PageLoad.ERROR_HOST_LOOKUP, "net::ERR_NAME_NOT_RESOLVED"))
        assertEquals("The Internet connection appears to be offline. (net::ERR_INTERNET_DISCONNECTED)",
                     PageLoad.failure(PageLoad.ERROR_HOST_LOOKUP, "net::ERR_INTERNET_DISCONNECTED"))
        assertEquals("The request timed out.", PageLoad.failure(PageLoad.ERROR_TIMEOUT, null))
        assertEquals(PageLoad.INSECURE + " (net::ERR_CERT_AUTHORITY_INVALID)",
                     PageLoad.failure(PageLoad.ERROR_FAILED_SSL_HANDSHAKE, "net::ERR_CERT_AUTHORITY_INVALID"))
        assertEquals("a code without a sentence keeps Chromium's", "net::ERR_ADDRESS_UNREACHABLE",
                     PageLoad.failure(-1, " net::ERR_ADDRESS_UNREACHABLE "))
        assertEquals("The page did not load.", PageLoad.failure(-1, ""))
    }

    @Test
    fun aLoadGivenUpIsNoFailure() {
        assertNull(PageLoad.failure(-1, "net::ERR_ABORTED"))
        assertNull(PageLoad.failure(PageLoad.ERROR_IO, "net::ERR_CANCELLED"))
    }
}
