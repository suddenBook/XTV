package com.xtv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainActivityProvisioningTest {
    @Test
    fun `ordinary launcher intent does not start provisioning`() {
        assertNull(
            legacyProvisioningRequest(
                requestId = null,
                clientId = null,
                refreshToken = null,
                bearer = null,
            ),
        )
    }

    @Test
    fun `original three-extra workflow creates a provisioning request`() {
        val request = legacyProvisioningRequest(
            requestId = null,
            clientId = "client",
            refreshToken = "refresh",
            bearer = "bearer",
            newRequestId = { "generated-request" },
        )

        assertEquals("generated-request", request?.requestId)
        assertEquals("client", request?.clientId)
        assertEquals("refresh", request?.refreshToken)
        assertEquals("bearer", request?.appOnlyBearer)
    }

    @Test
    fun `caller request id is retained`() {
        val request = legacyProvisioningRequest(
            requestId = "caller-request",
            clientId = "client",
            refreshToken = "refresh",
            bearer = "bearer",
        )

        assertEquals("caller-request", request?.requestId)
    }

    @Test
    fun `present but incomplete extras reach normal validation`() {
        val request = legacyProvisioningRequest(
            requestId = null,
            clientId = "",
            refreshToken = null,
            bearer = null,
            newRequestId = { "generated-request" },
        )

        assertEquals("", request?.clientId)
        assertEquals("", request?.refreshToken)
        assertEquals("", request?.appOnlyBearer)
    }
}
