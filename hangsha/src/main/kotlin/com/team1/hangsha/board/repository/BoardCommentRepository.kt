package com.team1.hangsha.board.repository

import com.team1.hangsha.board.model.BoardComment
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository

interface BoardCommentRepository : CrudRepository<BoardComment, Long> {
    @Query("SELECT * FROM board_comments WHERE id = :id FOR UPDATE")
    fun lockById(id: Long): BoardComment?

    @Query("SELECT * FROM board_comments WHERE id = :id AND post_id = :postId FOR UPDATE")
    fun lockByIdAndPostId(id: Long, postId: Long): BoardComment?

    @Query("SELECT id FROM board_comments WHERE parent_id = :parentId FOR UPDATE")
    fun findReplyIdsForUpdate(parentId: Long): List<Long>
}
