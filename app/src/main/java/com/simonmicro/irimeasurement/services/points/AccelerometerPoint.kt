package com.simonmicro.irimeasurement.services.points

class AccelerometerPoint(var accelX: Float, var accelY: Float, var accelZ: Float, time: Long? = null): DataPoint(time) {
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

fun AccelerometerPoint(row: List<String>?): AccelerometerPoint {
    return if(row != null)
        AccelerometerPoint(row[1].toFloat(), row[2].toFloat(), row[3].toFloat(), row[0].toLong())
    else
        AccelerometerPoint(0.0f, 0.0f, 0.0f)
}