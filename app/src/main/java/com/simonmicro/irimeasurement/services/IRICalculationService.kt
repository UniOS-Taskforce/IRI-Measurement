package com.simonmicro.irimeasurement.services

import android.content.Context
import android.location.Geocoder
import com.simonmicro.irimeasurement.Collection
import com.simonmicro.irimeasurement.R
import com.simonmicro.irimeasurement.services.points.*
import java.lang.Double.max
import java.lang.Double.min
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

class IRICalculationService(private val collection: Collection, private val context: Context, private var useAccelerometer: Boolean, private var useGeocoding: Boolean) {
    private val log = com.simonmicro.irimeasurement.util.Log(IRICalculationService::class.java.name)
    private data class CollectionData(
        var start: Date,
        var end: Date,
        var accelerometer: List<AccelerometerPoint>,
        var location: List<LocationPoint>
    )

    private var collectionData: CollectionData
    inner class Segment(var locations: List<EstimatedLocationPoint>) {
        var start: Date
        var end: Date

        init {
            assert(this.locations.size > 1) { "Every segment must have at least two locations!" }
            this.start = Date(this.locations[0].time)
            this.end = Date(this.locations[this.locations.size - 1].time)
            //assert(this.start != this.end) { "Every segment must not start and end at the same location!" } // This is possible, if the user shook the phone and the location was not updated (yet)
        }

        override fun toString(): String {
            val from: EstimatedLocationPoint = this.locations[0]
            val to: EstimatedLocationPoint = this.locations[this.locations.size - 1]
            return "Segment of ${this.locations.size} locations, from ${from.time} to ${to.time}, from $from to $to"
        }
    }

    init {
        var start: Date? = null
        var end: Date? = null
        val accelerometer: List<AccelerometerPoint> = this.collection.getPoints(::AccelerometerPoint)
        for(accel in accelerometer) {
            val t = Date(accel.time)
            if(start == null || start > t)
                start = t
            if(end == null || end < t)
                end = t
        }
        val locationRaw: List<LocationPoint> = this.collection.getPoints(::LocationPoint)
        val location: ArrayList<LocationPoint> = ArrayList()
        for(loc in locationRaw) {
            val t = Date(loc.time)
            if(start == null || start > t)
                start = t
            if(end == null || end < t)
                end = t
            if(location.size == 0 || location[location.size - 1] != loc)
                location.add(loc)
        }
        if(start == null || end == null || start == end)
            throw RuntimeException("Not enough timepoints available to find a separate start and end point for at least one section!")
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
    private fun getLocation(date: Date): EstimatedLocationPoint {
        if(this.collectionData.location.isEmpty())
            throw RuntimeException("The collection must have at least one location stored!")
        val time: Long = date.time
        // Find the from and to locations to interpolate between
        var from: LocationPoint? = null
        var to: LocationPoint? = null
        for(locId in this.collectionData.location.indices) {
            val loc = this.collectionData.location[locId]
            if(loc.time >= time) {
                to = loc
                from = if(locId > 0) this.collectionData.location[locId - 1] else to
                break
            }
        }
        val back: LocationPoint = this.collectionData.location[this.collectionData.location.size - 1]
        if(from == null && to == null && time > back.time) {
            // We requested a point beyond our data... So we have no information!
            from = back
            to = back
        }
        if(from == null) {
            this.log.w("Wanted: $time")
            this.log.w("Has to: $to")
            this.log.w("Has from (really): " + this.collectionData.location[0].time.toString())
            this.log.w("Has to (really): " + this.collectionData.location[this.collectionData.location.size - 1].time.toString())
            throw RuntimeException("Failed to find from-location!")
        }
        if(to == null)
            throw RuntimeException("Failed to find to-location!")
        val moveDuration: Long = to.time - from.time // In ms
        val movePercent: Double = min(1.0, (time - from.time).toDouble() / moveDuration.toDouble())
        if(moveDuration == 0L || movePercent == 0.0 || movePercent == 1.0)
            // Okay, in zero ms nobody moves anywhere. Just return the original position
            return EstimatedLocationPoint(from)
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
            false,
            this.slide(from.time.toDouble(), to.time.toDouble(), movePercent).toLong()
        )
    }

