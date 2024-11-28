package com.example.androidMic.domain.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import com.example.androidMic.Modes
import com.example.androidMic.R
import com.example.androidMic.domain.audio.AudioBuffer
import com.example.androidMic.domain.audio.MicAudioManager
import com.example.androidMic.domain.service.Command.Companion.COMMAND_DISC_STREAM
import com.example.androidMic.domain.service.Command.Companion.COMMAND_GET_STATUS
import com.example.androidMic.domain.service.Command.Companion.COMMAND_START_AUDIO
import com.example.androidMic.domain.service.Command.Companion.COMMAND_START_STREAM
import com.example.androidMic.domain.service.Command.Companion.COMMAND_STOP_AUDIO
import com.example.androidMic.domain.service.Command.Companion.COMMAND_STOP_STREAM
import com.example.androidMic.domain.streaming.MicStreamManager
import com.example.androidMic.utils.ignore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking


data class ServiceStates(
    var isStreamStarted: Boolean = false,
    var streamShouldStop: Boolean = false,
    var isAudioStarted: Boolean = false,
    var audioShouldStop: Boolean = false,
    var mode: Int = Modes.WIFI.ordinal
)

class ForegroundService : Service() {
    private val TAG = "MicService"
    private val scope = CoroutineScope(Dispatchers.Default)
    private val WAIT_PERIOD = 500L


