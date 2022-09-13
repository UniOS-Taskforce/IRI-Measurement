package com.simonmicro.irimeasurement.ui

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.simonmicro.irimeasurement.services.CollectorService
import com.simonmicro.irimeasurement.HomeScreen
import com.simonmicro.irimeasurement.R
import com.simonmicro.irimeasurement.services.StorageService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

fun Float.format(digits: Int) = "%.${digits}f".format(this)
fun Double.format(digits: Int) = "%.${digits}f".format(this)

class CollectFragment : Fragment() {
    private val logTag = CollectFragment::class.java.name
    private var serviceControlButton: Button? = null
    private var serviceStatus: TextView? = null
    private var serviceUptime: TextView? = null
    private var serviceCollectionId: TextView? = null
    private var serviceFreeSpace: TextView? = null
    private var serviceLastAccel: TextView? = null
    private var serviceLastGrav: TextView? = null
    private var serviceLastMag: TextView? = null
    private var serviceLastTemp: TextView? = null
    private var serviceLastPress: TextView? = null
    private var serviceLastHumi: TextView? = null
    private var serviceLastLoc: TextView? = null
    private var serviceLastLocAccu: TextView? = null
    private var serviceLastLocDir: TextView? = null
    private var serviceLoad: ProgressBar? = null
    private var activeWarn: LinearLayout? = null
    private var done: Boolean = false

    companion object {
        private var instance: CollectFragment? = null
        private var asyncIsQueued: Boolean = false

        fun asyncUpdateUI() {
            if(asyncIsQueued)
                return
            asyncIsQueued = true
            CoroutineScope(Dispatchers.Main).launch {
                instance?.updateUI()
                asyncIsQueued = false
            }
        }
    }

    private fun getServiceUIState(): Boolean {
        return CollectorService.instance != null && CollectorService.instance?.status == CollectorService.DataCollectorWorkerStatus.ALIVE
    }

    private fun updateUI() {
        var l: List<WorkInfo> = WorkManager.getInstance(this.requireContext()).getWorkInfosForUniqueWork(getString(R.string.service_id)).get()
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
        if(isRunning && CollectorService.instance != null) {
            var service: CollectorService = CollectorService.instance!!
            this.serviceUptime?.text = ((System.currentTimeMillis() - service.startTime) / 1000).toString() + "s"
            val bufferPercent = ((service.dataPointCount - service.dataPointCountOnLastFlush).toFloat() / service.flushTarget.toFloat() * 100).toInt()
            if(bufferPercent > 100) {
                this.serviceLoad?.isIndeterminate = true
            } else {
                this.serviceLoad?.isIndeterminate = false
                this.serviceLoad?.progress = bufferPercent
            }
            this.serviceCollectionId?.text = service.collection?.id.toString()
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
            if (service.lastLocation != null) {
                this.serviceLastLoc?.text = "↑ ${service.lastLocation!!.locHeight.format(2)} m, lon ${service.lastLocation!!.locLon.format(2)} °, lat ${service.lastLocation!!.locLat.format(2)} °"
                var accuracy: String = ""
                accuracy = "±${service.lastLocation!!.accuDir.format(2)} °, ↑ ±${service.lastLocation!!.accuHeight.format(2)} m, "
                accuracy += "±${service.lastLocation!!.accuLonLat.format(2)} m"
                this.serviceLastLocAccu?.text = accuracy
                this.serviceLastLocDir?.text = "${service.lastLocation!!.dir.format(2)} °, ${service.lastLocation!!.dirSpeed.format(2)} m/s"
            }
            runBlocking { service.dataPointMutex.unlock() }
        }
        this.serviceFreeSpace?.text = StorageService.getBytesNormalString(StorageService.getFreeSpaceBytes())
        if(!isRunning) {
            this.serviceLoad?.isIndeterminate = false
            this.serviceLoad?.progress = 0
        }
        if(this.getServiceUIState()) {
            this.serviceControlButton?.text = "STOP"
            this.activeWarn?.visibility = LinearLayout.VISIBLE
        } else {
            this.serviceControlButton?.text = "START"
            this.activeWarn?.visibility = LinearLayout.GONE
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        var view: View = inflater.inflate(R.layout.fragment_collect, container, false)

        this.serviceStatus = view.findViewById<TextView>(R.id.collectorStatus)
        this.serviceUptime = view.findViewById<TextView>(R.id.collectorUptime)
        this.serviceCollectionId = view.findViewById<TextView>(R.id.collectionId)
        this.serviceFreeSpace = view.findViewById<TextView>(R.id.freeSpace)
        this.serviceLastAccel = view.findViewById<TextView>(R.id.collectorAccel)
        this.serviceLastMag = view.findViewById<TextView>(R.id.collectorMag)
        this.serviceLastGrav = view.findViewById<TextView>(R.id.collectorGrav)
        this.serviceLastTemp = view.findViewById<TextView>(R.id.collectorTemp)
        this.serviceLastPress = view.findViewById<TextView>(R.id.collectorPress)
        this.serviceLastHumi = view.findViewById<TextView>(R.id.collectorHumi)
        this.serviceLastLoc = view.findViewById<TextView>(R.id.collectorLoc)
        this.serviceLastLocAccu = view.findViewById<TextView>(R.id.collectorLocAccu)
        this.serviceLastLocDir = view.findViewById<TextView>(R.id.collectorLocDir)
        this.serviceLoad = view.findViewById<ProgressBar>(R.id.serviceProgressBar)
        this.activeWarn = view.findViewById<LinearLayout>(R.id.keepActiveWarning)
        this.serviceControlButton = view.findViewById(R.id.button)
        this.serviceControlButton!!.setOnClickListener {
            if(this.getServiceUIState()) {
                Log.i(logTag, "Stopping collector...")
                WorkManager.getInstance(this.requireContext()).cancelUniqueWork(getString(R.string.service_id))
            } else if(HomeScreen.locService!!.requestPermissionsIfNecessary(this.requireActivity())) {
                Log.i(logTag, "Starting collector...")
                WorkManager.getInstance(this.requireContext()).enqueueUniqueWork(
                    getString(R.string.service_id),
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<CollectorService>().build()
                )
            }
            // Note that the new task is now scheduled to run / queued to cancel - this is not done yet! Meaning the states are not up-to-date yet.
        }

        this.updateUI() // Initial view update
        instance = this // Enable interaction from outside

        // Start periodic UI update - in case the service crashes too fast to update it itself (or well, in case of a crash it won't update it anyways)
        this.done = false
        var that = this
        val handler = Handler()
        val runnableCode: Runnable = object : Runnable {
            override fun run() {
                if(that.done) return
                instance?.updateUI()
                handler.postDelayed(this, 6000) // Every 6 seconds update fetched status
            }
        }
        handler.post(runnableCode)

        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        instance = null // Disable interaction from outside
        this.done = true
    }
}