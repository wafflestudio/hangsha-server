package com.team1.hangsha.board.service

import com.fasterxml.jackson.databind.JsonNode
import com.team1.hangsha.board.dto.CreatePostRequest
import com.team1.hangsha.board.dto.PostPageResponse
import com.team1.hangsha.board.dto.core.PostDetailResponse
import com.team1.hangsha.board.model.BoardPost
import com.team1.hangsha.board.repository.BoardPostRepository
import com.team1.hangsha.board.repository.BoardQueryRepository
import com.team1.hangsha.board.repository.BoardRelationsRepository
import com.team1.hangsha.common.error.DomainException
import com.team1.hangsha.common.error.ErrorCode
import com.team1.hangsha.event.repository.EventRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BoardPostService(
    private val posts: BoardPostRepository,
    private val query: BoardQueryRepository,
    private val relations: BoardRelationsRepository,
    private val events: EventRepository,
) {
    @Transactional
    fun create(userId: Long, request: CreatePostRequest): PostDetailResponse {
        request.eventId?.let { if (events.findVisibleById(it) == null) throw DomainException(ErrorCode.EVENT_NOT_FOUND) }
        val post = posts.save(
            BoardPost(
                userId = userId,
                eventId = request.eventId,
                title = request.title.trim(),
                content = request.content.trim(),
                isAnonymous = request.isAnonymous
            )
        )
        relations.replaceTags(post.id!!, request.tagNames.map { it.trim() }.distinct())
        return detail(post.id, userId)
    }

    @Transactional(readOnly = true)
    fun list(
        viewer: Long?,
        search: String?,
        eventId: Long?,
        popularOnly: Boolean,
        page: Int,
        size: Int
    ): PostPageResponse {
        val offset = BoardPolicy.page(page, size)
        eventId?.let(BoardPolicy::id)
        val (total, items) = query.list(BoardPolicy.query(search), eventId, popularOnly, offset, size, viewer)
        return PostPageResponse(page, size, total, items)
    }

    @Transactional(readOnly = true)
    fun detail(postId: Long, viewer: Long?): PostDetailResponse {
        BoardPolicy.id(postId)
        return query.detail(postId, viewer) ?: throw DomainException(ErrorCode.POST_NOT_FOUND)
    }

    @Transactional
    fun update(userId: Long, postId: Long, body: JsonNode): PostDetailResponse {
        BoardPolicy.body(body, setOf("title", "content", "tagNames", "isAnonymous"))
        if (body.isEmpty) BoardPolicy.invalid("수정할 필드가 필요합니다")
        val post = lock(postId)
        owner(post, userId)

        val title = if (body.has("title")) BoardPolicy.text(
            BoardPolicy.string(body, "title"),
            BoardPolicy.TITLE_MAX,
            "제목"
        ) else post.title

        val content = if (body.has("content")) BoardPolicy.text(
            BoardPolicy.string(body, "content"),
            BoardPolicy.CONTENT_MAX,
            "본문"
        ) else post.content

        val anonymous = if (body.has("isAnonymous")) BoardPolicy.flag(body, "isAnonymous") else post.isAnonymous
        posts.save(post.copy(title = title, content = content, isAnonymous = anonymous))
        if (body.has("tagNames")) relations.replaceTags(postId, BoardPolicy.tagNames(body, allowNull = true))
        return detail(postId, userId)
    }

    @Transactional
    fun delete(userId: Long, postId: Long) {
        val post = lock(postId)
        owner(post, userId)
        relations.deletePostChildren(postId)
        posts.deleteById(postId)
    }

    @Transactional
    fun like(userId: Long, postId: Long, add: Boolean) {
        lock(postId)
        relations.postLike(postId, userId, add)
    }

    private fun lock(postId: Long): BoardPost {
        BoardPolicy.id(postId)
        return posts.lockById(postId) ?: throw DomainException(ErrorCode.POST_NOT_FOUND)
    }

    private fun owner(post: BoardPost, userId: Long) {
        if (post.userId != userId) throw DomainException(ErrorCode.POST_NOT_FOUND)
    }
}
