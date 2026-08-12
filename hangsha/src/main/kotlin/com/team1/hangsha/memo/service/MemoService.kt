package com.team1.hangsha.memo.service

import com.team1.hangsha.common.error.DomainException
import com.team1.hangsha.common.error.ErrorCode
import com.team1.hangsha.event.repository.EventRepository
import com.team1.hangsha.memo.dto.*
import com.team1.hangsha.memo.dto.core.MemoOrganizationResponse
import com.team1.hangsha.memo.dto.core.MemoResponse
import com.team1.hangsha.memo.dto.core.MemoTagResponse
import com.team1.hangsha.memo.dto.core.MemoWithEventResponse
import com.team1.hangsha.memo.model.Memo
import com.team1.hangsha.memo.model.MemoTagRef
import com.team1.hangsha.memo.repository.MemoQueryRepository
import com.team1.hangsha.memo.repository.MemoRepository
import com.team1.hangsha.tag.model.Tag
import com.team1.hangsha.tag.repository.TagRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

@Service
class MemoService(
    private val memoRepository: MemoRepository,
    private val memoQueryRepository: MemoQueryRepository,
    private val tagRepository: TagRepository,
    private val eventRepository: EventRepository,
    private val objectMapper: ObjectMapper,
) {

    @Transactional
    fun createMemo(userId: Long, req: CreateMemoRequest): MemoResponse {
        validateMemoContent(req.content)

        val event = eventRepository.findById(req.eventId)
            .orElseThrow { DomainException(ErrorCode.EVENT_NOT_FOUND) }

        // 태그 처리: 이름으로 유저 태그 조회 -> 없으면 생성
        val tagRefs = resolveTags(userId, req.tagNames)

        val memo = memoRepository.save(
            Memo(
                userId = userId,
                eventId = req.eventId,
                content = req.content,
                tags = tagRefs
            )
        )

        return mapToMemoResponse(memo, event.title)
    }

    @Transactional
    fun updateMemo(userId: Long, memoId: Long, body: JsonNode): MemoResponse {
        val memo = memoRepository.findById(memoId)
            .orElseThrow { DomainException(ErrorCode.MEMO_NOT_FOUND) }

        if (memo.userId != userId) {
            throw DomainException(ErrorCode.MEMO_NOT_FOUND)
        }

        val hasContent = body.has("content")
        val hasTagNames = body.has("tagNames")

        // 둘 다 미포함이면 PATCH empty
        if (!hasContent && !hasTagNames) {
            // 너희 코드 스타일대로 에러코드 하나 만들기 추천
            throw DomainException(ErrorCode.INVALID_REQUEST)
        }

        val req = try {
            objectMapper.treeToValue(body, UpdateMemoRequest::class.java)
        } catch (e: Exception) {
            throw DomainException(ErrorCode.INVALID_REQUEST, "Invalid request body")
        }

        // -------- content 정책: 미포함=변경없음 / null 또는 blank=거부 / 값=업데이트 --------
        val nextContent: String =
            if (!hasContent) {
                memo.content
            } else {
                validateMemoContent(req.content)
                req.content!!.trimEnd()
            }

        // -------- tagNames 정책: 미포함=변경없음 / null=비우기 / 값=replace-all --------
        val nextTags =
            if (!hasTagNames) {
                memo.tags
            } else {
                val namesOrNull = req.tagNames
                if (namesOrNull == null) {
                    emptySet()
                } else {
                    resolveTags(userId, namesOrNull) // distinct는 resolveTags 내부에서 하고 있지? 지금 코드에 distinct() 있음
                }
            }

        val updatedMemo = memoRepository.save(
            memo.copy(
                content = nextContent,
                tags = nextTags
            )
        )

        val eventTitle = eventRepository.findById(updatedMemo.eventId)
            .map { it.title }
            .orElse("Unknown Event")

        return mapToMemoResponse(updatedMemo, eventTitle)
    }


    @Transactional
    fun deleteMemo(userId: Long, memoId: Long) {
        val memo = memoRepository.findById(memoId)
            .orElseThrow { DomainException(ErrorCode.MEMO_NOT_FOUND) }

        if (memo.userId != userId) {
            throw DomainException(ErrorCode.MEMO_NOT_FOUND)
        }

        memoRepository.delete(memo)
        // 끝. 태그는 건드리지 않는다.
    }

    @Transactional(readOnly = true)
    fun getMyMemos(userId: Long): List<MemoWithEventResponse> {
        val rows = memoQueryRepository.findMemosWithEventByUserId(userId)
        if (rows.isEmpty()) return emptyList()

        val tagsByMemoId = memoQueryRepository.findTagsByMemoIds(rows.map { it.id })
            .groupBy({ it.memoId }, { MemoTagResponse(id = it.tagId, name = it.tagName) })

        return rows.map { row ->
            MemoWithEventResponse(
                id = row.id,
                eventId = row.eventId,
                eventTitle = row.eventTitle ?: "Unknown Event",
                categoryId = row.categoryId,
                content = row.content,
                tags = tagsByMemoId[row.id].orEmpty(),
                createdAt = row.createdAt,
                updatedAt = row.updatedAt,
                applyEnd = row.applyEnd,
                organization = if (row.orgId != null && row.orgName != null) {
                    MemoOrganizationResponse(id = row.orgId, name = row.orgName)
                } else {
                    null
                },
                isBookmarked = row.isBookmarked,
            )
        }
    }

    @Transactional(readOnly = true)
    fun getMyMemoByEventId(userId: Long, eventId: Long): MemoResponse {
        val memo = memoRepository.findByUserIdAndEventId(userId, eventId)
            .orElseThrow { DomainException(ErrorCode.MEMO_NOT_FOUND) }

        val eventTitle = eventRepository.findById(memo.eventId)
            .map { it.title }
            .orElse("Unknown Event")

        return mapToMemoResponse(memo, eventTitle)
    }

    @Transactional(readOnly = true)
    fun findMemosByTagId(userId: Long, tagId: Long): List<MemoResponse> {
        val tag = tagRepository.findById(tagId).orElseThrow { DomainException(ErrorCode.TAG_NOT_FOUND) }
        if (tag.userId != userId) throw DomainException(ErrorCode.TAG_NOT_FOUND)

        val memos = memoRepository.findAllByUserIdAndTagId(userId, tagId)

        return memos.map { memo ->
            val eventTitle = eventRepository.findById(memo.eventId)
                .map { it.title }
                .orElse("Unknown Event")

            mapToMemoResponse(memo, eventTitle)
        }
    }

    // 특정 메모의 태그만 가져오는 기능 (필요하다면 구현, 현재 Response에 포함됨)
    @Transactional(readOnly = true)
    fun getTagsByMemoId(userId: Long, memoId: Long): List<MemoTagResponse> {
        val memo = memoRepository.findById(memoId)
            .orElseThrow { DomainException(ErrorCode.MEMO_NOT_FOUND) }

        if(memo.userId != userId) throw DomainException(ErrorCode.MEMO_NOT_FOUND)

        return memo.tags.mapNotNull { ref ->
            tagRepository.findById(ref.tagId).map { MemoTagResponse(it.id!!, it.name) }.orElse(null)
        }
    }

   private fun resolveTags(userId: Long, tagNames: List<String>): Set<MemoTagRef> {
        return tagNames.distinct().map { name ->
            validateTagName(name)

            val tag = tagRepository.findByUserIdAndName(userId, name)
                .orElseGet {
                    tagRepository.save(Tag(userId = userId, name = name))
                }
            MemoTagRef(tagId = tag.id!!)
        }.toSet()
    }

    private fun validateMemoContent(content: String?) {
        if (content.isNullOrBlank()) {
            throw DomainException(ErrorCode.MEMO_CONTENT_CANNOT_BE_BLANK)
        }
    }

    private fun validateTagName(name: String) {
        if (name.isBlank()) {
            throw DomainException(ErrorCode.TAG_NAME_CANNOT_BE_BLANK)
        }
    }

    private fun mapToMemoResponse(memo: Memo, eventTitle: String): MemoResponse {
        val tags: List<MemoTagResponse> = memo.tags.mapNotNull { ref ->
            tagRepository.findById(ref.tagId)
                .map { tag -> MemoTagResponse(id = tag.id!!, name = tag.name) }
                .orElse(null)
        }

        return MemoResponse(
            id = memo.id!!,
            eventId = memo.eventId,
            eventTitle = eventTitle,
            content = memo.content,
            tags = tags,
            createdAt = memo.createdAt,
            updatedAt = memo.updatedAt
        )
    }
}