    private inner class ServiceHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                COMMAND_START_STREAM -> startStream(msg)
                COMMAND_STOP_STREAM -> stopStream(msg)
                COMMAND_START_AUDIO -> startAudio(msg)
                COMMAND_STOP_AUDIO -> stopAudio(msg)
                COMMAND_GET_STATUS -> getStatus(msg)
            }
        }
    }

    private lateinit var handlerThread: HandlerThread
    private lateinit var serviceLooper: Looper
    private lateinit var serviceHandler: ServiceHandler
    private lateinit var serviceMessenger: Messenger

    private var sharedBuffer = AudioBuffer()
    private var managerAudio: MicAudioManager? = null
    private var managerStream: MicStreamManager? = null
    private var jobStreamM: Job? = null
    private var jobAudioM: Job? = null


    private val states = ServiceStates()
    private lateinit var messageui: MessageUi


    override fun onCreate() {
        Log.d(TAG, "onCreate")
        // create message handler
        handlerThread = HandlerThread("MicServiceStart", Process.THREAD_PRIORITY_BACKGROUND)
        handlerThread.start()
        serviceLooper = handlerThread.looper
        serviceHandler = ServiceHandler(handlerThread.looper)
        serviceMessenger = Messenger(serviceHandler)

        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.app_name)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance)
            // Register the channel with the system
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
        messageui = MessageUi(this)
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "onBind")

        return serviceMessenger.binder
    }

    private var serviceShouldStop = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        serviceShouldStop = false
        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        super.onUnbind(intent)
        Log.d(TAG, "onUnbind")

        if ((!states.isAudioStarted || states.audioShouldStop) &&
            (!states.isStreamStarted || states.streamShouldStop)
        ) {
            // delay to handle reconfiguration
            // (Service is not destroy when the screen rotate)
            serviceShouldStop = true
            scope.launch {
                delay(3000L)
                if (serviceShouldStop)
                    stopService()
            }
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        stopService()
    }

    private fun stopService() {
        Log.d(TAG, "stopService")
        managerAudio?.shutdown()
        managerAudio = null
        managerStream?.shutdown()
        managerStream = null
        states.streamShouldStop = true
        states.audioShouldStop = true
        runBlocking {
            delay(WAIT_PERIOD)
            if (jobStreamM?.isActive == true) jobStreamM?.cancel()
            if (jobAudioM?.isActive == true) jobAudioM?.cancel()
            jobStreamM?.join()
            jobAudioM?.join()
        }
        serviceLooper.quitSafely()
        ignore { handlerThread.join(WAIT_PERIOD) }


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }


    // start streaming
    private fun startStream(msg: Message) {
        val sender = msg.replyTo
        val replyData = Bundle()
        // check connection state
        if (states.isStreamStarted) {
            replyData.putString("reply", this.getString(R.string.stream_already_started))
            reply(sender, replyData, COMMAND_START_STREAM, true)
            return
        } else if (jobStreamM?.isActive == true) {
            // avoid duplicate jobs
            reply(sender, replyData, COMMAND_START_STREAM, false)
            return
        }

        // get params before going into the scope
        val mode: Modes = msg.data.getInt("MODE").let {
            Modes.entries[it]
        }
        val ip: String? = msg.data.getString("IP")
        val port: Int = msg.data.getInt("PORT")

        Log.d(TAG, "startStream [start]")
        // try to start streaming
        jobStreamM = scope.launch {
            managerStream?.shutdown()
            managerStream = null
            try {
                managerStream = MicStreamManager(applicationContext, mode, ip, port)
            } catch (e: IllegalArgumentException) {
                Log.d(TAG, "start stream with mode ${mode.name} failed:\n${e.message}")
                replyData.putString(
                    "reply",
                    applicationContext.getString(R.string.error) + e.message
                )
                reply(sender, replyData, COMMAND_START_STREAM, false)
                cancel()
                awaitCancellation()
            }
            states.streamShouldStop = false

            sharedBuffer.clear()
            if (managerStream?.start() == true && managerStream?.isConnected() == true) {
                replyData.putString(
                    "reply",
                    applicationContext.getString(R.string.connected_device) +
                            managerStream?.getInfo()
                )
                reply(sender, replyData, COMMAND_START_STREAM, true)
                Log.d(TAG, "startStream [connected]")
            } else {
                replyData.putString(
                    "reply",
                    applicationContext.getString(R.string.failed_to_connect)
                )
                reply(sender, replyData, COMMAND_START_STREAM, false)
                managerStream?.shutdown()
                managerStream = null
                cancel()
                awaitCancellation()
            }
            messageui.showMessage(applicationContext.getString(R.string.start_streaming))
            states.isStreamStarted = true
            states.streamShouldStop = false
            while (!states.streamShouldStop) {
                if (managerStream?.isConnected() == true) {
                    managerStream?.stream(sharedBuffer)
                    delay(MicStreamManager.STREAM_DELAY)
                } else {
                    replyData.putString(
                        "reply",
                        applicationContext.getString(R.string.device_disconnected)
                    )
                    messageui.showMessage(applicationContext.getString(R.string.stop_streaming))
                    reply(sender, replyData, COMMAND_DISC_STREAM, false)
                    break
                }
            }
            states.isStreamStarted = false
        }
    }

    // stop streaming
    private fun stopStream(msg: Message) {
        Log.d(TAG, "stopStream")
        val sender = msg.replyTo
        val replyData = Bundle()
        runBlocking {
            states.streamShouldStop = true
            managerStream?.shutdown()
            delay(WAIT_PERIOD)
            jobStreamM?.cancel()
        }
        managerStream = null
        jobStreamM = null
        replyData.putString("reply", applicationContext.getString(R.string.device_disconnected))
        messageui.showMessage(applicationContext.getString(R.string.stop_streaming))
        states.isStreamStarted = false
        reply(sender, replyData, COMMAND_STOP_STREAM, true)
    }

    // start mic
    private fun startAudio(msg: Message) {
        val sender = msg.replyTo
        val replyData = Bundle()
        // check audio state
        if (states.isAudioStarted) {
            replyData.putString("reply", this.getString(R.string.microphone_already_started))
            reply(sender, replyData, COMMAND_START_AUDIO, true)
            return
        } else if (jobAudioM?.isActive == true) {
            // avoid duplicate jobs
            reply(sender, replyData, COMMAND_START_AUDIO, false)
            return
        }

        // get params before going into the scope
        val sampleRate: Int = msg.data.getInt("SAMPLE_RATE")
        val channelCount: Int = msg.data.getInt("CHANNEL_COUNT")
        val audioFormat: Int = msg.data.getInt("AUDIO_FORMAT")

        Log.d(TAG, "startAudio [start]")
        // start audio recording
        jobAudioM = scope.launch {
            managerAudio?.shutdown()
            managerAudio = null
            managerAudio = try {
                MicAudioManager(
                    ctx = applicationContext,
                    sampleRate = sampleRate,
                    audioFormat = audioFormat,
                    channelCount = channelCount,
                )
            } catch (e: IllegalArgumentException) {
                replyData.putString("reply", application.getString(R.string.error) + e.message)
                reply(sender, replyData, COMMAND_START_AUDIO, false)
                cancel()
                awaitCancellation()
            }
            // start recording
            sharedBuffer.clear()
            managerAudio?.start()

            // the id is not important here
            // we need to start in foreground to use the mic
            // but no need to specified a flag because we declared
            // the type in manifest
            startForeground(3, messageui.getNotification())

            messageui.showMessage(application.getString(R.string.start_recording))
            replyData.putString("reply", application.getString(R.string.mic_start_recording))
            reply(sender, replyData, COMMAND_START_AUDIO, true)
            Log.d(TAG, "startAudio [recording]")
            // record into buffer
            states.isAudioStarted = true
            states.audioShouldStop = false
            while (!states.audioShouldStop) {
                managerAudio?.record(sharedBuffer)
                delay(MicAudioManager.RECORD_DELAY)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            states.isAudioStarted = false
        }
    }

    // stop mic
    private fun stopAudio(msg: Message) {
        Log.d(TAG, "stopAudio")
        val sender = msg.replyTo
        val replyData = Bundle()
        runBlocking {
            states.audioShouldStop = true
            managerAudio?.shutdown()
            delay(WAIT_PERIOD)
            jobAudioM?.cancel()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        managerAudio = null
        jobAudioM = null
        replyData.putString("reply", application.getString(R.string.recording_stopped))
        messageui.showMessage(application.getString(R.string.stop_recording))
        states.isAudioStarted = false
        reply(sender, replyData, COMMAND_STOP_AUDIO, true)
    }


    private fun getStatus(msg: Message) {
        Log.d(TAG, "getStatus")
        val sender = msg.replyTo
        val replyData = Bundle()
        replyData.putBoolean("isStreamStarted", states.isStreamStarted)
        replyData.putBoolean("isAudioStarted", states.isAudioStarted)
        val reply = Message()
        reply.data = replyData
        reply.what = COMMAND_GET_STATUS
        sender.send(reply)
    }
}