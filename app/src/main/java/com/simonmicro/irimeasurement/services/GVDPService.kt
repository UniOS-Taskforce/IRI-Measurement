package com.simonmicro.irimeasurement.services

import android.app.AlertDialog
import android.content.Context
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.simonmicro.irimeasurement.BuildConfig
import com.simonmicro.irimeasurement.R
import com.simonmicro.irimeasurement.util.Log

class GVDPService {
    companion object {
        private val log = Log(GVDPService::class.java.name)

        fun shouldDisplayAgain(): Boolean {
            if (false) { // future F-Droid-only releases do not need this!
                val file = StorageService.getGVDPConfigPath().toFile()
                if (!file.exists()) {
                    log.d("Never showed GVDP before...")
                    return true
                }
                val lastShow = file.bufferedReader().readText()

                if (lastShow != BuildConfig.VERSION_NAME) {
                    log.d("Lastly showed GVDP on $lastShow (now ${BuildConfig.VERSION_NAME})...")
                    return true
                }

                val fourteenDaysInMs = 14L * 24 * 60 * 60 * 1000
                if (System.currentTimeMillis() - file.lastModified() > fourteenDaysInMs) {
                    log.d("Lastly showed GVDP 14 days ago...")
                    return true
                }
            }
            return false
        }

        fun displayNow(context: Context) {
            val message = SpannableString(ContextCompat.getString(context, R.string.gvdp_notification_msg)) // msg should have url to enable clicking
            Linkify.addLinks(message, Linkify.WEB_URLS)

            val builder: AlertDialog.Builder = AlertDialog.Builder(context)
            builder
                .setTitle(R.string.gvdp_notification_title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(ContextCompat.getString(context, R.string.gvdp_positive)) { _, _ ->
                    // Well, do nothing... We'll remind again!
                }
                .setNegativeButton(ContextCompat.getString(context, R.string.gvdp_negative)) { _, _ ->
                    // Good, see you next update (if there will be any ever again)
                    this.neverDisplayAgain()
                }

            val dialog: AlertDialog = builder.create()
            dialog.show()

            // now inject the part making the link actually click-able
            dialog.findViewById<TextView>(android.R.id.message)?.movementMethod = LinkMovementMethod.getInstance()
        }

        fun neverDisplayAgain() {
            val file = StorageService.getGVDPConfigPath().toFile()
            if(!file.exists())
                file.createNewFile()
            log.d("Not automatically showing GVDP again for 14 days and ${BuildConfig.VERSION_NAME}...")
            file.writer().use {
                it.write(BuildConfig.VERSION_NAME)
            }
        }

        fun resetDisplayAgain() {
            val file = StorageService.getGVDPConfigPath().toFile()
            if(file.exists())
                file.delete()
            log.d("Reset GVDP display again flag...")
        }
    }
}