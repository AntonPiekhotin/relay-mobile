package com.relay.push

import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter

const val PUSH_DIALOG_ID = "dialogId"

class IosNotificationPresenter {
    private val center = UNUserNotificationCenter.currentNotificationCenter()

    fun requestAuthorization() {
        center.requestAuthorizationWithOptions(
            UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge
        ) { _, _ -> }
    }

    fun show(message: PushDisplay.Message) {
        val content = UNMutableNotificationContent()
        content.setTitle(message.title)
        content.setBody(message.body)
        content.setUserInfo(mapOf(PUSH_DIALOG_ID to message.dialogId))
        center.addNotificationRequest(
            UNNotificationRequest.requestWithIdentifier(
                identifier = message.dialogId,
                content = content,
                trigger = null
            ),
            null
        )
    }

    fun dismiss(dialogId: String) {
        center.removeDeliveredNotificationsWithIdentifiers(listOf(dialogId))
    }
}
