package com.simonmicro.irimeasurement.services

import android.util.Log
import com.simonmicro.irimeasurement.Collection
import com.simonmicro.irimeasurement.services.points.*
import java.lang.Double.max
import java.lang.Double.min
import java.util.*
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

class IRICalculationService {
    private val logTag = IRICalculationService::class.java.name
    private data class CollectionData(
        var start: Date,
        var end: Date,
        var accelerometer: List<AccelerometerPoint>,
        var location: List<LocationPoint>
    ) { }
    private val collection: Collection
    private var collectionData: CollectionData
    enum class SectionEnd {
        TODO
    }
    inner class Section(
        private var svc: IRICalculationService,
        var start: Date,
        var end: Date,
        var endReason: SectionEnd
    ) {
        override fun toString(): String {
            var from: EstimatedLocationPoint = svc.getLocation(this.start)
            var to: EstimatedLocationPoint = svc.getLocation(this.end)
            return "Section ${start.time} to ${end.time} from $from to $to "
        }
    }

    constructor(collection: Collection) {
        this.collection = collection
        // Load accelerometer data
        var start: Date? = null
        var end: Date? = null
        var accelerometer: List<AccelerometerPoint> = this.collection.getPoints(::AccelerometerPoint)
        for(accel in accelerometer) {
            var t = Date(accel.time)
            if(start == null || start > t)
                start = t
            if(end == null || end < t)
                end = t
        }
        var locationRaw: List<LocationPoint> = this.collection.getPoints(::LocationPoint)
        var location: ArrayList<LocationPoint> = ArrayList()
        for(loc in locationRaw) {
            var t = Date(loc.time)
            if(start == null || start > t)
                start = t
            if(end == null || end < t)
                end = t
            if(location.size == 0 || location[location.size - 1] != loc)
                location.add(loc)
        }
        if(start == null || end == null || start == end)
            throw RuntimeException("Not enough timepoints available to find a separate start and end point for at least one section?!")
        this.collectionData = CollectionData(start, end, accelerometer, location)
    }

    private fun slide(from: Double, to: Double, percent: Double): Double {
        return from + ((to - from) * max(0.0, min(percent, 1.0)))
    }

    private fun slide(from: Float, to: Float, percent: Double): Float {
        return from + ((to - from) * max(0.0, min(percent, 1.0))).toFloat()
    }

    /**
     * This function tries to interpolate the location of the user around
     * the time in question.
     */
    fun getLocation(date: Date): EstimatedLocationPoint {
        val time: Long = date.time
        // Find the from and to locations to interpolate between
        var from: LocationPoint? = null
        var to: LocationPoint? = null
        for(loc in this.collectionData.location) {
            if(from == null) {
                if(time <= loc.time)
                    from = loc
                else
                    continue
            }
            if(from != null && to == null) {
                if(loc.time >= time)
                    to = loc
                else
                    continue
            }
            if(from != null && to != null)
                break
        }
        var back: LocationPoint = this.collectionData.location[this.collectionData.location.size - 1]
        if(from == null && to == null && time > back.time) {
            // We requested a point beyond our data... So we have no information!
            from = back
            to = back
        }
        if(from == null) {
            Log.w(logTag, "Wanted: " + time.toString())
            Log.w(logTag, "Has from: " + from.toString())
            Log.w(logTag, "Has to: " + to.toString())
            Log.w(logTag, "Has from (really): " + this.collectionData.location[0].time.toString())
            Log.w(logTag, "Has to (really): " + this.collectionData.location[this.collectionData.location.size - 1].time.toString())
            throw RuntimeException("Failed to find from-location!")
        }
        if(to == null)
            throw RuntimeException("Failed to find to-location!")
        var moveDuration: Long = to.time - from.time // In ms
        if(moveDuration == 0L)
            // Okay, in zero ms nobody moves anywhere. Just return the original position
            return EstimatedLocationPoint(
                from, null,
                from.locHeight,
                from.locLon,
                from.locLat,
                from.accuDir,
                from.accuHeight,
                from.accuLonLat,
                from.dir,
                from.dirSpeed,
                from.queried
            )
        var movePercent: Double = (time - from.time).toDouble() / moveDuration.toDouble()
        return EstimatedLocationPoint(
            from, to,
            this.slide(from.locHeight, to.locHeight, movePercent),
            this.slide(from.locLon, to.locLon, movePercent),
            this.slide(from.locLat, to.locLat, movePercent),
            this.slide(from.accuDir, to.accuDir, movePercent),
            this.slide(from.accuHeight, to.accuHeight, movePercent),
            this.slide(from.accuLonLat, to.accuLonLat, movePercent),
            this.slide(from.dir, to.dir, movePercent),
            this.slide(from.dirSpeed, to.dirSpeed, movePercent),
            false
        )
    }

