package com.simonmicro.irimeasurement

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.lang.Exception
import java.util.logging.Logger

class DataCollectorWorker(appContext: Context, workerParams: WorkerParameters): Worker(appContext, workerParams), SensorEventListener {
    enum class DataCollectorWorkerStatus {
        DEAD, PREPARE, ALIVE, SHUTDOWN
    }

    private var nId = 0
    private var notificationBuilder: NotificationCompat.Builder? = null
    private val log = Logger.getLogger(DataCollectorWorker::class.java.name)
    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private var requestStop: Boolean = false
    var status: DataCollectorWorkerStatus = DataCollectorWorkerStatus.DEAD

    var lastAccelerometerX: Float = 0.0f
    var lastAccelerometerY: Float = 0.0f
    var lastAccelerometerZ: Float = 0.0f

    companion object {
        var instance: DataCollectorWorker? = null
    }

    /**
     * These are the loop-relevant variables
     */
    var loopCounter = 0

    /**
     * This function is used to reset the state of this worker in case of failures or shutdown
     */
    private fun reset() {
        this.loopCounter = 0
    }

    private fun updateNotificationContent(i: String) {
        this.notificationBuilder!!.setContentText(i)
        NotificationManagerCompat.from(applicationContext).notify(nId, this.notificationBuilder!!.build())
    }

    private fun prepare() {
        DataCollectorWorker.instance = this
        this.requestStop = false
        this.status = DataCollectorWorkerStatus.PREPARE
        // Inform the WorkManager that this is a long-running service-task
        setForegroundAsync(createForegroundInfo()) // This will also create our ongoing notification
        // Register us to listen for sensors
        sensorManager = applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_FASTEST)
        // Note, that we do not reset any internal variables here - to allow the worker to seamlessly "resume" if it restarts
    }

    /**
     * This function is called on a regular basis - make sure to keep every iteration as short-as-possible
     */
    private fun loop(): Boolean {
        this.loopCounter++

        this.updateNotificationContent("Active since " + this.loopCounter.toString() + " seconds...");

        return this.loopCounter < 160 // Should we proceed?
    }

    private fun run() {
        this.status = DataCollectorWorkerStatus.ALIVE
        MainActivity.asyncUpdateUI() // TODO auto-call this on setters!

        var wantProceed = true
        while (!this.requestStop && wantProceed) {
            // Update notification as first step, so we don't show it in case the user requested this worker to stop
            NotificationManagerCompat.from(applicationContext).notify(nId, this.notificationBuilder!!.build()) // Update notification, as we are still alive
            wantProceed = this.loop()
            Thread.sleep(1000) // Should be enough - for now?
        }
    }

    private fun shutdown() {
        this.status = DataCollectorWorkerStatus.SHUTDOWN
        DataCollectorWorker.instance = null // Communicate to the outside that we are already done...
        this.requestStop = true // Just in case we are instructed to stop by the WorkManager
        sensorManager.unregisterListener(this) // This disconnects ALL sensors!
        NotificationManagerCompat.from(applicationContext).cancel(nId)
        this.reset()
    }

    override fun doWork(): Result {
        var wasRunOK = true
        try {
            this.prepare()
            this.run()
        } catch (e: Exception) {
            this.log.severe("Unexpected exception in service: " + (e.message?: "-"))
            wasRunOK = false
        }

        try {
            this.shutdown()
        } finally {
            // Ignore.
        }

        this.status = DataCollectorWorkerStatus.DEAD
        DataCollectorWorker.instance = null
        return if(wasRunOK)
            Result.success()
        else
            Result.failure() // So the WorkManager will try to restart us
    }

    override fun onStopped() {
        super.onStopped()
        this.shutdown()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(applicationContext.getString(R.string.service_notification_channel_id),
                applicationContext.getString(R.string.service_notification_channel_title), importance).apply {
                    description = applicationContext.getString(R.string.service_notification_channel_description)
                }
            // Register the channel with the system
            val notificationManager: NotificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        this.createNotificationChannel()
        this.notificationBuilder = NotificationCompat.Builder(applicationContext, applicationContext.getString(R.string.service_notification_channel_id))
            .setContentTitle(applicationContext.getString(R.string.service_notification_title))
            .setContentText("Starting...")
            .setSmallIcon(R.drawable.ic_service_notification_icon)
            .setOngoing(true)
            .setTimeoutAfter(10000) // Just re-emit it to prevent is from removal - used to "timeout" if we crash badly
            .setOnlyAlertOnce(true) // Silence on repeated updates!

        return ForegroundInfo(nId, this.notificationBuilder!!.build())
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Nope
    }

    override fun onSensorChanged(event: SensorEvent) {
        if(event.sensor == accelSensor) {
            this.lastAccelerometerX = event.values[0]
            this.lastAccelerometerY = event.values[1]
            this.lastAccelerometerZ = event.values[2]
        } else
            print(event.values)
        MainActivity.asyncUpdateUI()
    }
}

