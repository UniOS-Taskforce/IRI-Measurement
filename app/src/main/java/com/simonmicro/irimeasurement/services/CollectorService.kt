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
import android.os.Bundle
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.simonmicro.irimeasurement.BuildConfig
import com.simonmicro.irimeasurement.R
import com.simonmicro.irimeasurement.Collection
import com.simonmicro.irimeasurement.ui.CollectFragment
import com.simonmicro.irimeasurement.services.points.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList

class CollectorService(appContext: Context, workerParams: WorkerParameters): Worker(appContext, workerParams), SensorEventListener, LocationListener {
    enum class DataCollectorWorkerStatus {
        DEAD, PREPARE, ALIVE, SHUTDOWN
    }

    private var nId = 0
    private val loopExpireIn = 10000L // Stuff will begin to expire after 10 seconds (notifications, wakelocks, ...)
    private var notificationBuilder: NotificationCompat.Builder? = null
    private val log = com.simonmicro.irimeasurement.util.Log(CollectorService::class.java.name)
    private lateinit var sensorManager: SensorManager
    private var requestStop: Boolean = false
    private var locService: LocationService? = null
    private var wakelockScreen: PowerManager.WakeLock? = null
    private var wakelockCPU: PowerManager.WakeLock? = null
    var collection: Collection? = null

    private var accelSensor: Sensor? = null
    private var tempSensor: Sensor? = null
    private var gravSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var magSensor: Sensor? = null
    private var pressSensor: Sensor? = null
    private var humiSensor: Sensor? = null

    inner class CollectorLocationCallback(private var service: CollectorService): LocationCallback() {
        override fun onLocationResult(lRes: LocationResult) {
            super.onLocationResult(lRes)
            for(loc: Location in lRes.locations)
                this.service.saveLocation(loc, false)
        }

        override fun onLocationAvailability(p0: LocationAvailability) {
            // Nope
        }
    }
    private var locCallback: CollectorLocationCallback = CollectorLocationCallback(this)

    var status: DataCollectorWorkerStatus = DataCollectorWorkerStatus.DEAD
    var startTime: Long = System.currentTimeMillis()
    var dataPointCount: Long = 0
    var dataPointCountOnLastFlush: Long = 0
    val flushTarget: Int = 4096

    var dataPointMutex = Mutex()

    var lastAccelerometerPoint: AccelerometerPoint? = null
    private var accelerometerHistory: ArrayList<AccelerometerPoint> = ArrayList()

    var lastGravityPoint: GravityPoint? = null
    private var gravityHistory: ArrayList<GravityPoint> = ArrayList()

    var lastMagnetometerPoint: MagnetometerPoint? = null
    private var magnetometerHistory: ArrayList<MagnetometerPoint> = ArrayList()

    private var lastGyrometerPoint: GyrometerPoint? = null
    private var gyrometerHistory: ArrayList<GyrometerPoint> = ArrayList()

    var lastTemperaturePoint: TemperaturePoint? = null
    private var temperatureHistory: ArrayList<TemperaturePoint> = ArrayList()

    var lastPressurePoint: PressurePoint? = null
    private var pressureHistory: ArrayList<PressurePoint> = ArrayList()

