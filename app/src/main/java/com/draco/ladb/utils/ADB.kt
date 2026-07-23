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
import androidx.core.content.edit

class ADB(private val context: Context) {
    companion object {
        const val MAX_OUTPUT_BUFFER_SIZE = 1024 * 16
        const val OUTPUT_BUFFER_DELAY_MS = 100L
        const val LAST_CONNECTED_PORT_KEY = "ladb_last_connected_port"

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

    private var manualDebugPort: String? = null
    private var lastConnectedPort: String? = null

    init {
        // Load last connected port from shared preferences
        lastConnectedPort = sharedPrefs.getString(LAST_CONNECTED_PORT_KEY, null)
    }

    fun setManualDebugPort(port: String) { manualDebugPort = port; lastConnectedPort = port }

    private fun saveLastConnectedPort(port: String?) {
        sharedPrefs.edit { putString(LAST_CONNECTED_PORT_KEY, port) }
    }

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

        /* Remove "List of devices attached" line if it exists (it should). */
        val deviceLines = linesRaw.filterNot { it ->
            it.contains("List of devices attached")
        }

        /* Just get first part with device name/IP and port. */
        var deviceNames = deviceLines.map { it ->
            it.split("\t").first()
        }

        /* Remove any empty lines. */
        deviceNames = deviceNames.filterNot { it ->
            it.isEmpty()
        }

        for (name in deviceNames) {
            Log.d("LINES", "<<<$name>>>")
        }

        return deviceNames
    }

    /**
     * Start the ADB server.
     */
    fun initServer(): InitResult {
        if (_running.value == true || tryingToPair) {
            return InitResult.Success
        }

        tryingToPair = true

        return try {
            val autoShell = sharedPrefs.getBoolean(
                context.getString(R.string.auto_shell_key),
                true
            )

            val secureSettingsGranted =
                context.checkSelfPermission(
                    Manifest.permission.WRITE_SECURE_SETTINGS
                ) == PackageManager.PERMISSION_GRANTED

            val candidatePorts = linkedSetOf<String>()

            if (autoShell) {
                if (secureSettingsGranted) {
                    disableMobileDataAlwaysOn()

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        if (lastConnectedPort == null) {
                            cycleWirelessDebugging()
                        } else {
                            enableWirelessDebugging()
                        }
                    } else if (!isUSBDebuggingEnabled()) {
                        debug("Turning on USB debugging...")

                        Settings.Global.putInt(
                            context.contentResolver,
                            Settings.Global.ADB_ENABLED,
                            1
                        )

                        Thread.sleep(5_000)
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (!isWirelessDebuggingEnabled()) {
                        debug("Wireless debugging is not enabled!")
                        debug("Settings -> Developer options -> Wireless debugging")
                        debug("Waiting for wireless debugging...")

                        while (!isWirelessDebuggingEnabled()) {
                            Thread.sleep(1_000)
                        }
                    }

                    val startTime = System.currentTimeMillis()
                    val maxTimeoutTime =
                        startTime + 10.seconds.inWholeMilliseconds
                    val minDnsScanTime =
                        (DnsDiscover.aliveTime ?: startTime) +
                            3.seconds.inWholeMilliseconds

                    while (true) {
                        val currentTime = System.currentTimeMillis()
                        val pendingResolves = DnsDiscover.pendingResolves.get()

                        if (currentTime >= minDnsScanTime && !pendingResolves) {
                            debug("DNS resolver done...")
                            break
                        }

                        if (currentTime >= maxTimeoutTime) {
                            debug("DNS resolver took too long! Skipping...")
                            break
                        }

                        debug("Awaiting DNS resolver...")
                        Thread.sleep(1_000)
                    }

                    val discoveredPort = DnsDiscover.adbPort?.toString()

                    if (discoveredPort != null) {
                        debug("Best ADB port discovered: $discoveredPort")
                    } else {
                        debug("No ADB port discovered, fallback...")
                    }

                    lastConnectedPort
                        ?.takeIf(::isValidAdbPort)
                        ?.let(candidatePorts::add)

                    discoveredPort
                        ?.takeIf(::isValidAdbPort)
                        ?.let(candidatePorts::add)

                    manualDebugPort
                        ?.takeIf(::isValidAdbPort)
                        ?.let(candidatePorts::add)

                    if (candidatePorts.isEmpty()) {
                        val message =
                            "No debug port found. Please enter the debug port " +
                                "(ADB connect port) shown in your device's " +
                                "Wireless Debugging screen."

                        debug(message)
                        appendToOutput("[LADB] $message")

                        return InitResult.NeedsPort
                    }
                } else {
                    if (!isUSBDebuggingEnabled()) {
                        debug("USB debugging is not enabled!")
                        debug("Settings -> Developer options -> USB debugging")
                        debug("Waiting for USB debugging...")

                        while (!isUSBDebuggingEnabled()) {
                            Thread.sleep(1_000)
                        }
                    }
                }

                debug("Starting ADB server...")

                val startServerProcess = adb(
                    false,
                    listOf("start-server")
                )

                val serverStarted = startServerProcess.waitFor(
                    1,
                    TimeUnit.MINUTES
                )

                if (!serverStarted) {
                    startServerProcess.destroyForcibly()
                    debug("ADB server startup timed out.")
                    return InitResult.Failure
                }

                if (startServerProcess.exitValue() != 0) {
                    debug("ADB server failed to start.")
                    return InitResult.Failure
                }
            }

            connectionAndStart(candidatePorts.toList())
        } catch (exception: Exception) {
            Log.e("ADB", "Failed to initialize ADB server", exception)
            debug("Failed to initialize ADB: ${exception.message}")
            InitResult.Failure
        } finally {
            tryingToPair = false
        }
    }

