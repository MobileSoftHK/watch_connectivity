package dev.rexios.watch_connectivity_garmin

import android.content.Context
import android.content.pm.PackageManager
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.ConnectIQ.ConnectIQListener
import com.garmin.android.connectiq.ConnectIQ.IQApplicationInfoListener
import com.garmin.android.connectiq.ConnectIQ.IQConnectType
import com.garmin.android.connectiq.ConnectIQ.IQSdkErrorStatus
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread


/** WatchConnectivityGarminPlugin */
class WatchConnectivityGarminPlugin : FlutterPlugin, MethodCallHandler {
    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private lateinit var channel: MethodChannel
    private lateinit var context: Context
    private lateinit var packageManager: PackageManager
    private lateinit var connectIQ: ConnectIQ
    private lateinit var iqApp: IQApp
    private var initialized: Boolean = false

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "watch_connectivity_garmin")
        channel.setMethodCallHandler(this)

        context = flutterPluginBinding.applicationContext

        packageManager = context.packageManager
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        if (initialized) {
            connectIQ.shutdown(context)
            initialized = false
        }
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            // Getters
            "isSupported" -> isSupported(result)
            "isPaired" -> isPaired(result)
            "isReachable" -> isReachable(result)

            // Methods
            "initialize" -> initialize(call, result)
            "sendMessage" -> sendMessage(call, result)

            // Not implemented
            else -> result.notImplemented()
        }
    }

    private fun initialize(call: MethodCall, result: Result) {
        val applicationId = call.argument<String>("applicationId")!!
        iqApp = IQApp(applicationId)

        val connectTypeString = call.argument<String>("connectType")!!
        val connectType = IQConnectType.valueOf(connectTypeString)
        connectIQ = ConnectIQ.getInstance(context, connectType)
        connectIQ.initialize(
            context,
            call.argument<Boolean>("autoUI")!!,
            object : ConnectIQListener {
                override fun onSdkReady() {
                    initialized = true
                    listenForMessages()
                    result.success(null)
                }

                override fun onInitializeError(status: IQSdkErrorStatus?) {
                    result.error(status.toString(), "Unable to initialize Garmin SDK", null)
                }

                override fun onSdkShutDown() {}
            },
        )

        if (connectType == IQConnectType.TETHERED) {
            val adbPort = call.argument<Int>("adbPort")!!
            connectIQ.adbPort = adbPort
        }
    }

    private fun listenForMessages() {
        if (!initialized) return

        val devices = connectIQ.knownDevices ?: listOf()

        for (device in devices) {
            connectIQ.registerForDeviceEvents(device) { _, status ->
                processDeviceStatus(device, status)
            }
        }

        for (device in connectIQ.connectedDevices ?: listOf()) {
            processDeviceStatus(device, IQDevice.IQDeviceStatus.CONNECTED)
        }
    }

    private fun processDeviceStatus(device: IQDevice, status: IQDevice.IQDeviceStatus) {
        if (!initialized) return

        if (status == IQDevice.IQDeviceStatus.CONNECTED) {
            connectIQ.registerForAppEvents(device, iqApp) { _, _, data, status ->
                if (status != ConnectIQ.IQMessageStatus.SUCCESS) return@registerForAppEvents
                for (datum in data) {
                    channel.invokeMethod("didReceiveMessage", datum)
                }
            }
        } else {
            connectIQ.unregisterForApplicationEvents(device, iqApp)
        }
    }

    private fun getApplicationForDevice(device: IQDevice): IQApp? {
        var installedApp: IQApp? = null
        val latch = CountDownLatch(1)
        connectIQ.getApplicationInfo(
            iqApp.applicationId,
            device,
            object : IQApplicationInfoListener {
                override fun onApplicationInfoReceived(app: IQApp?) {
                    installedApp = app
                    latch.countDown()
                }

                override fun onApplicationNotInstalled(p0: String?) {
                    latch.countDown()
                }
            }
        )
        latch.await()
        return installedApp
    }

    private fun isSupported(result: Result) {
        val apps = packageManager.getInstalledApplications(0)
        val wearableAppInstalled =
            apps.any { it.packageName == "com.garmin.android.apps.connectmobile" }
        result.success(wearableAppInstalled)
    }

    private fun isPaired(result: Result) {
        if (initialized) {
            result.success(connectIQ.knownDevices?.isNotEmpty() ?: false)
        } else {
            result.error("WatchConnectivityGarminPlugin isPaired", "SDK not initialized", null)
        }
    }

    private fun isReachable(result: Result) {
        thread {
            if (initialized) {
                for (device in connectIQ.connectedDevices ?: listOf()) {
                    val installedApp = getApplicationForDevice(device)
                    if (installedApp != null) {
                        result.success(true)
                        return@thread
                    }
                }
                result.success(false)
            } else {
                result.error("WatchConnectivityGarminPlugin isReachable", "SDK not initialized", null)
            }
        }
    }


    private fun sendMessage(call: MethodCall, result: Result) {
        if (!initialized) {
            result.error("WatchConnectivityGarminPlugin sendMessage", "SDK not initialized", null)
            return
        }

        val devices = connectIQ.connectedDevices ?: listOf()

        thread {
            try{
                val latch = CountDownLatch(devices.count())
                val errors = mutableListOf<ConnectIQ.IQMessageStatus>()
                for (device in devices) {
                    connectIQ.sendMessage(device, iqApp, call.arguments) { _, _, status ->
                        if (status != ConnectIQ.IQMessageStatus.SUCCESS) {
                            errors.add(status)
                        }

                        latch.countDown()
                    }
                }
                try {
                    val success = latch.await(15, TimeUnit.SECONDS)
                    if (!success) {
                        result.error("GARMIN WATCH CONNECTIVITY ERROR - Android", "Unable to send message - COUNT DOWN LATCH TIMEOUT", null)
                    } else if (errors.isNotEmpty()) {
                        result.error(errors.toString(), "Unable to send message", null)
                    } else {
                        result.success(null)
                    }
                } catch (e: InterruptedException) {
                    result.error("GARMIN WATCH CONNECTIVITY ERROR - Android", "Unable to send message - InterruptedException", null)
                }
            }
            catch (e: Exception) {
                result.error("GARMIN WATCH CONNECTIVITY ERROR - Android", "Unable to send message - FAILURE DURING TRANSFER", null)
            }
        }
    }
}

