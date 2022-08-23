package com.simonmicro.irimeasurement

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.logging.Logger

class MainActivity : AppCompatActivity() {
    private val log = Logger.getLogger(MainActivity::class.java.name)
    private var serviceControlButton: Button? = null
    private var serviceStatusText: TextView? = null
    private var serviceLastAccelX: TextView? = null
    private var serviceLastAccelY: TextView? = null
    private var serviceLastAccelZ: TextView? = null

    companion object {
        private var instance: MainActivity? = null

        fun asyncUpdateUI() {
            CoroutineScope(Dispatchers.Main).launch {
                MainActivity.instance?.updateUI()
            }
        }
    }

    private fun getServiceUIState(): Boolean {
        return DataCollectorWorker.instance != null && DataCollectorWorker.instance?.status == DataCollectorWorker.DataCollectorWorkerStatus.ALIVE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        this.serviceStatusText = findViewById<TextView>(R.id.collectorStatus)
        this.serviceLastAccelX = findViewById<TextView>(R.id.collectorLastAccelX)
        this.serviceLastAccelY = findViewById<TextView>(R.id.collectorLastAccelY)
        this.serviceLastAccelZ = findViewById<TextView>(R.id.collectorLastAccelZ)
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

        // TODO this code ↓
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
        if(l.isEmpty()) {
            // Do nothing, as the service never ran
            this.serviceStatusText?.text = "-"
        } else {
            val state: WorkInfo.State = l[0].state
            // Update text
            if(state == WorkInfo.State.SUCCEEDED)
                this.serviceStatusText?.text = "Finished"
            else if(state == WorkInfo.State.FAILED)
                this.serviceStatusText?.text = "Crashed"
            else if(state == WorkInfo.State.CANCELLED)
                this.serviceStatusText?.text = "Stopped"
            else if(state == WorkInfo.State.RUNNING)
                this.serviceStatusText?.text = "Running (active)..."
            else if(state == WorkInfo.State.ENQUEUED)
                this.serviceStatusText?.text = "Running (queued)..."
            else if(state == WorkInfo.State.BLOCKED)
                this.serviceStatusText?.text = "Running (blocked)..."
            else
                this.serviceStatusText?.text = "???"
            // Update text color
            if(state == WorkInfo.State.FAILED)
                this.serviceStatusText?.setTextColor(Color.RED)
            else if(state == WorkInfo.State.RUNNING)
                this.serviceStatusText?.setTextColor(Color.GREEN)
            else
                this.serviceStatusText?.setTextColor(this.serviceLastAccelX?.currentTextColor?: Color.BLUE) // "Reset" the text color by stealing it from an other element
            if(DataCollectorWorker.instance != null) {
                this.serviceLastAccelX?.text =
                    DataCollectorWorker.instance!!.lastAccelerometerX.toString()
                this.serviceLastAccelY?.text =
                    DataCollectorWorker.instance!!.lastAccelerometerY.toString()
                this.serviceLastAccelZ?.text =
                    DataCollectorWorker.instance!!.lastAccelerometerZ.toString()
            }
        }
        if(this.getServiceUIState())
            this.serviceControlButton?.text = "STOP"
        else
            this.serviceControlButton?.text = "START"
    }
}