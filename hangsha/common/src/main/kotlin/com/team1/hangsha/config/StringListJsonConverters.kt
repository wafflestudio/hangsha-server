package com.team1.hangsha.com.team1.hangsha.config

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter

private val mapper = jacksonObjectMapper().findAndRegisterModules()

@WritingConverter
class StringListToJsonConverter : Converter<List<String>, String> {
    override fun convert(source: List<String>): String =
        mapper.writeValueAsString(source)
}

@ReadingConverter
class JsonToStringListConverter : Converter<String, List<String>> {
    override fun convert(source: String): List<String> {
        val s = source.trim()
        if (s.isEmpty()) return emptyList()
        return runCatching { mapper.readValue<List<String>>(s) }
            .getOrElse { emptyList() }
    }
}

@WritingConverter
class BooleanMapToJsonConverter : Converter<Map<String, Boolean>, String> {
    override fun convert(source: Map<String, Boolean>): String =
        mapper.writeValueAsString(source)
}

@ReadingConverter
class JsonToBooleanMapConverter : Converter<String, Map<String, Boolean>> {
    override fun convert(source: String): Map<String, Boolean> {
        val s = source.trim()
        if (s.isEmpty()) return emptyMap()
        return runCatching { mapper.readValue<Map<String, Boolean>>(s) }
            .getOrElse { emptyMap() }
    }
}
