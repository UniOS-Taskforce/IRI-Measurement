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
    time) { }