    var lastHumidityPoint: HumidityPoint? = null
    private var humidityHistory: ArrayList<HumidityPoint> = ArrayList()

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
        this.locService = LocationService(this.applicationContext, null)
        if(!this.locService!!.hasLocationPermissions())
            throw RuntimeException("Missing permissions - service can't start!")
        // Create new collection for this run
        this.collection = Collection(UUID.randomUUID())
        this.collection!!.create()
        // Send the logs to the collection!
        com.simonmicro.irimeasurement.util.Log.sendLogsToCollection(this.collection)
        // Register loop, which is required by this ancient API to process incoming location updates
        if(!this.locService!!.startLocationUpdates(this.applicationContext.mainLooper, this.locCallback, this))
            this.log.w("Failed to register for location updates - still using query based solution...")
        // Get wakelock
        this.wakelockScreen = (applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, BuildConfig.APPLICATION_ID + "::collector_screen")
        this.wakelockCPU = (applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, BuildConfig.APPLICATION_ID + "::collector_cpu")
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
        if(accelSensor == null) this.log.w("No accelerometer found!") else sensorManager.registerListener(this, accelSensor, speed)
        if(tempSensor == null) this.log.w("No temperature found!") else sensorManager.registerListener(this, tempSensor, speed)
        if(gravSensor == null) this.log.w("No gravity found!") else sensorManager.registerListener(this, gravSensor, speed)
        if(gyroSensor == null) this.log.w("No gyroscope found!") else sensorManager.registerListener(this, gyroSensor, speed)
        if(magSensor == null) this.log.w("No magnetometer found!") else sensorManager.registerListener(this, magSensor, speed)
        if(pressSensor == null) this.log.w("No pressure found!") else sensorManager.registerListener(this, pressSensor, speed)
        if(humiSensor == null) this.log.w("No humidity found!") else sensorManager.registerListener(this, humiSensor, speed)
    }

    /**
     * This function is called on a regular basis - make sure to keep every iteration as short-as-possible
     */
    private fun loop(): Boolean {
        val runtime: Long = (System.currentTimeMillis() - this.startTime) / 1000

        runBlocking { dataPointMutex.lock() }
        this.updateNotificationContent("${applicationContext.getString(R.string.collector_notification_0)} $runtime ${applicationContext.getString(R.string.collector_notification_1)} ${this.dataPointCount} ${applicationContext.getString(R.string.collector_notification_2)}.")
        val currentDataCount: Long = this.dataPointCount
        runBlocking { dataPointMutex.unlock() } // Release the lock - just in case we don't flush now

        val done = false // Use this to stop after 30 seconds: "runtime > 30"

        // Do we need to flush?
        if(done || (currentDataCount - this.dataPointCountOnLastFlush > this.flushTarget)) {
            runBlocking { dataPointMutex.lock() }
            // Move the current values into our task
            val accelerometerHistoryCopy: ArrayList<AccelerometerPoint> = accelerometerHistory
            val gravityHistoryCopy: ArrayList<GravityPoint> = gravityHistory
            val magnetometerHistoryCopy: ArrayList<MagnetometerPoint> = magnetometerHistory
            val gyrometerHistoryCopy: ArrayList<GyrometerPoint> = gyrometerHistory
            val temperatureHistoryCopy: ArrayList<TemperaturePoint> = temperatureHistory
            val pressureHistoryCopy: ArrayList<PressurePoint> = pressureHistory
            val humidityHistoryCopy: ArrayList<HumidityPoint> = humidityHistory
            val locationHistoryCopy: ArrayList<LocationPoint> = locationHistory
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
            this.collection!!.addPoints(accelerometerHistoryCopy)
            this.collection!!.addPoints(temperatureHistoryCopy)
            this.collection!!.addPoints(gravityHistoryCopy)
            this.collection!!.addPoints(gyrometerHistoryCopy)
            this.collection!!.addPoints(magnetometerHistoryCopy)
            this.collection!!.addPoints(pressureHistoryCopy)
            this.collection!!.addPoints(humidityHistoryCopy)
            this.collection!!.addPoints(locationHistoryCopy)
            this.dataPointCountOnLastFlush = currentDataCount
        }

        // It seems like not all devices are properly triggering the location update callback, so we need to ask explicitly from time to time
        if(this.lastLocation == null || Date().time - this.lastLocation!!.time > 5 * 1000) {
            val loc = this.locService!!.getCurrentLocation(showWarning = false)
            if (loc != null)
                this.saveLocation(loc, true)
        }

        // Refresh the wakelocks
        try {
            if (this.wakelockScreen != null && !this.wakelockScreen!!.isHeld)
                this.wakelockScreen!!.acquire(this.loopExpireIn)
        } catch (e: Exception) {
            this.log.w("Failed to acquire the screen wake-lock: ${e.stackTraceToString()}")
        }
        try {
            if (this.wakelockCPU != null && !this.wakelockCPU!!.isHeld)
                this.wakelockCPU!!.acquire(this.loopExpireIn)
        } catch (e: Exception) {
            this.log.w("Failed to acquire the cpu wake-lock: ${e.stackTraceToString()}")
        }

        return !done
    }

    private fun run() {
        this.status = DataCollectorWorkerStatus.ALIVE
        CollectFragment.asyncUpdateUI()

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
        com.simonmicro.irimeasurement.util.Log.sendLogsToCollection(null) // Stop logging to collection
        this.locService!!.stopLocationUpdates(this.locCallback, this)
        if(this.wakelockScreen != null && this.wakelockScreen!!.isHeld)
            this.wakelockScreen!!.release()
        if(this.wakelockCPU != null && this.wakelockCPU!!.isHeld)
            this.wakelockCPU!!.release()
        sensorManager.unregisterListener(this) // This disconnects ALL sensors!
        TimeUnit.SECONDS.sleep(1) // Sleep a while to let all queued callbacks settle, so finishing the collection won't cause exceptions later on!
        NotificationManagerCompat.from(applicationContext).cancel(nId)
        this.collection!!.completed(this.locService!!.getLocationTags()) // May not executed because of crash, even if collection itself was successful: Finish after all inputs are stopped!
    }

    override fun doWork(): Result {
        var wasRunOK = true
        try {
            this.prepare()
            this.run()
        } catch (e: Exception) {
            this.collection?.addCrashReport(e)
            this.log.e("Unexpected exception in service: ${e.stackTraceToString()}")
            wasRunOK = false
        }

        try {
            this.shutdown()
        } catch(e: Exception) {
            this.collection?.addCrashReport(e)
            this.log.e("Unexpected exception in service shutdown: ${e.stackTraceToString()}")
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
        val importance = NotificationManager.IMPORTANCE_DEFAULT
        val channel = NotificationChannel(applicationContext.getString(R.string.notification_channel_id),
            applicationContext.getString(R.string.notification_channel_title), importance).apply {
                description = applicationContext.getString(R.string.notification_channel_description)
            }
        // Register the channel with the system
        val notificationManager: NotificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun createForegroundInfo(): ForegroundInfo {
        this.createNotificationChannel()
        this.notificationBuilder = NotificationCompat.Builder(applicationContext, applicationContext.getString(R.string.notification_channel_id))
            .setContentTitle(applicationContext.getString(R.string.notification_title))
            .setContentText(applicationContext.getString(R.string.loading))
            .setSmallIcon(R.drawable.ic_twotone_fiber_manual_record_24)
            .setOngoing(true)
            .setTimeoutAfter(this.loopExpireIn) // Just re-emit it to prevent is from removal - used to "timeout" if we crash badly
            .setOnlyAlertOnce(true) // Silence on repeated updates!

        return ForegroundInfo(nId, this.notificationBuilder!!.build())
    }

    override fun onProviderDisabled(provider: String) {
        // Nope
    }

    override fun onProviderEnabled(provider: String) {
        // Nope
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        // Nope
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Nope
    }

    override fun onSensorChanged(event: SensorEvent) {
        runBlocking { dataPointMutex.lock() }
        this.dataPointCount++
        if (event.sensor.type == accelSensor?.type) {
            val a = AccelerometerPoint(event.values[0], event.values[1], event.values[2])
            this.lastAccelerometerPoint = a
            this.accelerometerHistory.add(a)
        } else if (event.sensor.type == gravSensor?.type) {
            val g = GravityPoint(event.values[0], event.values[1], event.values[2])
            this.lastGravityPoint = g
            this.gravityHistory.add(g)
        } else if (event.sensor.type == magSensor?.type) {
            val m = MagnetometerPoint(event.values[0], event.values[1], event.values[2])
            this.lastMagnetometerPoint = m
            this.magnetometerHistory.add(m)
        } else if (event.sensor.type == gyroSensor?.type) {
            val g = GyrometerPoint(event.values[0], event.values[1], event.values[2])
            this.lastGyrometerPoint = g
            this.gyrometerHistory.add(g)
        } else if (event.sensor.type == tempSensor?.type) {
            val t = TemperaturePoint(event.values[0])
            this.lastTemperaturePoint = t
            this.temperatureHistory.add(t)
        } else if (event.sensor.type == pressSensor?.type) {
            val p = PressurePoint(event.values[0])
            this.lastPressurePoint = p
            this.pressureHistory.add(p)
        } else if (event.sensor.type == humiSensor?.type) {
            val h = HumidityPoint(event.values[0])
            this.lastHumidityPoint = h
            this.humidityHistory.add(h)
        } else
            print(event.values)
        runBlocking { dataPointMutex.unlock() }
        CollectFragment.asyncUpdateUI()
    }

    private fun saveLocation(location: Location, wasQueried: Boolean) {
        val loc = LocationPoint(location.altitude, location.longitude, location.latitude, location.bearingAccuracyDegrees, location.verticalAccuracyMeters, location.accuracy, location.bearing, location.speed, wasQueried)
        runBlocking { dataPointMutex.lock() }
        if(loc != this.lastLocation) {
            this.dataPointCount++
            this.locationHistory.add(loc)
            this.lastLocation = loc
        } else
            log.d("Ignored duplicate location \"update\" for $loc")
        runBlocking { dataPointMutex.unlock() }
        CollectFragment.asyncUpdateUI()
    }

    override fun onLocationChanged(location: Location) {
        this.saveLocation(location, false)
    }
}

