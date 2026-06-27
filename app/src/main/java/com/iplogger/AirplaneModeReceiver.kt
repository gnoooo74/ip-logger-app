package com.iplogger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class AirplaneModeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_AIRPLANE_MODE_CHANGED) {
            val isOn = Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON, 0
            ) != 0

            IpLogWorker.logEvent(context, if (isOn) "비행기모드 ON" else "비행기모드 OFF")

            if (!isOn) {
                Thread {
                    Thread.sleep(3000)
                    IpLogWorker.logIpNow(context)
                }.start()
            }
        }
    }
}
