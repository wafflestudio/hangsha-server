package com.team1.hangsha.review.repository

import com.team1.hangsha.review.model.Review
import com.team1.hangsha.review.model.ReviewComment
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository

interface ReviewRepository : CrudRepository<Review, Long> {
    @Query("SELECT * FROM reviews WHERE id = :id FOR UPDATE")
    fun lockById(id: Long): Review?
}

interface ReviewCommentRepository : CrudRepository<ReviewComment, Long> {
    @Query("SELECT * FROM review_comments WHERE id = :id FOR UPDATE")
    fun lockById(id: Long): ReviewComment?

    @Query("SELECT id FROM review_comments WHERE parent_id = :parentId FOR UPDATE")
    fun findReplyIdsForUpdate(parentId: Long): List<Long>
}
