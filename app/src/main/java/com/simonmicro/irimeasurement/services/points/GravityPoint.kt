package com.simonmicro.irimeasurement.services.points

class GravityPoint(var accelX: Float, var accelY: Float, var accelZ: Float, time: Long? = null): DataPoint(time) {
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