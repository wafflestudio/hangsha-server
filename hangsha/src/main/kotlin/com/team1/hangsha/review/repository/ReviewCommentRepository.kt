package com.team1.hangsha.review.repository

import com.team1.hangsha.review.model.ReviewComment
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.query.Param

interface ReviewCommentRepository : CrudRepository<ReviewComment, Long> {
    /**
     * 후기를 지워도 댓글 행은 is_deleted = FALSE로 남으므로(소프트 삭제라 CASCADE가 돌지 않는다)
     * 부모 후기와 행사의 삭제 여부까지 같이 본다.
     */
    @Query(
        """
        SELECT c.*
        FROM review_comments c
        JOIN reviews r ON r.id = c.review_id
        JOIN events e ON e.id = r.event_id
        WHERE c.id = :commentId
          AND c.is_deleted = FALSE
          AND r.is_deleted = FALSE
          AND e.admin_deleted = FALSE
        """
    )
    fun findLiveById(@Param("commentId") commentId: Long): ReviewComment?
}
