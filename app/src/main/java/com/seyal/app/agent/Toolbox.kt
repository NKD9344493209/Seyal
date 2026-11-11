package com.seyal.app.agent

import com.seyal.app.tools.AgentTools
import com.seyal.app.tools.FileTools
import com.seyal.app.tools.MailTools
import com.seyal.app.tools.NotificationTools
import com.seyal.app.tools.PhoneTools
import com.seyal.app.tools.ScreenTools
import com.seyal.app.tools.WebTools

/** Every tool Seyal has, and the ones that can work right now. */
object Toolbox {
    val all: List<Tool> by lazy { PhoneTools.all + FileTools.all + MailTools.all + WebTools.all + NotificationTools.all + ScreenTools.all + AgentTools.all }

    fun available(): List<Tool> = all.filter { runCatching { it.available() }.getOrDefault(false) }

    fun get(name: String): Tool? = all.firstOrNull { it.name == name }

    /** Tools the user can set a level for, grouped for the Control screen. */
    val groups: List<Pair<String, String>> = listOf(
        "comms" to "Messages and calls", "phone" to "Phone", "mail" to "Mail", "web" to "Web", "notifications" to "Notifications", "screen" to "Screen control", "agent" to "Agent",
    )
}
