package com.draco.ladb.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import com.draco.ladb.BuildConfig
import com.draco.ladb.R
import java.io.BufferedReader
import java.io.File
import java.io.PrintStream
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class ADB(private val context: Context) {
    companion object {
        const val MAX_OUTPUT_BUFFER_SIZE = 1024 * 16
        const val OUTPUT_BUFFER_DELAY_MS = 100L
        const val CONNECT_ATTEMPTS = 3
        const val ADB_KEY_NAME = "LADB"

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: ADB? = null
        fun getInstance(context: Context): ADB = instance ?: synchronized(this) {
            instance ?: ADB(context).also { instance = it }
        }
    }

    private val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)

    private val adbPath = "${context.applicationInfo.nativeLibraryDir}/libadb.so"
    private val scriptPath = "${context.getExternalFilesDir(null)}/script.sh"

    /**
     * Is the shell ready to handle commands?
     */
    private val _running = MutableLiveData(false)
    val running: LiveData<Boolean> = _running

    private var tryingToPair = false

    /**
     * Is the shell closed for any reason?
     */
    private val _closed = MutableLiveData(false)
    val closed: LiveData<Boolean> = _closed

    /**
     * Where shell output is stored
     */
    val outputBufferFile: File = File.createTempFile("buffer", ".txt").also {
        it.deleteOnExit()
    }

    /**
     * Single shell instance where we can pipe commands to
     */
    private var shellProcess: Process? = null

    /**
     * Returns the user buffer size if valid, else the default
     */
    fun getOutputBufferSize(): Int {
        val userValue = sharedPrefs.getString(context.getString(R.string.buffer_size_key), "16384")!!
        return try {
            Integer.parseInt(userValue)
        } catch (_: NumberFormatException) {
            MAX_OUTPUT_BUFFER_SIZE
        }
    }

    /**
     * Get a list of connected devices.
     */
    fun getDevices(): List<String> {
        val devicesProcess = adb(false, listOf("devices"))
        devicesProcess.waitFor()

        /* Get result of the command. */
        val linesRaw = BufferedReader(devicesProcess.inputStream.reader()).readLines()

        /* Split each line into the name and the state; headers and empty lines have neither. */
        val deviceLines = linesRaw.map { it ->
            it.split("\t")
        }.filter { it ->
            it.size == 2
        }

        /* Offline and unauthorized devices take no commands, so ignore them. */
        val deviceNames = deviceLines.filter { it ->
            it.last().trim() == "device"
        }.map { it ->
            it.first()
        }

        for (name in deviceNames) {
            Log.d("LINES", "<<<$name>>>")
        }

        return deviceNames
    }

    /**
     * Start the ADB server
     */
    fun initServer(): Boolean {
        if (_running.value == true || tryingToPair)
            return true

        tryingToPair = true

        val autoShell = sharedPrefs.getBoolean(context.getString(R.string.auto_shell_key), true)

        val secureSettingsGranted =
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

        if (autoShell) {
            /* Only do wireless debugging steps on compatible versions */
            if (secureSettingsGranted) {
                disableMobileDataAlwaysOn()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    cycleWirelessDebugging()
                } else if (!isUSBDebuggingEnabled()) {
                    debug(context.getString(R.string.debug_usb_debugging_on))
                    Settings.Global.putInt(
                        context.contentResolver,
                        Settings.Global.ADB_ENABLED,
                        1
                    )

                    Thread.sleep(5_000)
                }
            }

            /* Check again... */
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!isWirelessDebuggingEnabled()) {
                    debug(context.getString(R.string.debug_wireless_debugging_off_hint))
                    debug(context.getString(R.string.debug_wireless_debugging_path))
                    debug(context.getString(R.string.debug_wireless_debugging_await))

                    while (!isWirelessDebuggingEnabled()) {
                        Thread.sleep(1_000)
                    }
                }
            } else {
                if (!isUSBDebuggingEnabled()) {
                    debug(context.getString(R.string.debug_usb_debugging_off_hint))
                    debug(context.getString(R.string.debug_usb_debugging_path))
                    debug(context.getString(R.string.debug_usb_debugging_await))

                    while (!isUSBDebuggingEnabled()) {
                        Thread.sleep(1_000)
                    }
                }
            }

            val nowTime = System.currentTimeMillis()
            val maxTimeoutTime = nowTime + 10.seconds.inWholeMilliseconds
            val minDnsScanTime = (DnsDiscover.aliveTime ?: nowTime) + 3.seconds.inWholeMilliseconds
            while (true) {
                val nowTime = System.currentTimeMillis()
                val pendingResolves = DnsDiscover.pendingResolves.get()

                // Wait for a port, for pending DNS resolves, and for the minimum scan time...
                if (nowTime >= minDnsScanTime && !pendingResolves && DnsDiscover.adbPort != null) {
                    debug(context.getString(R.string.debug_dns_done))
                    break
                }

                // Or if 10 seconds pass...
                if (nowTime >= maxTimeoutTime) {
                    debug(context.getString(R.string.debug_dns_timeout))
                    break
                }

                debug(context.getString(R.string.debug_dns_await))

                Thread.sleep(1_000)
            }

            val adbPort = DnsDiscover.adbPort
            if (adbPort != null)
                debug(context.getString(R.string.debug_port_found, adbPort))
            else
                debug(context.getString(R.string.debug_port_missing))

            debug(context.getString(R.string.debug_server_starting))
            adb(false, listOf("start-server")).waitFor(1, TimeUnit.MINUTES)

            var waitProcess = false

            if (adbPort != null) {
                // Connect exits successfully even when it attaches nothing.
                for (attempt in 1..CONNECT_ATTEMPTS) {
                    adb(false, listOf("connect", "localhost:$adbPort")).waitFor(1, TimeUnit.MINUTES)

                    if (getDevices().isNotEmpty()) {
                        waitProcess = true
                        break
                    }

                    if (attempt < CONNECT_ATTEMPTS) {
                        debug(context.getString(R.string.debug_connect_retry))
                        Thread.sleep(2_000)
                    }
                }
            } else {
                waitProcess = adb(false, listOf("wait-for-device")).waitFor(1, TimeUnit.MINUTES)
            }

            if (!waitProcess) {
                debug(context.getString(R.string.debug_connect_failed))
                debug(context.getString(R.string.debug_connect_failed_hint))

                if (isMobileDataAlwaysOnEnabled()) {
                    debug(context.getString(R.string.debug_mobile_data_hint))
                    Thread.sleep(5_000)
                }

                tryingToPair = false
                return false
            }
        }

        val deviceList = getDevices()
        Log.d("DEVICES", "Devices: $deviceList")

        shellProcess = if (autoShell) {
            var argList = listOf("shell")

            /* Uh oh, multiple possible devices... */
            if (deviceList.size > 1) {
                Log.w("DEVICES", "Multiple devices detected...")
                val localDevices = deviceList.filter { it ->
                    it.contains("localhost")
                }

                /* Choose the first local device (hopefully the only). */
                if (localDevices.isNotEmpty()) {
                    val serialId = localDevices.first()
                    Log.w("DEVICES", "Choosing first local device: $serialId")
                    argList = listOf("-s", serialId, "shell")
                } else {
                    /*
                     * If no local devices to use, try to filter out
                     * any emulator devices and choose the first remaining result.
                     */

                    val nonEmulators = deviceList.filterNot { it ->
                        it.contains("emulator")
                    }

                    /* Choose the first non emulator device (hopefully the only). */
                    if (nonEmulators.isNotEmpty()) {
                        val serialId = nonEmulators.first()
                        Log.w("DEVICES", "Choosing first non-emulator device: $serialId")
                        argList = listOf("-s", serialId, "shell")
                    } else {
                        /* Otherwise, we're screwed, just choose the first device. */
                        val serialId = deviceList.first()
                        Log.w("DEVICES", "Choosing first unrecognized device: $serialId")
                        argList = listOf("-s", serialId, "shell")
                    }
                }
            }

            adb(true, argList)
        } else {
            shell(true, listOf("sh", "-l"))
        }

        sendToShellProcess("alias adb=\"$adbPath\"")

        if (!secureSettingsGranted) {
            sendToShellProcess("pm grant ${BuildConfig.APPLICATION_ID} android.permission.WRITE_SECURE_SETTINGS &> /dev/null")
        }

        if (autoShell)
            sendToShellProcess("echo '${context.getString(R.string.shell_entered_adb)}'")
        else
            sendToShellProcess("echo '${context.getString(R.string.shell_entered_non_adb)}'")

        val startupCommand = sharedPrefs.getString(
            context.getString(R.string.startup_command_key),
            context.getString(R.string.startup_command_default)
        )!!
        if (startupCommand.isNotEmpty())
            sendToShellProcess(startupCommand)

        _running.postValue(true)
        tryingToPair = false

        return true
    }

    private fun isWirelessDebuggingEnabled() =
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1

    private fun isUSBDebuggingEnabled() =
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1

    private fun isMobileDataAlwaysOnEnabled() =
        Settings.Global.getInt(context.contentResolver, "mobile_data_always_on", 0) == 1

    /**
     * Settings.Global.MOBILE_DATA_ALWAYS_ON creates a bug
     * with the DNS resolver.
     */
    fun disableMobileDataAlwaysOn() {
        val secureSettingsGranted =
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

        if (secureSettingsGranted) {
            // Only turn it off if it's already on.
            if (isMobileDataAlwaysOnEnabled()) {
                debug(context.getString(R.string.debug_mobile_data_disabling))
                Settings.Global.putInt(
                    context.contentResolver,
                    "mobile_data_always_on",
                    0
                )
                Thread.sleep(3_000)
            }
        }
    }

    /**
     * Cycles wireless debugging to get a new port to scan.
     *
     * For whatever reason, Wireless Debugging needs to be
     * cycled twice to broadcast a valid port.
     */
    fun cycleWirelessDebugging() {
        val secureSettingsGranted =
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

        if (secureSettingsGranted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                debug(context.getString(R.string.debug_cycling_wireless_debugging))
                // Only turn it off if it's already on.
                if (isWirelessDebuggingEnabled()) {
                    debug(context.getString(R.string.debug_wireless_debugging_turning_off))
                    Settings.Global.putInt(
                        context.contentResolver,
                        "adb_wifi_enabled",
                        0
                    )
                    Thread.sleep(3_000)
                }

                debug(context.getString(R.string.debug_wireless_debugging_turning_on))
                Settings.Global.putInt(
                    context.contentResolver,
                    "adb_wifi_enabled",
                    1
                )
                Thread.sleep(3_000)

                debug(context.getString(R.string.debug_wireless_debugging_turning_off))
                Settings.Global.putInt(
                    context.contentResolver,
                    "adb_wifi_enabled",
                    0
                )
                Thread.sleep(3_000)

                debug(context.getString(R.string.debug_wireless_debugging_turning_on))
                Settings.Global.putInt(
                    context.contentResolver,
                    "adb_wifi_enabled",
                    1
                )
                Thread.sleep(3_000)
            }
        }
    }

    /**
     * Wait restart the shell once it dies
     */
    fun waitForDeathAndReset() {
        while (true) {
            /* Do not falsely claim the shell is dead if we haven't even initialized it yet */
            if (tryingToPair) continue

            shellProcess?.waitFor()
            _running.postValue(false)
            debug(context.getString(R.string.debug_shell_dead))
            adb(false, listOf("kill-server")).waitFor()

            Thread.sleep(3_000)
            initServer()
        }
    }

    /**
     * Ask the device to pair on Android 11+ devices
     */
    fun pair(port: String, pairingCode: String): Boolean {
        val pairShell = adb(false, listOf("pair", "localhost:$port"))

        /* Sleep to allow shell to catch up */
        Thread.sleep(5000)

        /* Pipe pairing code */
        PrintStream(pairShell.outputStream).apply {
            println(pairingCode)
            flush()
        }

        /* Continue once finished pairing (or 30s elapses) */
        val paired = pairShell.waitFor(30, TimeUnit.SECONDS) && pairShell.exitValue() == 0
        pairShell.destroyForcibly().waitFor()

        val killShell = adb(false, listOf("kill-server"))
        killShell.waitFor(3, TimeUnit.SECONDS)
        killShell.destroyForcibly()

        return paired
    }

    /**
     * Send a raw ADB command
     */
    private fun adb(redirect: Boolean, command: List<String>): Process {
        val commandList = command.toMutableList().also {
            it.add(0, adbPath)
        }
        return shell(redirect, commandList)
    }

    /**
     * Send a raw shell command
     */
    private fun shell(redirect: Boolean, command: List<String>): Process {
        val processBuilder = ProcessBuilder(command)
            .directory(context.filesDir)
            .apply {
                if (redirect) {
                    redirectErrorStream(true)
                    redirectOutput(outputBufferFile)
                }

                environment().apply {
                    put("HOME", context.filesDir.path)
                    put("TMPDIR", context.cacheDir.path)
                    put("LOGNAME", ADB_KEY_NAME)
                    put("HOSTNAME", ADB_KEY_NAME)
                }
            }

        return processBuilder.start()!!
    }

    /**
     * Send commands directly to the shell process
     */
    fun sendToShellProcess(msg: String) {
        if (shellProcess == null || shellProcess?.outputStream == null)
            return
        PrintStream(shellProcess!!.outputStream!!).apply {
            println(msg)
            flush()
        }
    }

    /**
     * Write a debug message to the user
     */
    fun debug(msg: String) {
        synchronized(outputBufferFile) {
            Log.d("DEBUG", msg)
            if (outputBufferFile.exists())
                outputBufferFile.appendText("* $msg" + System.lineSeparator())
        }
    }
}