package com.draco.ladb.receivers

import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.draco.ladb.R
import com.draco.ladb.services.PairingService
import com.draco.ladb.utils.ADB
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.net.nsd.NsdManager
import com.draco.ladb.utils.DnsDiscover

class PairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        if (intent.action == ACTION_RETRY) {
            prefs.edit().remove(PREF_PENDING_PORT).apply()
            updateNotification(context, notificationManager, "Enter Port", true, "Enter Port")
            return
        }

        val results = RemoteInput.getResultsFromIntent(intent) ?: return
        
        DnsDiscover.getInstance(context, nsdManager).scanAdbPorts()
        
        val adb = ADB.getInstance(context)
        val coroutineScope = CoroutineScope(Dispatchers.IO)

        val rawInput = results.getCharSequence(REMOTE_INPUT_KEY).toString().trim()
        Log.d("LADB_Pairing", "Input received: '$rawInput'")

        val parts = rawInput.split(Regex("\\s+"))
        
        var port: String? = null
        var code: String? = null

        if (parts.size >= 2) {
            port = parsePort(parts[0])
            code = parts[1]
        } else if (parts.size == 1) {
            val input = parts[0]
            val pendingPort = prefs.getString(PREF_PENDING_PORT, null)

            if (input.contains(":")) {
                val parsedPort = parsePort(input)
                if (parsedPort != null) {
                    prefs.edit().putString(PREF_PENDING_PORT, parsedPort).apply()
                    updateNotification(context, notificationManager, "Port $parsedPort saved. Enter Pairing Code:", true, "Enter Code")
                    return
                }
            } else if (pendingPort != null) {
                port = pendingPort
                code = input
                prefs.edit().remove(PREF_PENDING_PORT).apply()
            } else {
                val parsedPort = parsePort(input)
                if (parsedPort != null && (parsedPort.toIntOrNull() ?: 70000) <= 65535) {
                    prefs.edit().putString(PREF_PENDING_PORT, parsedPort).apply()
                    updateNotification(context, notificationManager, "Port $parsedPort saved. Enter Pairing Code:", true, "Enter Code")
                    return
                } else {
                     updateNotification(context, notificationManager, "Invalid Port. Enter Port:", true, "Enter Port")
                     return
                }
            }
        }

        if (port != null && code != null) {
            coroutineScope.launch {
                try {
                    updateNotification(context, notificationManager, "Pairing $port with $code...", false, "")
                    
                    val output = adb.pair(port, code)
                    Log.d("LADB_Pairing", "Output: $output")
                    
                    val isSuccess = output.contains("Successfully paired", ignoreCase = true)
                    
                    if (isSuccess) {
                        updateNotification(context, notificationManager, "Success! Connecting...", false, "")
                        val serverStarted = adb.initServer()
                        if (serverStarted) {
                             updateNotification(context, notificationManager, "Connected! You can now use LADB.", false, "")
                        } else {
                             showErrorNotification(context, notificationManager, "Paired, but connection failed.")
                        }
                    } else {
                        val displayLog = if (output.length > 100) output.take(100) + "..." else output
                        showErrorNotification(context, notificationManager, "Failed: $displayLog")
                    }
                } catch (e: Exception) {
                    Log.e("LADB_Pairing", "Exception during pair", e)
                    showErrorNotification(context, notificationManager, "Error: ${e.message}")
                }
            }
        } else {
            updateNotification(context, notificationManager, "Invalid input. Need Port + Code.", true, "Enter Port")
        }
    }

    private fun showErrorNotification(context: Context, manager: NotificationManager, text: String) {
        val intent = Intent(context, PairingReceiver::class.java).apply {
            action = ACTION_RETRY
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val action = NotificationCompat.Action.Builder(
            R.drawable.ic_baseline_restore_24dp,
            "Retry",
            pendingIntent
        ).build()

        val notification = NotificationCompat.Builder(context, PairingService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_adb_24)
            .setContentTitle("Wireless Pairing")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(action)
            .build()

        manager.notify(PairingService.NOTIFICATION_ID, notification)
    }

    private fun parsePort(input: String): String? {
        val portStr = if (input.contains(":")) {
            input.substringAfterLast(":")
        } else {
            input
        }
        return if (portStr.toIntOrNull() != null) portStr else null
    }

    private fun updateNotification(context: Context, manager: NotificationManager, text: String, showAction: Boolean, inputLabel: String) {
        val builder = NotificationCompat.Builder(context, PairingService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_adb_24)
            .setContentTitle("Wireless Pairing")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (showAction) {
            val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY).run {
                setLabel(inputLabel)
                build()
            }

            val intent = Intent(context, PairingReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val action = NotificationCompat.Action.Builder(
                R.drawable.ic_baseline_adb_24,
                "Enter",
                pendingIntent
            ).addRemoteInput(remoteInput).build()
            
            builder.addAction(action)
        }

        manager.notify(PairingService.NOTIFICATION_ID, builder.build())
    }

    companion object {
        const val REMOTE_INPUT_KEY = "pairing_input"
        const val PREF_PENDING_PORT = "pending_pairing_port"
        const val ACTION_RETRY = "com.draco.ladb.ACTION_RETRY"
    }
}
