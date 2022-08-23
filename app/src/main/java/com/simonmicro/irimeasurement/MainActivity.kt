package com.simonmicro.irimeasurement

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.logging.Logger

fun Float.format(digits: Int) = "%.${digits}f".format(this)

class MainActivity : AppCompatActivity() {
    private val log = Logger.getLogger(MainActivity::class.java.name)
    private var serviceControlButton: Button? = null
    private var serviceStatus: TextView? = null
    private var serviceUptime: TextView? = null
    private var serviceLastAccel: TextView? = null
    private var serviceLastGrav: TextView? = null
    private var serviceLastMag: TextView? = null
    private var serviceLastTemp: TextView? = null
    private var serviceLastPress: TextView? = null
    private var serviceLastHumi: TextView? = null
    private var serviceLoad: ProgressBar? = null

    companion object {
        private var instance: MainActivity? = null
        private var asyncIsQueued: Boolean = false

        fun asyncUpdateUI() {
            if(asyncIsQueued)
                return
            asyncIsQueued = true
            CoroutineScope(Dispatchers.Main).launch {
                MainActivity.instance?.updateUI()
                asyncIsQueued = false
            }
        }
    }

    private fun getServiceUIState(): Boolean {
        return DataCollectorWorker.instance != null && DataCollectorWorker.instance?.status == DataCollectorWorker.DataCollectorWorkerStatus.ALIVE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        this.serviceStatus = findViewById<TextView>(R.id.collectorStatus)
        this.serviceUptime = findViewById<TextView>(R.id.collectorUptime)
        this.serviceLastAccel = findViewById<TextView>(R.id.collectorAccel)
        this.serviceLastMag = findViewById<TextView>(R.id.collectorMag)
        this.serviceLastGrav = findViewById<TextView>(R.id.collectorGrav)
        this.serviceLastTemp = findViewById<TextView>(R.id.collectorTemp)
        this.serviceLastPress = findViewById<TextView>(R.id.collectorPress)
        this.serviceLastHumi = findViewById<TextView>(R.id.collectorHumi)
        this.serviceLoad = findViewById<ProgressBar>(R.id.serviceProgressBar)
        this.serviceControlButton = findViewById(R.id.button)
        this.serviceControlButton!!.setOnClickListener {
            if(this.getServiceUIState()) {
                this.log.info("Stopping collector...")
                WorkManager.getInstance(this).cancelUniqueWork(getString(R.string.service_id))
            } else {
                this.log.info("Starting collector...")
                WorkManager.getInstance(this).enqueueUniqueWork(
                    getString(R.string.service_id),
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<DataCollectorWorker>().build()
                )
            }
            // Note that the new task is now scheduled to run / queued to cancel - this is not done yet! Meaning the states are not up-to-date yet.
        }

        this.updateUI() // Initial view update
        MainActivity.instance = this // Enable interaction from outside

        // Start periodic UI update - in case the service crashes too fast to update it itself (or well, in case of a crash it won't update it anyways)
        val handler = Handler()
        val runnableCode: Runnable = object : Runnable {
            override fun run() {
                MainActivity.instance?.updateUI()
                handler.postDelayed(this, 6000) // Every 6 seconds update fetched status
            }
        }
        handler.post(runnableCode)
    }

    override fun onDestroy() {
        super.onDestroy()
        MainActivity.instance = null // Disable interaction from outside
    }

    private fun updateUI() {
        var l: List<WorkInfo> = WorkManager.getInstance(this).getWorkInfosForUniqueWork(getString(R.string.service_id)).get()
        var isRunning: Boolean = false
        if(l.isEmpty()) {
            // Do nothing, as the service never ran
            this.serviceStatus?.text = "-"
        } else {
            val state: WorkInfo.State = l[0].state
            // Update text
            if(state == WorkInfo.State.SUCCEEDED)
                this.serviceStatus?.text = "Finished"
            else if(state == WorkInfo.State.FAILED)
                this.serviceStatus?.text = "Crashed"
            else if(state == WorkInfo.State.CANCELLED)
                this.serviceStatus?.text = "Stopped"
            else if(state == WorkInfo.State.RUNNING) {
                this.serviceStatus?.text = "Running..."
                isRunning = true
            }
            // Update text color
            if(state == WorkInfo.State.FAILED)
                this.serviceStatus?.setTextColor(Color.RED)
            else if(state == WorkInfo.State.RUNNING)
                this.serviceStatus?.setTextColor(Color.GREEN)
            else
                this.serviceStatus?.setTextColor(this.serviceLastAccel?.currentTextColor?: Color.BLUE) // "Reset" the text color by stealing it from an other element
        }
        if(DataCollectorWorker.instance != null) {
            var service: DataCollectorWorker = DataCollectorWorker.instance!!
            this.serviceUptime?.text = ((System.currentTimeMillis() - service.startTime) / 1000).toString() + "s"
            if(isRunning) {
                val bufferPercent = ((service.dataPointCount - service.dataPointCountOnLastFlush).toFloat() / service.flushTarget.toFloat() * 100).toInt()
                if(bufferPercent > 100) {
                    this.serviceLoad?.isIndeterminate = true
                } else {
                    this.serviceLoad?.isIndeterminate = false
                    this.serviceLoad?.progress = bufferPercent
                }
            } else {
                this.serviceLoad?.isIndeterminate = false
                this.serviceLoad?.progress = 0
            }
            runBlocking { service.dataPointMutex.lock() }
            if (service.lastAccelerometerPoint != null)
                this.serviceLastAccel?.text = "(${service.lastAccelerometerPoint!!.accelX.format(2)}, ${service.lastAccelerometerPoint!!.accelY.format(2)}, ${service.lastAccelerometerPoint!!.accelZ.format(2)}) m/s²"
            if (service.lastGravityPoint != null)
                this.serviceLastGrav?.text = "(${service.lastGravityPoint!!.accelX.format(2)}, ${service.lastGravityPoint!!.accelY.format(2)}, ${service.lastGravityPoint!!.accelZ.format(2)}) m/s²"
            if (service.lastMagnetometerPoint != null)
                this.serviceLastMag?.text = "(${service.lastMagnetometerPoint!!.fieldX.format(2)}, ${service.lastMagnetometerPoint!!.fieldY.format(2)}, ${service.lastMagnetometerPoint!!.fieldZ.format(2)}) μT"
            if (service.lastTemperaturePoint != null)
                this.serviceLastTemp?.text = "${service.lastTemperaturePoint!!.amount.format(2)} °C"
            if (service.lastPressurePoint != null)
                this.serviceLastPress?.text = "${service.lastPressurePoint!!.amount.format(2)} hPa"
            if (service.lastHumidityPoint != null)
                this.serviceLastHumi?.text = "${service.lastHumidityPoint!!.amount.format(2)} hPa"
            runBlocking { service.dataPointMutex.unlock() }
        }
        if(this.getServiceUIState())
            this.serviceControlButton?.text = "STOP"
        else
            this.serviceControlButton?.text = "START"
    }
}