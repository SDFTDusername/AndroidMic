package com.example.androidMic.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.example.androidMic.AndroidMicApp
import com.example.androidMic.ui.home.HomeScreen
import com.example.androidMic.ui.theme.AndroidMicTheme
import com.example.androidMic.ui.utils.rememberWindowInfo
import com.example.androidMic.utils.ignore


class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"

    private val WAIT_PERIOD = 500L

    val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        setContent {

            AndroidMicTheme(
                theme = vm.prefs.theme.getAsState().value,
                dynamicColor = vm.prefs.dynamicColor.getAsState().value
            ) {
                // get windowInfo for rotation change
                val windowInfo = rememberWindowInfo()

                HomeScreen(vm, windowInfo)
            }
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.extras?.getBoolean("ForegroundServiceBound") == true) {
            Log.d(TAG, "onNewIntent -> ForegroundServiceBound")
            // get variable from application
            vm.refreshAppVariables()
            // get status
            vm.askForStatus()
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart")


        vm.handlerServiceResponse()
        // get variable from application
        vm.refreshAppVariables()

        (application as AndroidMicApp).bindService()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop")
        vm.mMessengerLooper.quitSafely()
        ignore { vm.handlerThread.join(WAIT_PERIOD) }

        (application as AndroidMicApp).unBindService()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

}