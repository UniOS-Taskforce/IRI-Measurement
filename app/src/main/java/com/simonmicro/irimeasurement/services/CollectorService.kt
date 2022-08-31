package com.simonmicro.irimeasurement.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.impl.utils.WakeLocks.newWakeLock
import com.simonmicro.irimeasurement.BuildConfig
import com.simonmicro.irimeasurement.R
import com.simonmicro.irimeasurement.Collection
import com.simonmicro.irimeasurement.ui.CollectFragment
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import java.util.*
import java.util.logging.Logger
import kotlin.collections.ArrayList

class CollectorService(appContext: Context, workerParams: WorkerParameters): Worker(appContext, workerParams), SensorEventListener, LocationListener {
    enum class DataCollectorWorkerStatus {
        DEAD, PREPARE, ALIVE, SHUTDOWN
    }

    private var nId = 0
    private var notificationBuilder: NotificationCompat.Builder? = null
    private val log = Logger.getLogger(CollectorService::class.java.name)
    private lateinit var sensorManager: SensorManager
    private var requestStop: Boolean = false
    private var locService: LocationService? = null
    private var wakelock: PowerManager.WakeLock? = null
    var collection: Collection? = null

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

    abstract inner class DataPoint {
        var time: Long = System.currentTimeMillis()

        abstract fun getName(): String
        open fun getHeader(): String {
            return "time"
        }
        open fun getRow(): String {
            return this.time.toString()
        }
    }

    inner class AccelerometerPoint(var accelX: Float, var accelY: Float, var accelZ: Float): DataPoint() {
        override fun getName(): String {
            return "accelerometer"
        }
        override fun getHeader(): String {
            return super.getHeader() + ";X;Y;Z"
        }
        override fun getRow(): String {
            return super.getRow() + ";${this.accelX};${this.accelY};${this.accelZ}"
        }
    }
    var lastAccelerometerPoint: AccelerometerPoint? = null
    private var accelerometerHistory: ArrayList<AccelerometerPoint> = ArrayList()

    inner class GravityPoint(var accelX: Float, var accelY: Float, var accelZ: Float): DataPoint() {
        override fun getName(): String {
            return "gravity"
        }
        override fun getHeader(): String {
            return super.getHeader() + ";X;Y;Z"
        }
        override fun getRow(): String {
            return super.getRow() + ";${this.accelX};${this.accelY};${this.accelZ}"
        }
    }
    var lastGravityPoint: GravityPoint? = null
    private var gravityHistory: ArrayList<GravityPoint> = ArrayList()

    inner class MagnetometerPoint(var fieldX: Float, var fieldY: Float, var fieldZ: Float): DataPoint() {
        override fun getName(): String {
            return "magnetometer"
        }
        override fun getHeader(): String {
            return super.getHeader() + ";X;Y;Z"
        }
        override fun getRow(): String {
            return super.getRow() + ";${this.fieldX};${this.fieldY};${this.fieldZ}"
        }
    }
    var lastMagnetometerPoint: MagnetometerPoint? = null
    private var magnetometerHistory: ArrayList<MagnetometerPoint> = ArrayList()

    inner class GyrometerPoint(var rotVelX: Float, var rotVelY: Float, var rotVelZ: Float): DataPoint() {
        override fun getName(): String {
            return "gyrometer"
        }
        override fun getHeader(): String {
            return super.getHeader() + ";X;Y;Z"
        }
        override fun getRow(): String {
            return super.getRow() + ";${this.rotVelX};${this.rotVelY};${this.rotVelZ}"
        }
    }
    var lastGyrometerPoint: GyrometerPoint? = null
    private var gyrometerHistory: ArrayList<GyrometerPoint> = ArrayList()

    inner class TemperaturePoint(var amount: Float): DataPoint() {
        override fun getName(): String {
            return "temperature"
        }
        override fun getHeader(): String {
            return super.getHeader() + ";value"
        }
        override fun getRow(): String {
            return super.getRow() + ";${this.amount}"
        }
    }
    var lastTemperaturePoint: TemperaturePoint? = null
    private var temperatureHistory: ArrayList<TemperaturePoint> = ArrayList()

