package com.team1.hangsha.user.verification

import com.oracle.bmc.auth.BasicAuthenticationDetailsProvider
import com.oracle.bmc.emaildataplane.EmailDPClient
import com.oracle.bmc.emaildataplane.model.EmailAddress
import com.oracle.bmc.emaildataplane.model.Recipients
import com.oracle.bmc.emaildataplane.model.Sender
import com.oracle.bmc.emaildataplane.model.SubmitEmailDetails
import com.oracle.bmc.emaildataplane.requests.SubmitEmailRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * OCI Email Delivery로 실제 인증 메일을 보내는 구현체.
 *
 * 발송은 Email Delivery의 데이터 플레인 API(submitEmail)를 쓴다.
 * SMTP가 아니라서 별도의 SMTP 자격증명이 필요 없고, Vault/Object Storage와 같은
 * OCI 인증(ociAuthProvider)을 그대로 재사용한다.
 *
 * from 주소는 미리 등록된 Approved Sender여야 하며, 아니면 발송이 거부된다.
 */
@Component
@ConditionalOnProperty(
    prefix = "email",
    name = ["sender"],
    havingValue = "oci",
)
class OciEmailSender(
    authProvider: BasicAuthenticationDetailsProvider,
    @Value("\${oci.storage.region}")
    region: String,
    /** Approved Sender가 등록된 컴파트먼트. sender 안에 들어가며, 빠지면 400이 난다. */
    @Value("\${email.oci.compartment-id}")
    private val compartmentId: String,
    @Value("\${email.oci.from-address}")
    private val fromAddress: String,
    @Value("\${email.oci.from-name:행샤}")
    private val fromName: String,
) : EmailSender {

    private val log = LoggerFactory.getLogger(javaClass)

    private val client: EmailDPClient = EmailDPClient.builder()
        .region(region)
        .build(authProvider)

    override fun sendVerificationCode(to: String, code: String, ttl: Duration) {
        val details = SubmitEmailDetails.builder()
            .sender(
                Sender.builder()
                    .compartmentId(compartmentId)
                    .senderAddress(
                        EmailAddress.builder()
                            .email(fromAddress)
                            .name(fromName)
                            .build(),
                    )
                    .build(),
            )
            .recipients(
                Recipients.builder()
                    .to(listOf(EmailAddress.builder().email(to).build()))
                    .build(),
            )
            .subject("[행샤] 이메일 인증번호 안내")
            .bodyText(buildBody(code, ttl))
            .build()

        val response = client.submitEmail(
            SubmitEmailRequest.builder().submitEmailDetails(details).build(),
        )

        log.info(
            "인증 메일 발송 완료 to={} messageId={}",
            to,
            response.emailSubmittedResponse?.messageId,
        )
    }

    private fun buildBody(code: String, ttl: Duration) = buildString {
        appendLine("인증번호: $code")
        appendLine()
        appendLine("${ttl.toMinutes()}분 이내에 입력해주세요.")
        appendLine()
        append("본 메일은 발신 전용입니다.")
    }
}
