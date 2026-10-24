package com.david.gps3dar.music

import android.service.notification.NotificationListenerService

/** Permission bridge for MediaSessionManager. No notification content is read or stored. */
class MusicSessionAccessService : NotificationListenerService()
