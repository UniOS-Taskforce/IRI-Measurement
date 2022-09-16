package com.simonmicro.irimeasurement.services.points

class TemperaturePoint(var amount: Float, time: Long? = null): DataPoint(time) {
    override fun getName(): String {
        return "temperature"
    }

    override fun getHeader(): String {
        return super.getHeader() + ";value"
    }

    override fun getRow(): String {
        return super.getRow() + ";${this.amount}"
    }
}