    /**
     * This function runs over the collection and tries to find
     * timepoints, on with e.g. the road changed, direction rapidly changed
     * or the user stood still for a while.
     */
    fun getSectionRecommendations(context: Context, progressNotification: (description: String, percent: Double) -> Unit): List<Segment> {
        var sectionTimes = ArrayList<Date>()
        if(this.useAccelerometer) {
            // Search all times were acceleration differs greater than variance from average
            var accelAvg = 0.0
            for(accel in this.collectionData.accelerometer)
                accelAvg += accel.accelX + accel.accelY + accel.accelZ
            accelAvg /= this.collectionData.accelerometer.size
            var accelVar = 0.0
            for(accel in this.collectionData.accelerometer) {
                val p: Double = ((accel.accelX + accel.accelY + accel.accelZ) - accelAvg)
                val q: Double = p * p
                accelVar += q
            }
            if(this.collectionData.location.size < 2)
                throw RuntimeException("For the variance calculation we need at least two accelerometer-points (otherwise divide by zero)!")
            accelVar /= this.collectionData.accelerometer.size - 1
            this.log.d("Accelerometer average $accelAvg with variance of $accelVar")
            for(accelId in this.collectionData.accelerometer.indices) {
                val accel = this.collectionData.accelerometer[accelId]
                val p: Double = (accel.accelX + accel.accelY + accel.accelZ).toDouble()
                progressNotification(context.getString(R.string.acceleration), accelId / this.collectionData.accelerometer.size.toDouble())
                if(accelAvg + accelVar > p && accelAvg - accelVar < p)
                    continue
                sectionTimes.add(Date(accel.time))
                this.log.d("Accelerometer triggered new section at ${accel.time} with $p")
            }
        }
        if(this.useGeocoding) {
            // Use Geocoder to trigger new segments on roads
            if(Geocoder.isPresent()) {
                GeocoderService(this.context).use {
                    var currentAddressLine = ""
                    for (locationId in this.collectionData.location.indices) {
                        val location = this.collectionData.location[locationId]
                        val line = it.getCachedLocation(location.locLat, location.locLon)
                        if(line != null) {
                            // Every address is more or less like this: "Sutthauser Str. 52, 49124 Georgsmarienhütte, Germany"
                            // I think everything is relevant - except the house number -> e.g. if the street changes we want to know that!
                            val simpleLine = line.replace(Regex("(.+)(\\s\\d+)(,\\s\\d+)"), "$1$3")
                            if (simpleLine != currentAddressLine) {
                                this.log.d("Next location is at \"$simpleLine\" (from \"$line\") at ${location.time}")
                                sectionTimes.add(Date(location.time))
                                currentAddressLine = simpleLine
                            }
                        }
                        progressNotification(context.getString(R.string.geocoding), locationId / this.collectionData.location.size.toDouble())
                    }
                }
            } else
                this.log.w("Geocoder is not available!")
        }

        if(sectionTimes.isEmpty())
            this.log.w("No segments could be found - no criteria yielded any results!r")

        progressNotification(context.getString(R.string.sort), -1.0)
        sectionTimes = ArrayList(sectionTimes.sorted())

        // Now assemble the segments, based on the segmentTimes and available data
        val sections = ArrayList<Segment>()
        var locationsForNextSegment = ArrayList<EstimatedLocationPoint>()
        locationsForNextSegment.add(this.getLocation(this.collectionData.start)) // For the very start!
        for(locId in this.collectionData.location.indices) {
            val location = this.collectionData.location[locId]
            while(sectionTimes.isNotEmpty() && sectionTimes[0] < Date(location.time)) {
                // Next segment time is smaller than the location -> create new segment
                val thisLoc = this.getLocation(sectionTimes[0])
                sectionTimes.removeAt(0)
                locationsForNextSegment.add(thisLoc)
                if(this.getEstimatedLocationDistance(locationsForNextSegment) > 16) { // Only add segments, which are greater than 16 meters
                    sections.add(Segment(locationsForNextSegment))
                    locationsForNextSegment = ArrayList() // Do not clear, as the segment would then be too...
                    locationsForNextSegment.add(thisLoc)
                }
            }
            locationsForNextSegment.add(EstimatedLocationPoint(location))
            progressNotification(context.getString(R.string.assemble), locId / this.collectionData.location.size.toDouble())
        }
        if(locationsForNextSegment.size > 1) {
            locationsForNextSegment.add(this.getLocation(this.collectionData.end)) // For the very end!
            sections.add(Segment(locationsForNextSegment))
        }
        locationsForNextSegment = ArrayList()
        assert(locationsForNextSegment.isEmpty()) { "We must not forget the last segment locations!" }
        return sections
    }

