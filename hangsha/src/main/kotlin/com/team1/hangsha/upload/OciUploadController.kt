package com.team1.hangsha.upload

import com.team1.hangsha.common.upload.OciUploadService
import com.team1.hangsha.common.upload.dto.UploadResponse
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/v1/uploads/oci")
class OciUploadController(
    private val ociUploadService: OciUploadService,
) {
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @RequestParam("file") file: MultipartFile,
        @RequestParam("prefix", required = false) prefix: String?,
    ): UploadResponse {
        val url = ociUploadService.uploadFile(prefix, file)
        return UploadResponse(url)
    }
}
