package com.team1.hangsha.config

import com.oracle.bmc.objectstorage.ObjectStorage
import com.oracle.bmc.objectstorage.requests.GetNamespaceRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class OciAuthProbe(
    private val objectStorage: ObjectStorage,
    @Value("\${oci.auth.verify:false}") private val verify: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun verifyAuth() {
        if (!verify) return
        try {
            val response = objectStorage.getNamespace(GetNamespaceRequest.builder().build())
            log.info("[oci-auth] OK: namespace={}", response.value)
        } catch (e: Exception) {
            log.error("[oci-auth] FAILED: {}", e.message, e)
        }
    }
}
