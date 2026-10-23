package com.team1.hangsha.board.repository

import com.team1.hangsha.board.model.BoardPost
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository

interface BoardPostRepository : CrudRepository<BoardPost, Long> {
    @Query("SELECT * FROM board_posts WHERE id = :id FOR UPDATE")
    fun lockById(id: Long): BoardPost?
}
