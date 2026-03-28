package com.team1.hangsha.common.upload

import com.oracle.bmc.objectstorage.ObjectStorage
import com.oracle.bmc.objectstorage.requests.PutObjectRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

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
}
