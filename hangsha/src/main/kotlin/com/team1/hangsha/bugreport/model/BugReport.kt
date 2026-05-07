package com.team1.hangsha.bugreport.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("bug_reports")
data class BugReport(
    @Id
    val id: Long? = null,

    @Column("user_id")
    val userId: Long? = null,

    val title: String,

    val content: String,

    @Column("created_at")
    val createdAt: Instant? = null,
)
