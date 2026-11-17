package com.team1.hangsha.board.service

import com.team1.hangsha.board.dto.CreateCommentRequest
import com.team1.hangsha.board.dto.CommentPageResponse
import com.team1.hangsha.board.dto.core.CommentResponse
import com.team1.hangsha.board.model.BoardComment
import com.team1.hangsha.board.repository.BoardCommentRepository
import com.team1.hangsha.board.repository.BoardPostRepository
import com.team1.hangsha.board.repository.BoardQueryRepository
import com.team1.hangsha.board.repository.BoardRelationsRepository
import com.team1.hangsha.common.error.DomainException
import com.team1.hangsha.common.error.ErrorCode
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BoardCommentService(
    private val posts: BoardPostRepository,
    private val comments: BoardCommentRepository,
    private val query: BoardQueryRepository,
    private val relations: BoardRelationsRepository,
) {
    @Transactional(readOnly = true)
    fun list(postId: Long, viewer: Long?, sort: String, page: Int, size: Int): CommentPageResponse {
        BoardPolicy.id(postId)
        val offset = BoardPolicy.page(page, size)
        if (sort !in setOf("recommended", "latest")) BoardPolicy.invalid("sort는 recommended 또는 latest여야 합니다")
        if (!posts.existsById(postId)) throw DomainException(ErrorCode.POST_NOT_FOUND)
        val (roots, total) = query.commentTotals(postId)
        return CommentPageResponse(page, size, roots, total, query.comments(postId, viewer, sort, offset, size))
    }

    @Transactional
    fun create(userId: Long, postId: Long, request: CreateCommentRequest): CommentResponse {
        BoardPolicy.id(postId)
        val post = posts.lockById(postId) ?: throw DomainException(ErrorCode.POST_NOT_FOUND)
        request.parentId?.let {
            // Reject foreign-post parents without taking another post's comment lock.
            val candidate = comments.findByIdOrNull(it) ?: throw DomainException(ErrorCode.COMMENT_NOT_FOUND)
            if (candidate.postId != postId) throw DomainException(ErrorCode.COMMENT_NOT_FOUND)
            val parent = comments.lockByIdAndPostId(it, postId) ?: throw DomainException(ErrorCode.COMMENT_NOT_FOUND)
            if (parent.postId != postId || parent.isDeleted) throw DomainException(ErrorCode.COMMENT_NOT_FOUND)
            if (parent.parentId != null) BoardPolicy.invalid("대댓글은 1단계까지만 가능합니다")
        }
        if (request.isAnonymous && userId != post.userId) relations.ensureAnonymous(post, userId)
        val comment = comments.save(
            BoardComment(
                postId = postId,
                userId = userId,
                parentId = request.parentId,
                content = request.content.trim(),
                isAnonymous = request.isAnonymous
            )
        )
        return checkNotNull(query.comment(comment.id!!, userId))
    }

    @Transactional
    fun delete(userId: Long, commentId: Long) {
        val comment = lockLive(commentId)
        if (comment.userId != userId) throw DomainException(ErrorCode.COMMENT_NOT_FOUND)
        if (comment.parentId == null && comments.findReplyIdsForUpdate(commentId).isNotEmpty()) {
            relations.tombstone(commentId)
        } else {
            comments.deleteById(commentId)
            comment.parentId?.let { parentId ->
                val parent = comments.lockById(parentId)
                if (parent?.isDeleted == true && comments.findReplyIdsForUpdate(parentId).isEmpty()) comments.deleteById(parentId)
            }
        }
    }

    @Transactional
    fun like(userId: Long, commentId: Long, add: Boolean) {
        lockLive(commentId)
        relations.commentLike(commentId, userId, add)
    }

    private fun lockLive(commentId: Long): BoardComment {
        BoardPolicy.id(commentId)
        // First read only identifies the post. Re-read under the post lock before checking policies.
        val initial = comments.findByIdOrNull(commentId) ?: throw DomainException(ErrorCode.COMMENT_NOT_FOUND)
        if (posts.lockById(initial.postId) == null) throw DomainException(ErrorCode.COMMENT_NOT_FOUND)
        val comment = comments.lockById(commentId) ?: throw DomainException(ErrorCode.COMMENT_NOT_FOUND)
        if (comment.isDeleted) throw DomainException(ErrorCode.COMMENT_NOT_FOUND)
        return comment
    }
}
