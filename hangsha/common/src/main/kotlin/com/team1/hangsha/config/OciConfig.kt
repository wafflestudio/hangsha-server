package com.team1.hangsha.config

import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider
import com.oracle.bmc.auth.InstancePrincipalsAuthenticationDetailsProvider
import com.oracle.bmc.objectstorage.ObjectStorage
import com.oracle.bmc.objectstorage.ObjectStorageClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OciConfig(
    @Value("\${oci.auth.type:auto}")
    private val authType: String,
    @Value("\${oci.auth.profile:DEFAULT}")
    private val configProfile: String,
    @Value("\${oci.storage.region}")
    private val region: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun ociAuthProvider(): BasicAuthenticationDetailsProvider {
        return when (authType.trim().lowercase()) {
            "auto" -> try {
                InstancePrincipalsAuthenticationDetailsProvider.builder().build()
            } catch (e: Exception) {
                log.info("OCI Instance Principal failed; falling back to config file auth: {}", e.message)
                ConfigFileAuthenticationDetailsProvider(configProfile)
            }
            "config" -> ConfigFileAuthenticationDetailsProvider(configProfile)
            "instance_principal" -> InstancePrincipalsAuthenticationDetailsProvider.builder().build()
            else -> throw IllegalArgumentException("Unsupported oci.auth.type: $authType")
        }
    }

    @Bean
    fun objectStorageClient(authProvider: BasicAuthenticationDetailsProvider): ObjectStorage {
        return ObjectStorageClient.builder()
            .region(region)
            .build(authProvider)
    }
}
