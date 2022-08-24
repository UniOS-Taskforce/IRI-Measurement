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
import com.simonmicro.irimeasurement.ui.CollectFragment
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
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
    private var requestStop: Boolean = false

    private var accelSensor: Sensor? = null
    private var tempSensor: Sensor? = null
    private var gravSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var magSensor: Sensor? = null
    private var pressSensor: Sensor? = null
    private var humiSensor: Sensor? = null

    var status: DataCollectorWorkerStatus = DataCollectorWorkerStatus.DEAD
    var startTime: Long = System.currentTimeMillis()
    var dataPointCount: Long = 0
    var dataPointCountOnLastFlush: Long = 0
    val flushTarget: Int = 4096

    var dataPointMutex = Mutex()

    open inner class DataPoint {
        var time: Long = System.currentTimeMillis()
    }

    inner class AccelerometerPoint: DataPoint() {
        var accelX: Float = 0.0f
        var accelY: Float = 0.0f
        var accelZ: Float = 0.0f
    }
    var lastAccelerometerPoint: AccelerometerPoint? = null
    private var accelerometerHistory: ArrayList<AccelerometerPoint> = ArrayList()

    inner class GravityPoint: DataPoint() {
        var accelX: Float = 0.0f
        var accelY: Float = 0.0f
        var accelZ: Float = 0.0f
    }
    var lastGravityPoint: GravityPoint? = null
    private var gravityHistory: ArrayList<GravityPoint> = ArrayList()

    inner class MagnetometerPoint: DataPoint() {
        var fieldX: Float = 0.0f
        var fieldY: Float = 0.0f
        var fieldZ: Float = 0.0f
    }
    var lastMagnetometerPoint: MagnetometerPoint? = null
    private var magnetometerHistory: ArrayList<MagnetometerPoint> = ArrayList()

    inner class GyrometerPoint: DataPoint() {
        var rotVelX: Float = 0.0f
        var rotVelY: Float = 0.0f
        var rotVelZ: Float = 0.0f
    }
    var lastGyrometerPoint: GyrometerPoint? = null
    private var gyrometerHistory: ArrayList<GyrometerPoint> = ArrayList()

    inner class TemperaturePoint: DataPoint() {
        var amount: Float = 0.0f
    }
    var lastTemperaturePoint: TemperaturePoint? = null
    private var temperatureHistory: ArrayList<TemperaturePoint> = ArrayList()

    inner class PressurePoint: DataPoint() {
        var amount: Float = 0.0f
    }
    var lastPressurePoint: PressurePoint? = null
    private var pressureHistory: ArrayList<PressurePoint> = ArrayList()

    inner class HumidityPoint: DataPoint() {
        var amount: Float = 0.0f
    }
    var lastHumidityPoint: HumidityPoint? = null
    private var humidityHistory: ArrayList<HumidityPoint> = ArrayList()

    companion object {
        var instance: DataCollectorWorker? = null
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
        val speed: Int = SensorManager.SENSOR_DELAY_FASTEST // Careful! If we are too fast we will lock-up!
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        tempSensor = sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
        gravSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        pressSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        humiSensor = sensorManager.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY)
        if(accelSensor == null) this.log.warning("No accelerometer found!") else sensorManager.registerListener(this, accelSensor, speed)
        if(tempSensor == null) this.log.warning("No temperature found!") else sensorManager.registerListener(this, tempSensor, speed)
        if(gravSensor == null) this.log.warning("No gravity found!") else sensorManager.registerListener(this, gravSensor, speed)
        if(gyroSensor == null) this.log.warning("No gyroscope found!") else sensorManager.registerListener(this, gyroSensor, speed)
        if(magSensor == null) this.log.warning("No magnetometer found!") else sensorManager.registerListener(this, magSensor, speed)
        if(pressSensor == null) this.log.warning("No pressure found!") else sensorManager.registerListener(this, pressSensor, speed)
        if(humiSensor == null) this.log.warning("No humidity found!") else sensorManager.registerListener(this, humiSensor, speed)
    }

    /**
     * This function is called on a regular basis - make sure to keep every iteration as short-as-possible
     */
    private fun loop(): Boolean {
        var runtime: Long = (System.currentTimeMillis() - this.startTime) / 1000

        runBlocking { dataPointMutex.lock() }
        this.updateNotificationContent(
            "Active since " + (runtime).toString() + " seconds and collected " +
            this.dataPointCount.toString() + " data points."
        )
        val currentDataCount: Long = this.dataPointCount
        runBlocking { dataPointMutex.unlock() } // Release the lock - just in case we don't flush now

        val done: Boolean = runtime > 300 // Should we "finish" now?

        // Do we need to flush?
        if(done || (currentDataCount - this.dataPointCountOnLastFlush > this.flushTarget)) {
            runBlocking { dataPointMutex.lock() }
            // Move the current values into our task
            var accelerometerHistoryCopy: ArrayList<AccelerometerPoint> = accelerometerHistory
            var gravityHistoryCopy: ArrayList<GravityPoint> = gravityHistory
            var magnetometerHistoryCopy: ArrayList<MagnetometerPoint> = magnetometerHistory
            var gyrometerHistoryCopy: ArrayList<GyrometerPoint> = gyrometerHistory
            var temperatureHistoryCopy: ArrayList<TemperaturePoint> = temperatureHistory
            var pressureHistoryCopy: ArrayList<PressurePoint> = pressureHistory
            var humidityHistoryCopy: ArrayList<HumidityPoint> = humidityHistory
            // Free current queued values by creating new instances
            this.accelerometerHistory = ArrayList()
            this.gravityHistory = ArrayList()
            this.magnetometerHistory = ArrayList()
            this.gyrometerHistory = ArrayList()
            this.temperatureHistory = ArrayList()
            this.pressureHistory = ArrayList()
            this.humidityHistory = ArrayList()
            runBlocking { dataPointMutex.unlock() }
            // TODO Persist data
            accelerometerHistoryCopy.clear()
            temperatureHistoryCopy.clear()
            gravityHistoryCopy.clear()
            gyrometerHistoryCopy.clear()
            magnetometerHistoryCopy.clear()
            pressureHistoryCopy.clear()
            humidityHistoryCopy.clear()
            this.dataPointCountOnLastFlush = currentDataCount
        }

        return !done
    }

    private fun run() {
        this.status = DataCollectorWorkerStatus.ALIVE
        CollectFragment.asyncUpdateUI() // TODO auto-call this on setters!

        this.log.warning("Running ONLY for 300 seconds!") // TODO Change this to unlimited... If stable.
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
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setOngoing(true)
            .setTimeoutAfter(10000) // Just re-emit it to prevent is from removal - used to "timeout" if we crash badly
            .setOnlyAlertOnce(true) // Silence on repeated updates!

        return ForegroundInfo(nId, this.notificationBuilder!!.build())
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Nope
    }

    override fun onSensorChanged(event: SensorEvent) {
        runBlocking { dataPointMutex.lock() }
        this.dataPointCount++
        if (event.sensor.type == accelSensor?.type) {
            var a = AccelerometerPoint()
            a.accelX = event.values[0]
            a.accelY = event.values[1]
            a.accelZ = event.values[2]
            this.lastAccelerometerPoint = a
            this.accelerometerHistory.add(a)
        } else if (event.sensor.type == gravSensor?.type) {
            var g = GravityPoint()
            g.accelX = event.values[0]
            g.accelY = event.values[1]
            g.accelZ = event.values[2]
            this.lastGravityPoint = g
            this.gravityHistory.add(g)
        } else if (event.sensor.type == magSensor?.type) {
            var m = MagnetometerPoint()
            m.fieldX = event.values[0]
            m.fieldY = event.values[1]
            m.fieldZ = event.values[2]
            this.lastMagnetometerPoint = m
            this.magnetometerHistory.add(m)
        } else if (event.sensor.type == gyroSensor?.type) {
            var g = GyrometerPoint()
            g.rotVelX = event.values[0]
            g.rotVelY = event.values[1]
            g.rotVelZ = event.values[2]
            this.lastGyrometerPoint = g
            this.gyrometerHistory.add(g)
        } else if (event.sensor.type == tempSensor?.type) {
            var t = TemperaturePoint()
            t.amount = event.values[0]
            this.lastTemperaturePoint = t
            this.temperatureHistory.add(t)
        } else if (event.sensor.type == pressSensor?.type) {
            var p = PressurePoint()
            p.amount = event.values[0]
            this.lastPressurePoint = p
            this.pressureHistory.add(p)
        } else if (event.sensor.type == humiSensor?.type) {
            var h = HumidityPoint()
            h.amount = event.values[0]
            this.lastHumidityPoint = h
            this.humidityHistory.add(h)
        } else
            print(event.values)
        runBlocking { dataPointMutex.unlock() }
        CollectFragment.asyncUpdateUI()
    }
}

