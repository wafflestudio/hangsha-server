package com.team1.hangsha.review.repository

import com.team1.hangsha.review.model.Review
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.query.Param

interface ReviewRepository : CrudRepository<Review, Long> {
    /** 지워진 후기, 관리자가 지운 행사의 후기는 없는 것으로 본다. */
    @Query(
        """
        SELECT r.*
        FROM reviews r
        JOIN events e ON e.id = r.event_id
        WHERE r.id = :reviewId
          AND r.is_deleted = FALSE
          AND e.admin_deleted = FALSE
        """
    )
    fun findLiveById(@Param("reviewId") reviewId: Long): Review?
}
