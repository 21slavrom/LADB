package com.draco.ladb.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.draco.ladb.R
import com.draco.ladb.receivers.PairingReceiver

class PairingService : Service() {
    private lateinit var notificationManager: NotificationManager

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.widget.Toast.makeText(this, "Starting Pairing Service...", android.widget.Toast.LENGTH_SHORT).show()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Pairing",
            NotificationManager.IMPORTANCE_HIGH
        )
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): android.app.Notification {
        val remoteInput = RemoteInput.Builder(PairingReceiver.REMOTE_INPUT_KEY).run {
            setLabel("Enter Port")
            build()
        }

        val intent = Intent(this, PairingReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_MUTABLE
        )

        val action = NotificationCompat.Action.Builder(
            R.drawable.ic_baseline_adb_24,
            "Enter",
            pendingIntent
        ).addRemoteInput(remoteInput).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_adb_24)
            .setContentTitle("Wireless Pairing")
            .setContentText("Enter Port")
            .setOngoing(true)
            .addAction(action)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    companion object {
        const val CHANNEL_ID = "pairing_channel_v2"
        const val NOTIFICATION_ID = 1
    }
}
