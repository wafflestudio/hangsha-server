package com.team1.hangsha.common.upload

import com.oracle.bmc.objectstorage.ObjectStorage
import com.oracle.bmc.objectstorage.requests.PutObjectRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import com.oracle.bmc.objectstorage.requests.HeadObjectRequest
import java.io.ByteArrayInputStream
import java.security.MessageDigest

@Service
class OciUploadService(
    private val objectStorage: ObjectStorage,
    @Value("\${oci.storage.namespace}")
    private val namespace: String,
    @Value("\${oci.storage.bucket}")
    private val bucket: String,
    @Value("\${oci.storage.region}")
    private val region: String,
) {
    fun uploadFile(prefix: String?, file: MultipartFile): String {
        val objectName = buildObjectName(prefix, file.originalFilename)
        val contentType = file.contentType ?: "application/octet-stream"

        file.inputStream.use { input ->
            val request = PutObjectRequest.builder()
                .namespaceName(namespace)
                .bucketName(bucket)
                .objectName(objectName)
                .contentLength(file.size)
                .contentType(contentType)
                .putObjectBody(input)
                .build()
            objectStorage.putObject(request)
        }

        return buildPublicUrl(objectName)
    }

    private fun buildObjectName(prefix: String?, originalFilename: String?): String {
        val safePrefix = prefix?.trim()?.trim('/')?.takeIf { it.isNotBlank() }
        val filename = originalFilename?.takeIf { it.isNotBlank() } ?: "upload-${UUID.randomUUID()}"
        return if (safePrefix == null) filename else "$safePrefix/$filename"
    }

    private fun buildPublicUrl(objectName: String): String {
        val encodedObjectName = URLEncoder.encode(objectName, StandardCharsets.UTF_8)
            .replace("+", "%20")
        return "https://objectstorage.$region.oraclecloud.com/n/$namespace/b/$bucket/o/$encodedObjectName"
    }

    fun uploadBytesIfAbsent(
        prefix: String?,
        originalFilename: String?,
        bytes: ByteArray,
        contentType: String = "application/octet-stream",
    ): String {
        require(bytes.isNotEmpty()) { "empty bytes" }

        val ext = extractExtension(originalFilename, contentType, bytes)
        val sha256 = sha256Hex(bytes)
        val objectName = buildObjectName(prefix, "$sha256.$ext")

        if (!exists(objectName)) {
            ByteArrayInputStream(bytes).use { input ->
                val request = PutObjectRequest.builder()
                    .namespaceName(namespace)
                    .bucketName(bucket)
                    .objectName(objectName)
                    .contentLength(bytes.size.toLong())
                    .contentType(contentType)
                    .putObjectBody(input)
                    .build()
                objectStorage.putObject(request)
            }
        }

        return buildPublicUrl(objectName)
    }

    private fun exists(objectName: String): Boolean {
        return try {
            val request = HeadObjectRequest.builder()
                .namespaceName(namespace)
                .bucketName(bucket)
                .objectName(objectName)
                .build()
            objectStorage.headObject(request)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun extractExtension(
        originalFilename: String?,
        contentType: String,
        bytes: ByteArray,
    ): String {
        val fromName = originalFilename
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }

        if (fromName != null && fromName != "bin") return fromName

        detectImageExtension(bytes)?.let { return it }

        return when (contentType.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/bmp" -> "bmp"
            else -> "bin"
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun detectImageExtension(bytes: ByteArray): String? {
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
                return "jpg"
            }
        }

        if (bytes.size >= 8) {
            if (
                bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte() &&
                bytes[4] == 0x0D.toByte() &&
                bytes[5] == 0x0A.toByte() &&
                bytes[6] == 0x1A.toByte() &&
                bytes[7] == 0x0A.toByte()
            ) {
                return "png"
            }
        }

        if (bytes.size >= 6) {
            val header = bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII)
            if (header == "GIF87a" || header == "GIF89a") {
                return "gif"
            }
        }

        if (bytes.size >= 12) {
            val riff = bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII)
            val webp = bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII)
            if (riff == "RIFF" && webp == "WEBP") {
                return "webp"
            }
        }

        return null
    }
}