    private fun connectionAndStart(candidatePorts: List<String>): InitResult {
        val autoShell = sharedPrefs.getBoolean(
            context.getString(R.string.auto_shell_key),
            true
        )

        var connectedPort: String? = null
        var deviceList: List<String> = emptyList()

        if (autoShell) {
            if (candidatePorts.isNotEmpty()) {
                for (port in candidatePorts) {
                    debug("Connecting to ADB on port $port...")

                    val connectProcess = adb(
                        false,
                        listOf("connect", "localhost:$port")
                    )

                    val completed = connectProcess.waitFor(
                        1,
                        TimeUnit.MINUTES
                    )

                    if (!completed) {
                        connectProcess.destroyForcibly()
                        debug("ADB connection timed out on port $port.")
                        continue
                    }

                    deviceList = getDevices()

                    val expectedSerial = "localhost:$port"

                    if (deviceList.contains(expectedSerial)) {
                        debug("Connected to ADB on port $port.")
                        connectedPort = port
                        break
                    }

                    debug("No connected ADB device found on port $port.")
                }

                if (connectedPort == null) {
                    debug("Your device didn't connect to LADB")
                    debug("Please check the wireless debugging port and pairing.")

                    if (isMobileDataAlwaysOnEnabled()) {
                        debug(
                            "Please disable 'Mobile data always on' " +
                                "in Developer Settings!"
                        )
                        Thread.sleep(5_000)
                    }

                    lastConnectedPort = null
                    return InitResult.NeedsPort
                }
            } else {
                debug("Waiting for USB ADB device...")

                val waitProcess = adb(
                    false,
                    listOf("wait-for-device")
                )

                val completed = waitProcess.waitFor(
                    1,
                    TimeUnit.MINUTES
                )

                if (!completed) {
                    waitProcess.destroyForcibly()
                    debug("Your device didn't connect to LADB")
                    debug("If a reboot doesn't work, please contact support")
                    return InitResult.Failure
                }

                deviceList = getDevices()
            }

            if (deviceList.isEmpty()) {
                debug("No connected ADB devices found.")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    lastConnectedPort = null
                    return InitResult.NeedsPort
                }

                return InitResult.Failure
            }

            Log.d("DEVICES", "Devices: $deviceList")
        }

        shellProcess = if (autoShell) {
            val argList = buildShellArguments(
                deviceList = deviceList,
                connectedPort = connectedPort
            )

            adb(true, argList)
        } else {
            shell(true, listOf("sh", "-l"))
        }

        sendToShellProcess("alias adb=\"$adbPath\"")

        val secureSettingsGranted =
            context.checkSelfPermission(
                Manifest.permission.WRITE_SECURE_SETTINGS
            ) == PackageManager.PERMISSION_GRANTED

        if (!secureSettingsGranted) {
            sendToShellProcess(
                "pm grant ${BuildConfig.APPLICATION_ID} " +
                    "android.permission.WRITE_SECURE_SETTINGS &> /dev/null"
            )
        }

        if (autoShell) {
            sendToShellProcess("echo 'Entered adb shell'")
        } else {
            sendToShellProcess("echo 'Entered non-adb shell'")
        }

