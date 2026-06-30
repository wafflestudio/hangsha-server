package com.team1.hangsha.tag.service

import com.team1.hangsha.common.error.DomainException
import com.team1.hangsha.common.error.ErrorCode
import com.team1.hangsha.tag.dto.*
import com.team1.hangsha.tag.dto.core.TagDto
import com.team1.hangsha.tag.model.Tag
import com.team1.hangsha.tag.repository.TagRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TagService(
    private val tagRepository: TagRepository
) {
    @Transactional(readOnly = true)
    fun getAllTags(userId: Long): List<TagDto> {
        return tagRepository.findAllByUserId(userId)
            .map { TagDto(it.id!!, it.name) }
    }

    @Transactional
    fun createTag(userId: Long, req: CreateTagRequest): CreateTagResponse {
        validateTagName(req.name)

        // 해당 유저에게 이미 같은 이름의 태그가 있는지 확인
        if (tagRepository.findByUserIdAndName(userId, req.name).isPresent) {
            throw DomainException(ErrorCode.TAG_ALREADY_EXISTS)
        }

        val tag = tagRepository.save(Tag(userId = userId, name = req.name))
        return CreateTagResponse(tag.id!!, tag.name)
    }

    // 태그 이름 변경 (전역 변경)
    @Transactional
    fun updateTag(userId: Long, tagId: Long, req: UpdateTagRequest): UpdateTagResponse {
        validateTagName(req.name)

        val tag = tagRepository.findById(tagId)
            .orElseThrow { DomainException(ErrorCode.TAG_NOT_FOUND) }

        // 소유권 확인
        if (tag.userId != userId) {
            throw DomainException(ErrorCode.TAG_NOT_FOUND) // 혹은 권한 없음 예외
        }

        // 중복 이름 체크 (본인 이름 제외)
        if (tag.name != req.name && tagRepository.findByUserIdAndName(userId, req.name).isPresent) {
            throw DomainException(ErrorCode.TAG_ALREADY_EXISTS)
        }

        val updatedTag = tagRepository.save(tag.copy(name = req.name))
        return UpdateTagResponse(updatedTag.id!!, updatedTag.name)
    }

    @Transactional
    fun deleteTag(userId: Long, tagId: Long) {
        val tag = tagRepository.findById(tagId)
            .orElseThrow { DomainException(ErrorCode.TAG_NOT_FOUND) }

        if (tag.userId != userId) {
            throw DomainException(ErrorCode.TAG_NOT_FOUND)
        }

        tagRepository.delete(tag)
    }

    private fun validateTagName(name: String) {
        if (name.isBlank()) {
            throw DomainException(ErrorCode.TAG_NAME_CANNOT_BE_BLANK)
        }
    }
}
