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
    fun send(to: List<String>, subject: String, body: String, cc: List<String> = emptyList()) {
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
            setText(body, "UTF-8")
        }
        try {
            Transport.send(msg)
        } catch (e: javax.mail.AuthenticationFailedException) {
            throw IllegalStateException("Gmail login failed. Check the address and the 16-letter app password in Control.")
        }
    }
}
