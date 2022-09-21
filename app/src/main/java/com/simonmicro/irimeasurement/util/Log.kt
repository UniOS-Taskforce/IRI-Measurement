package com.simonmicro.irimeasurement.util

import com.simonmicro.irimeasurement.services.points.DataPoint
import com.simonmicro.irimeasurement.Collection
import android.util.Log as AndroidLog

class Log(private var tag: String) {
    private class LogPoint(var type: String, var msg: String): DataPoint(null) {
        override fun getName(): String {
            return "log"
        }

        override fun getHeader(): String {
            return super.getHeader() + ";LVL;MSG"
        }

        override fun getRow(): String {
            return super.getRow() + ";${this.type};${this.msg}"
        }
    }

    companion object {
        private var logCollection: Collection? = null
        private var log = Log(Log::class.java.name)

        fun sendLogsToCollection(collection: Collection?) {
            if(collection == null)
                this.log.i("Stopping logging output to current collection...")
            this.logCollection = collection
            if(collection != null)
                this.log.i("Switched logging output to collection ${collection.id}.")
        }

        private fun addCollection(type: String, msg: String) {
            try {
                if (logCollection != null && logCollection!!.getMeta().finished == null)
                    logCollection!!.addPoints(arrayListOf(LogPoint(type, msg)))
            } catch(e: Exception) {
                AndroidLog.w(this.log.tag, "Failed to log to collection: ${e.stackTraceToString()}")
            }
        }
    }

    fun e(msg: String) {
        AndroidLog.e(this.tag, msg)
        addCollection("e", msg)
    }

    fun w(msg: String) {
        AndroidLog.w(this.tag, msg)
        addCollection("w", msg)
    }

    fun i(msg: String) {
        AndroidLog.i(this.tag, msg)
        addCollection("i", msg)
    }

    fun d(msg: String) {
        AndroidLog.d(this.tag, msg)
        addCollection("d", msg)
    }
}