    inner class PressurePoint(var amount: Float): DataPoint() {
        override fun getName(): String {
            return "pressure"
        }
        override fun getHeader(): String {
            return super.getHeader() + ";value"
        }
        override fun getRow(): String {
            return super.getRow() + ";${this.amount}"
        }
    }
    var lastPressurePoint: PressurePoint? = null
    private var pressureHistory: ArrayList<PressurePoint> = ArrayList()

    inner class HumidityPoint(var amount: Float): DataPoint() {
        override fun getName(): String {
            return "humidity"
        }
        override fun getHeader(): String {
            return super.getHeader() + ";value"
        }
        override fun getRow(): String {
            return super.getRow() + ";${this.amount}"
        }
    }
    var lastHumidityPoint: HumidityPoint? = null
    private var humidityHistory: ArrayList<HumidityPoint> = ArrayList()

    inner class LocationPoint(var locHeight: Double, var locLon: Double, var locLat: Double,
                              var accuDir: Float, var accuHeight: Float, var accuLonLat: Float,
                              var dir: Float, var dirSpeed: Float, var queried: Boolean): DataPoint() {

        override fun getName(): String {
            return "location"
        }
        override fun getHeader(): String {
            return super.getHeader() + ";location height;location longitude;location latitude;accuracy direction;accuracy height;accuracy longitude latitude; direction; direction speed;queried"
        }
        override fun getRow(): String {
            return super.getRow() + ";${locHeight};${locLon};${locLat};${accuDir};${accuHeight};${accuLonLat};${dir};${dirSpeed};${queried}"
        }
    }
    private var lastLocationObject: Location? = null
    var lastLocation: LocationPoint? = null
    private var locationHistory: ArrayList<LocationPoint> = ArrayList()

    companion object {
        var instance: CollectorService? = null
    }

    private fun updateNotificationContent(i: String) {
        this.notificationBuilder!!.setContentText(i)
        NotificationManagerCompat.from(applicationContext).notify(nId, this.notificationBuilder!!.build())
    }

    private fun prepare() {
        instance = this
        this.requestStop = false
        this.status = DataCollectorWorkerStatus.PREPARE
        // Inform the WorkManager that this is a long-running service-task
        setForegroundAsync(createForegroundInfo()) // This will also create our ongoing notification
        // Check if we got location permissions
        this.locService = LocationService(applicationContext)
        if(!this.locService!!.hasLocationPermissions())
            throw RuntimeException("Missing permissions - service can't start!")
        // Create new collection for this run
        this.collection = Collection(UUID.randomUUID())
        this.collection!!.create()
        // Register loop, which is required by this ancient API to process incoming location updates
        if(!this.locService!!.startLocationUpdates(this.applicationContext.mainLooper, this))
            this.log.warning("Failed to register for location updates - still using query based solution...")
        // Get wakelock
        this.wakelock = (applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, BuildConfig.APPLICATION_ID + "::collector").apply {
                    acquire()
                }
            }
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

