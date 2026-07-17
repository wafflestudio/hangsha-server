package com.team1.hangsha.config

import com.team1.hangsha.com.team1.hangsha.config.JsonToStringListConverter
import com.team1.hangsha.com.team1.hangsha.config.StringListToJsonConverter
import org.springframework.context.annotation.Configuration
import org.springframework.data.jdbc.repository.config.EnableJdbcAuditing
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration

@Configuration
@EnableJdbcRepositories(basePackages = ["com.team1.hangsha"])
@EnableJdbcAuditing
class DatabaseConfig : AbstractJdbcConfiguration() {
    override fun userConverters(): MutableList<Any> = mutableListOf(
        StringListToJsonConverter(),
        JsonToStringListConverter(),
    )
}