        val startupCommand = sharedPrefs.getString(
            context.getString(R.string.startup_command_key),
            "echo 'Success! ※\\(^o^)/※'"
        ).orEmpty()

        if (startupCommand.isNotEmpty()) {
            sendToShellProcess(startupCommand)
        }

        if (connectedPort != null) {
            lastConnectedPort = connectedPort
            saveLastConnectedPort(connectedPort)
        }

        _running.postValue(true)

        return InitResult.Success
    }

    private fun buildShellArguments(
        deviceList: List<String>,
        connectedPort: String?
    ): List<String> {
        val expectedSerial = connectedPort?.let { "localhost:$it" }

        if (expectedSerial != null && deviceList.contains(expectedSerial)) {
            Log.d("DEVICES", "Choosing connected device: $expectedSerial")
            return listOf("-s", expectedSerial, "shell")
        }

        if (deviceList.size <= 1) {
            return listOf("shell")
        }

        Log.w("DEVICES", "Multiple devices detected...")

        val localDevice = deviceList.firstOrNull {
            it.startsWith("localhost:")
        }

        if (localDevice != null) {
            Log.w("DEVICES", "Choosing first local device: $localDevice")
            return listOf("-s", localDevice, "shell")
        }

        val nonEmulatorDevice = deviceList.firstOrNull {
            !it.startsWith("emulator-")
        }

        if (nonEmulatorDevice != null) {
            Log.w(
                "DEVICES",
                "Choosing first non-emulator device: $nonEmulatorDevice"
            )
            return listOf("-s", nonEmulatorDevice, "shell")
        }

        val fallbackDevice = deviceList.first()

        Log.w(
            "DEVICES",
            "Choosing first available device: $fallbackDevice"
        )

        return listOf("-s", fallbackDevice, "shell")
    }

    private fun isValidAdbPort(port: String): Boolean {
        val numericPort = port.trim().toIntOrNull() ?: return false
        return numericPort in 1..65535
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
                debug("Disabling 'Mobile data always on'...")
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
                debug("Cycling wireless debugging, please wait...")
                // Only turn it off if it's already on.
                if (isWirelessDebuggingEnabled()) {
                    debug("Turning off wireless debugging...")
                    Settings.Global.putInt(
                        context.contentResolver,
                        "adb_wifi_enabled",
                        0
                    )
                    Thread.sleep(3_000)
                }

                debug("Turning on wireless debugging...")
                Settings.Global.putInt(
                    context.contentResolver,
                    "adb_wifi_enabled",
                    1
                )
                Thread.sleep(3_000)

                debug("Turning off wireless debugging...")
                Settings.Global.putInt(
                    context.contentResolver,
                    "adb_wifi_enabled",
                    0
                )
                Thread.sleep(3_000)

                debug("Turning on wireless debugging...")
                Settings.Global.putInt(
                    context.contentResolver,
                    "adb_wifi_enabled",
                    1
                )
                Thread.sleep(3_000)
            }
        }
    }

    fun enableWirelessDebugging() {
        val secureSettingsGranted =
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

        if (secureSettingsGranted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!isWirelessDebuggingEnabled()) {
                    Settings.Global.putInt(
                        context.contentResolver,
                        "adb_wifi_enabled",
                        1
                    )
                    Thread.sleep(3_000)
                }
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
            debug("Shell is dead, resetting...")
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

        /* Continue once finished pairing (or 10s elapses) */
        pairShell.waitFor(10, TimeUnit.SECONDS)
        pairShell.destroyForcibly().waitFor()

        val killShell = adb(false, listOf("kill-server"))
        killShell.waitFor(3, TimeUnit.SECONDS)
        killShell.destroyForcibly()

        val success = pairShell.exitValue() == 0
        debug("Pairing was " + if (success) "successful" else "unsuccessful")
        return success
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

    /**
     * Append a message to the output buffer (for user-visible output)
     */
    fun appendToOutput(msg: String) {
        synchronized(outputBufferFile) {
            if (outputBufferFile.exists())
                outputBufferFile.appendText(msg + System.lineSeparator())
        }
    }

    fun resumeInitServerWithPort(port: String): InitResult {
        manualDebugPort = port
        return connectionAndStart(port)
    }

    sealed class InitResult {
        object Success : InitResult()
        object NeedsPort : InitResult()
        object Failure : InitResult()
    }
}