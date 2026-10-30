package com.team1.hangsha.memo.model

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.MappedCollection
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("memos")
data class Memo(
    @Id
    val id: Long? = null,

    @Column("user_id")
    val userId: Long,

    @Column("event_id")
    val eventId: Long,

    val content: String,

    @MappedCollection(idColumn = "memo_id")
    val tags: Set<MemoTagRef> = emptySet(),

    @CreatedDate
    val createdAt: Instant? = null,

    @LastModifiedDate
    val updatedAt: Instant? = null
)

@Table("memo_tags")
data class MemoTagRef(
    @Column("tag_id")
    val tagId: Long
)