        val done: Boolean = false // Use this to stop after 30 seconds: runtime > 30

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
            var locationHistoryCopy: ArrayList<LocationPoint> = locationHistory
            // Free current queued values by creating new instances
            this.accelerometerHistory = ArrayList()
            this.gravityHistory = ArrayList()
            this.magnetometerHistory = ArrayList()
            this.gyrometerHistory = ArrayList()
            this.temperatureHistory = ArrayList()
            this.pressureHistory = ArrayList()
            this.humidityHistory = ArrayList()
            this.locationHistory = ArrayList()
            runBlocking { dataPointMutex.unlock() }
            this.collection!!.addPoints<AccelerometerPoint>(accelerometerHistoryCopy)
            this.collection!!.addPoints<TemperaturePoint>(temperatureHistoryCopy)
            this.collection!!.addPoints<GravityPoint>(gravityHistoryCopy)
            this.collection!!.addPoints<GyrometerPoint>(gyrometerHistoryCopy)
            this.collection!!.addPoints<MagnetometerPoint>(magnetometerHistoryCopy)
            this.collection!!.addPoints<PressurePoint>(pressureHistoryCopy)
            this.collection!!.addPoints<HumidityPoint>(humidityHistoryCopy)
            this.collection!!.addPoints<LocationPoint>(locationHistoryCopy)
            this.dataPointCountOnLastFlush = currentDataCount
        }

        // It seems like not all devices are properly triggering the location update callback, so we need to ask explicitly from time to time
        var location: Location? = this.locService!!.getUserLocation(showWarning = false)
        if(location != null && location != this.lastLocationObject) {
            this.saveLocation(location, true)
            this.lastLocationObject = location
        }

        return !done
    }

    private fun run() {
        this.status = DataCollectorWorkerStatus.ALIVE
        CollectFragment.asyncUpdateUI() // TODO auto-call this on setters!

        var wantProceed = true
        while (!this.requestStop && wantProceed) {
            instance = this // Repeat this every loop, as sometimes this reference gets lost, so the UI fails to recognize the services state
            // Update notification as first step, so we don't show it in case the user requested this worker to stop
            NotificationManagerCompat.from(applicationContext).notify(nId, this.notificationBuilder!!.build()) // Update notification, as we are still alive
            wantProceed = this.loop()
            Thread.sleep(1000) // Should be enough - for now?
        }
    }

    private fun shutdown() {
        this.status = DataCollectorWorkerStatus.SHUTDOWN
        instance = null // Communicate to the outside that we are already done...
        this.requestStop = true // Just in case we are instructed to stop by the WorkManager
        this.collection!!.completed()
        if(!this.locService!!.stopLocationUpdates(this))
            this.log.warning("Failed to unregister from location updates - did we ever subscribe successfully?")
        this.wakelock!!.release()
        sensorManager.unregisterListener(this) // This disconnects ALL sensors!
        NotificationManagerCompat.from(applicationContext).cancel(nId)
    }

    override fun doWork(): Result {
        var wasRunOK = true
        try {
            this.prepare()
            this.run()
        } catch (e: Exception) {
            this.collection?.addCrashReport(e)
            this.log.severe("Unexpected exception in service: " + e.stackTraceToString())
            wasRunOK = false
        }

        try {
            this.shutdown()
        } catch(e: Exception) {
            this.collection?.addCrashReport(e)
            this.log.severe("Unexpected exception in service shutdown: " + e.stackTraceToString())
        }

        this.status = DataCollectorWorkerStatus.DEAD
        instance = null
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
            .setSmallIcon(R.drawable.ic_twotone_fiber_manual_record_24)
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
            var a = AccelerometerPoint(event.values[0], event.values[1], event.values[2])
            this.lastAccelerometerPoint = a
            this.accelerometerHistory.add(a)
        } else if (event.sensor.type == gravSensor?.type) {
            var g = GravityPoint(event.values[0], event.values[1], event.values[2])
            this.lastGravityPoint = g
            this.gravityHistory.add(g)
        } else if (event.sensor.type == magSensor?.type) {
            var m = MagnetometerPoint(event.values[0], event.values[1], event.values[2])
            this.lastMagnetometerPoint = m
            this.magnetometerHistory.add(m)
        } else if (event.sensor.type == gyroSensor?.type) {
            var g = GyrometerPoint(event.values[0], event.values[1], event.values[2])
            this.lastGyrometerPoint = g
            this.gyrometerHistory.add(g)
        } else if (event.sensor.type == tempSensor?.type) {
            var t = TemperaturePoint(event.values[0])
            this.lastTemperaturePoint = t
            this.temperatureHistory.add(t)
        } else if (event.sensor.type == pressSensor?.type) {
            var p = PressurePoint(event.values[0])
            this.lastPressurePoint = p
            this.pressureHistory.add(p)
        } else if (event.sensor.type == humiSensor?.type) {
            var h = HumidityPoint(event.values[0])
            this.lastHumidityPoint = h
            this.humidityHistory.add(h)
        } else
            print(event.values)
        runBlocking { dataPointMutex.unlock() }
        CollectFragment.asyncUpdateUI()
    }

    private fun saveLocation(location: Location, wasQueried: Boolean) {
        runBlocking { dataPointMutex.lock() }
        this.dataPointCount++
        var l = LocationPoint(location.altitude, location.longitude, location.latitude, location.bearingAccuracyDegrees, location.verticalAccuracyMeters, location.accuracy, location.bearing, location.speed, wasQueried)
        this.lastLocation = l
        this.locationHistory.add(l)
        runBlocking { dataPointMutex.unlock() }
        CollectFragment.asyncUpdateUI()
    }

    override fun onLocationChanged(location: Location) {
        this.saveLocation(location, false)
    }
}

