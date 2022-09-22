package com.simonmicro.irimeasurement.services.points

class EstimatedLocationPoint(var from: LocationPoint, var to: LocationPoint?,
    locHeight: Double,
    locLon: Double,
    locLat: Double,
    accuDir: Float,
    accuHeight: Float,
    accuLonLat: Float,
    dir: Float,
    dirSpeed: Float,
    queried: Boolean,
    time: Long? = null
): LocationPoint(
    locHeight,
    locLon,
    locLat,
    accuDir,
    accuHeight,
    accuLonLat,
    dir,
    dirSpeed,
    queried,
    time) {
    fun wasEstimated(): Boolean {
        return this.to != null
    }

    override fun toString(): String {
        var s = super.toString()
        if(this.wasEstimated())
            s = "e$s" // Add "e" to indicate it was estimated
        return s
    }
}

fun EstimatedLocationPoint(loc: LocationPoint) = EstimatedLocationPoint(
    loc, null,
    loc.locHeight,
    loc.locLon,
    loc.locLat,
    loc.accuDir,
    loc.accuHeight,
    loc.accuLonLat,
    loc.dir,
    loc.dirSpeed,
    loc.queried,
    loc.time
)