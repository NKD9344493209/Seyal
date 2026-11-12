package com.seyal.app.device

import com.seyal.app.data.Settings
import java.util.Properties
import javax.mail.Message
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/** Sends mail through Gmail SMTP with the same app password used for reading. */
object MailSender {
    class Attachment(val name: String, val mime: String, val bytes: ByteArray)

    fun send(to: List<String>, subject: String, body: String, cc: List<String> = emptyList(), attachment: Attachment? = null) {
        val email = Settings.gmailAddress
        val pass = Settings.gmailAppPassword
        if (email.isBlank() || pass.isBlank()) throw IllegalStateException("Gmail isn't connected. Control → Gmail: add your address and an app password.")
        val props = Properties().apply {
            put("mail.smtp.host", "smtp.gmail.com")
            put("mail.smtp.port", "465")
            put("mail.smtp.auth", "true")
            put("mail.smtp.ssl.enable", "true")
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "25000")
        }
        val session = Session.getInstance(props, object : javax.mail.Authenticator() {
            override fun getPasswordAuthentication() = javax.mail.PasswordAuthentication(email, pass)
        })
        val msg = MimeMessage(session).apply {
            setFrom(InternetAddress(email, Settings.userName.ifBlank { null }))
            to.forEach { addRecipient(Message.RecipientType.TO, InternetAddress(it.trim())) }
            cc.forEach { addRecipient(Message.RecipientType.CC, InternetAddress(it.trim())) }
            setSubject(subject, "UTF-8")
            if (attachment == null) setText(body, "UTF-8")
            else {
                val text = javax.mail.internet.MimeBodyPart().apply { setText(body, "UTF-8") }
                val file = javax.mail.internet.MimeBodyPart().apply {
                    setDataHandler(javax.activation.DataHandler(javax.mail.util.ByteArrayDataSource(attachment.bytes, attachment.mime)))
                    setFileName(attachment.name)
                }
                setContent(javax.mail.internet.MimeMultipart().apply { addBodyPart(text); addBodyPart(file) })
            }
        }
        try {
            Transport.send(msg)
        } catch (e: javax.mail.AuthenticationFailedException) {
            throw IllegalStateException("Gmail login failed. Check the address and the 16-letter app password in Control.")
        }
    }
}
