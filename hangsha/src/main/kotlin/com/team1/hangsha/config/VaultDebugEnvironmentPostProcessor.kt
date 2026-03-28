package com.team1.hangsha.config

import org.slf4j.LoggerFactory
import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.env.PropertySource

class VaultDebugEnvironmentPostProcessor : EnvironmentPostProcessor, Ordered {
    private val log = LoggerFactory.getLogger(VaultDebugEnvironmentPostProcessor::class.java)

    private val interestingKeys = listOf(
        "spring.security.oauth2.client.registration.google.client-id",
        "spring.security.oauth2.client.registration.google.client-secret",
        "spring.security.oauth2.client.registration.naver.client-id",
        "spring.security.oauth2.client.registration.kakao.client-id",
        "jwt.secret",
    )

    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        log.info(
            "[vault-debug] activeProfiles={}, defaultProfiles={}",
            environment.activeProfiles.joinToString(","),
            environment.defaultProfiles.joinToString(","),
        )

        val secretIds = environment.getProperty("oci.vault.secret-ids")
        log.info("[vault-debug] oci.vault.secret-ids present={}, len={}", secretIds != null, secretIds?.length ?: -1)

        val propertySourceNames = environment.propertySources.map(PropertySource<*>::getName)
        log.info("[vault-debug] propertySources={}", propertySourceNames.joinToString(" -> "))

        val vaultSource = environment.propertySources["oci-vault-secrets"]
        if (vaultSource == null) {
            log.warn("[vault-debug] propertySource 'oci-vault-secrets' NOT FOUND")
        } else {
            log.info("[vault-debug] propertySource 'oci-vault-secrets' FOUND (type={})", vaultSource.javaClass.name)
            if (vaultSource is EnumerablePropertySource<*>) {
                val names = vaultSource.propertyNames.toSet()
                log.info("[vault-debug] oci-vault-secrets keyCount={}", names.size)
                for (key in interestingKeys) {
                    log.info("[vault-debug] oci-vault-secrets hasKey({})={}", key, names.contains(key))
                }
            }
        }

        for (key in interestingKeys) {
            val resolved = environment.getProperty(key)
            val owner = findOwner(environment, key)
            log.info(
                "[vault-debug] key={} resolvedLen={} owner={}",
                key,
                resolved?.length ?: -1,
                owner ?: "NOT_FOUND",
            )
        }

    }

    private fun findOwner(environment: ConfigurableEnvironment, key: String): String? {
        for (source in environment.propertySources) {
            val value = source.getProperty(key)
            if (value != null) {
                return source.name
            }
        }
        return null
    }
}
