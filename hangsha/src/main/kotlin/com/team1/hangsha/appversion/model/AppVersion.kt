package com.team1.hangsha.appversion.model

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("app_versions")
data class AppVersion(
    @Id
    val id: Long? = null,

    val platform: AppPlatform,

    @Column("version_code")
    val versionCode: Int,

    @Column("version_name")
    val versionName: String,

    @Column("release_notes")
    val releaseNotes: String,

    @Column("minimum_supported_version_code")
    val minimumSupportedVersionCode: Int,

    @CreatedDate
    @Column("created_at")
    val createdAt: Instant? = null,

    @LastModifiedDate
    @Column("updated_at")
    val updatedAt: Instant? = null,
)