    private val earthR: Double = (6371 * 1000).toDouble() // Earth radius in m
    private fun locationToXYZ(location: LocationPoint): DoubleArray {
        var lat = location.locLat
        var lon = location.locLon
        val r = earthR + location.locHeight // in m
        lat = Math.toRadians(lat)
        lon = Math.toRadians(lon)
        val x: Double = r * cos(lat) * cos(lon)
        val y: Double = r * cos(lat) * sin(lon)
        val z: Double = r * sin(lat)
        return doubleArrayOf(x, y, z) // In m
    }

    private fun getLocationDistance(from: LocationPoint, to: LocationPoint): Double {
        if(from == to)
            return 0.0
        val fromXYZ: DoubleArray = this.locationToXYZ(from)
        val toXYZ: DoubleArray = this.locationToXYZ(to)
        if(fromXYZ[0] == toXYZ[0] && fromXYZ[1] == toXYZ[1] && fromXYZ[2] == toXYZ[2])
            return 0.0
        var frac: Double = (fromXYZ[0] * toXYZ[0] + fromXYZ[1] * toXYZ[1] + fromXYZ[2] * toXYZ[2]) / ((earthR + from.locHeight) * (earthR + to.locHeight))
        frac = min(frac, 1.0) // In rare rounding cases the distance my be greater than 1, which is impossible
        val alpha: Double = acos(frac)
        return alpha * earthR // in m
    }

    private fun getEstimatedLocationDistance(locations: List<EstimatedLocationPoint>): Double {
        var dist = 0.0
        for(locId in 1 until locations.size) {
            val from = locations[locId - 1]
            val to = locations[locId]
            dist += this.getLocationDistance(from, to)
        }
        return dist
    }

    /**
     * This function uses an approximation for the calculation of
     * the height offset! It will try to find the closest timepoints
     * of the different measurements by the sensors and try to match
     * them for optimal results.
     */
    fun getIRIValue(segment: Segment): Double {
        val dist: Double = this.getEstimatedLocationDistance(segment.locations)
        if(dist <= 0.0)
            throw RuntimeException("The sections start and end are the same point (no distance!)")
        var sum = 0.0 // The part over the fraction
        var lastAcc: AccelerometerPoint? = null
        for(acc: AccelerometerPoint in this.collectionData.accelerometer) {
            if(Date(acc.time) < segment.start)
                continue
            if(Date(acc.time) > segment.end)
                break
            if(lastAcc == null) {
                lastAcc = acc
                continue
            }
            var alpha: Double = abs(lastAcc.accelX - acc.accelX).toDouble() + abs(lastAcc.accelY - acc.accelY).toDouble() + abs(lastAcc.accelZ - acc.accelZ).toDouble()
            val time: Double = (acc.time - lastAcc.time).toDouble()
            alpha *= time * time
            sum += alpha
            lastAcc = acc
        }
        sum *= 0.5
        assert(dist > 0) { "The distance of a section must be greater than zero ($dist)!" }
        return sum / dist
    }
}