package com.team1.hangsha.config

import org.slf4j.LoggerFactory
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.PriorityOrdered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.context.EnvironmentAware
import org.springframework.stereotype.Component
import org.springframework.util.ReflectionUtils
import java.util.concurrent.ConcurrentHashMap

@Component
class ValueLoggingPostProcessor : BeanPostProcessor, PriorityOrdered, EnvironmentAware {
    private val log = LoggerFactory.getLogger(ValueLoggingPostProcessor::class.java)
    private val processedBeans = ConcurrentHashMap.newKeySet<String>()
    private val loggedKeys = ConcurrentHashMap.newKeySet<String>()
    private lateinit var environment: ConfigurableEnvironment

    override fun setEnvironment(environment: org.springframework.core.env.Environment) {
        this.environment = environment as ConfigurableEnvironment
    }

    override fun getOrder(): Int {
        return Ordered.LOWEST_PRECEDENCE
    }

    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
        if (!processedBeans.add(beanName)) {
            return bean
        }

        val beanClass = AopUtils.getTargetClass(bean)
        ReflectionUtils.doWithFields(beanClass) { field ->
            val valueAnnotation = field.getAnnotation(Value::class.java)
                ?: return@doWithFields
            logValue(valueAnnotation.value)
        }

        beanClass.declaredConstructors.forEach { constructor ->
            constructor.parameters.forEach { parameter ->
                val valueAnnotation = parameter.getAnnotation(Value::class.java)
                    ?: return@forEach
                logValue(valueAnnotation.value)
            }
        }

        return bean
    }

    private fun logValue(raw: String) {
        val resolvedKey = extractPropertyKey(raw)
            ?: return
        if (!loggedKeys.add(resolvedKey)) {
            return
        }

        val resolvedValue = try {
            environment.resolvePlaceholders(raw)
        } catch (ex: Exception) {
            log.debug("@Value [{}] resolve failed ({})", resolvedKey, ex.message)
            return
        }

        val masked = mask(resolvedValue)
        log.debug("@Value [{}] = {}", resolvedKey, masked)
    }

    private fun extractPropertyKey(raw: String): String? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("\${") || !trimmed.endsWith("}")) {
            return null
        }
        // Strip ${...}
        val inner = trimmed.substring(2, trimmed.length - 1)
        // Drop default value suffix if present (e.g. key:default)
        val key = inner.substringBefore(":").trim()
        if (key.isEmpty()) return null
        return key
    }

    private fun mask(input: String): String {
        if (input.length <= 3) {
            return "***"
        }
        return input.substring(0, 3) + "***"
    }
}
