package com.simonmicro.irimeasurement.services.points

class MagnetometerPoint(var fieldX: Float, var fieldY: Float, var fieldZ: Float, time: Long? = null): DataPoint(time) {
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