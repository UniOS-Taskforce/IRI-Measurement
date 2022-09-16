package com.simonmicro.irimeasurement.services

import android.util.Log
import com.simonmicro.irimeasurement.Collection
import com.simonmicro.irimeasurement.services.points.*
import java.lang.Double.max
import java.lang.Double.min
import java.util.*
import kotlin.collections.ArrayList

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
    fun getLocation(date: Date): LocationPoint {
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
            return from
        var movePercent: Double = (time - from.time).toDouble() / moveDuration.toDouble()
        return LocationPoint(
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
    fun getSectionRecommendations(): List<Date> {
        // TODO - for now every location change will trigger a new "section"
        var sections: ArrayList<Date> = ArrayList()
        var last: LocationPoint? = null
        for(cDL in this.collectionData.location) {
            if(last == null || last != cDL) {
                sections.add(Date(cDL.time))
                last = cDL
            }
        }
        // Insert start is necessary
        if(sections[0] != this.collectionData.start)
            sections.add(0, this.collectionData.start)
        // Insert end is necessary
        if(sections[sections.size - 1] != this.collectionData.end)
            sections.add(this.collectionData.end)
        assert(sections.size >= 2) { "Well, we should have at least two points?!" }
        return sections
    }

    /**
     * This function uses an approximation for the calculation of
     * the height offset! It will try to find the closest timepoints
     * of the different measurements by the sensors and try to match
     * them for optimal results.
     */
    fun getIRIValue(start: Date, end: Date): Double {
        // TODO - for now always nothing (perfect)...
        return 0.0
    }
}