    /**
     * This function runs over the collection and tries to find
     * timepoints, on with e.g. the road changed, direction rapidly changed
     * or the user stood still for a while.
     */
    fun getSectionRecommendations(): List<Section> {
        // TODO - for now every location change will trigger a new "section"
        var sections: ArrayList<Section> = ArrayList()
        var last: LocationPoint? = null
        for(cDL in this.collectionData.location) {
            if(last == null || last != cDL) {
                if(last != null)
                    sections.add(Section(this, Date(last.time), Date(cDL.time), SectionEnd.TODO))
                last = cDL
            }
        }
        // Insert start and end dummy section if necessary
        if(sections.size == 0)
            sections.add(Section(this, this.collectionData.start, this.collectionData.end, SectionEnd.TODO))
        return sections
    }

    private val earthR: Double = (6371 * 1000).toDouble()
    private fun locationToXYZ(location: LocationPoint): DoubleArray {
        var lat = location.locLat
        var lon = location.locLon
        var r = earthR
        lat = Math.toRadians(lat)
        lon = Math.toRadians(lon)
        val x: Double = r * cos(lat) * cos(lon)
        val y: Double = r * cos(lat) * sin(lon)
        val z: Double = r * sin(lat)
        return doubleArrayOf(x, y, z)
    }

    fun getLocationDistance(from: LocationPoint, to: LocationPoint): Double {
        var fromXYZ: DoubleArray = this.locationToXYZ(from)
        var toXYZ: DoubleArray = this.locationToXYZ(to)
        var frac: Double = (fromXYZ[0] * toXYZ[0] + fromXYZ[1] * toXYZ[1] + fromXYZ[2] * toXYZ[2]) / ((earthR + from.locHeight) * (earthR + to.locHeight))
        frac = min(frac, 1.0) // In rare rounding cases the distance my be greater than 1, which is impossible
        var alpha: Double = acos(frac)
        var b: Double = alpha * earthR
        return b
    }

    fun getEstimatedLocationDistance(from: EstimatedLocationPoint, to: EstimatedLocationPoint): Double {
        // TODO Calc the distance over the intersecting points, not just from start to end
        return this.getLocationDistance(from, to)
    }

    /**
     * This function uses an approximation for the calculation of
     * the height offset! It will try to find the closest timepoints
     * of the different measurements by the sensors and try to match
     * them for optimal results.
     */
    fun getIRIValue(section: Section): Double {
        var dist: Double = this.getEstimatedLocationDistance(this.getLocation(section.start), this.getLocation(section.end))
        if(dist <= 0.0)
            throw RuntimeException("The sections start and end are the same point (no distance!)")
        var sum: Double = 0.0 // The part over the fraction
        var lastAcc: AccelerometerPoint? = null
        for(acc: AccelerometerPoint in this.collectionData.accelerometer) {
            if(Date(acc.time) < section.start)
                continue
            if(Date(acc.time) > section.end)
                break
            if(lastAcc == null) {
                lastAcc = acc
                continue
            }
            var alpha: Double = abs(lastAcc.accelX - acc.accelX).toDouble() + abs(lastAcc.accelY - acc.accelY).toDouble() + abs(lastAcc.accelZ - acc.accelZ).toDouble()
            var time: Double = (acc.time - lastAcc.time).toDouble()
            alpha *= time * time
            sum += alpha
            lastAcc = acc
        }
        sum *= 0.5
        assert(dist > 0) { "The distance of a section must be greater than zero ($dist)!" }
        return sum / dist
    }
}