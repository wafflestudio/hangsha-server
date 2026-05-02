package com.team1.hangsha.common.upload

import com.oracle.bmc.objectstorage.ObjectStorage
import com.oracle.bmc.objectstorage.requests.PutObjectRequest
import com.team1.hangsha.common.error.DomainException
import com.team1.hangsha.common.error.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

// OciUploadService가 common에도 존재하는데 main에도 존재...?

@Service
class OciUploadService(
    private val objectStorage: ObjectStorage,
    private val uploadProperties: UploadProperties,
    @Value("\${oci.storage.namespace}") private val namespace: String,
    @Value("\${oci.storage.bucket}") private val bucket: String,
    @Value("\${oci.storage.region}") private val region: String,
) {
    fun uploadFile(prefix: String?, file: MultipartFile): String {
        if (file.isEmpty || file.size <= 0) {
            throw DomainException(ErrorCode.UPLOAD_FILE_EMPTY)
        }
        if (file.size > uploadProperties.maxSizeBytes) {
            throw DomainException(ErrorCode.UPLOAD_FAILED, "파일이 너무 큽니다 (max=${uploadProperties.maxSizeBytes} bytes)")
        }

        val ext = guessExtension(file.originalFilename, file.contentType ?: "")
        val safePrefix = sanitizePrefix(prefix)
        val objectName = "$safePrefix/${UUID.randomUUID()}.$ext"

        try {
            val request = PutObjectRequest.builder()
                .namespaceName(namespace)
                .bucketName(bucket)
                .objectName(objectName)
                .contentLength(file.size)
                .contentType(file.contentType)
                .putObjectBody(file.inputStream)
                .build()
            objectStorage.putObject(request)
        } catch (e: Exception) {
            throw DomainException(ErrorCode.UPLOAD_FAILED, cause = e)
        }

        return buildObjectUrl(objectName)
    }

    private fun buildObjectUrl(objectName: String): String {
        val encoded = encodeObjectName(objectName)
        return "https://objectstorage.$region.oraclecloud.com/n/$namespace/b/$bucket/o/$encoded"
    }

    private fun encodeObjectName(objectName: String): String {
        return objectName
            .split('/')
            .joinToString("/") { segment ->
                URLEncoder.encode(segment, StandardCharsets.UTF_8)
                    .replace("+", "%20")
            }
    }

    private fun guessExtension(originalFilename: String?, contentType: String): String {
        val fromName = originalFilename
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase()
            ?.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }

        if (!fromName.isNullOrBlank()) return fromName

        return when (contentType.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> "bin"
        }
    }

    private fun sanitizePrefix(prefix: String?): String {
        val trimmed = prefix?.trim()?.trim('/')?.ifBlank { null } ?: "uploads/tmp"
        if (trimmed.contains("..")) {
            throw DomainException(ErrorCode.INVALID_REQUEST, "Invalid path")
        }
        return trimmed
    }
}
