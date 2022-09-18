package com.simonmicro.irimeasurement.services.points

open class LocationPoint(var locHeight: Double, var locLon: Double, var locLat: Double,
                    var accuDir: Float, var accuHeight: Float, var accuLonLat: Float,
                    var dir: Float, var dirSpeed: Float, var queried: Boolean, time: Long? = null): DataPoint(time) {

    override fun getName(): String {
        return "location"
    }

    override fun getHeader(): String {
        return super.getHeader() + ";location height;location longitude;location latitude;accuracy direction;accuracy height;accuracy longitude latitude; direction; direction speed;queried"
    }

    override fun getRow(): String {
        return super.getRow() + ";${locHeight};${locLon};${locLat};${accuDir};${accuHeight};${accuLonLat};${dir};${dirSpeed};${queried}"
    }

    override fun toString(): String {
        return "(lon $locLon, lat $locLat, height $locHeight)"
    }
}

fun LocationPoint(row: List<String>?): LocationPoint {
    return if(row != null)
        LocationPoint(row[1].toDouble(), row[2].toDouble(), row[3].toDouble(), row[4].toFloat(), row[5].toFloat(), row[6].toFloat(), row[7].toFloat(), row[8].toFloat(), row[9].toBooleanStrictOrNull()?:false, row[0].toLong())
    else
        LocationPoint(0.0, 0.0, 0.0, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, false)
}