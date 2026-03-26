package com.team1.hangsha.config

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
