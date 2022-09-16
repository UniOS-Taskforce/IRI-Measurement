package com.simonmicro.irimeasurement.services.points

class GyrometerPoint(var rotVelX: Float, var rotVelY: Float, var rotVelZ: Float, time: Long? = null): DataPoint(time) {
    override fun getName(): String {
        return "gyrometer"
    }

    override fun getHeader(): String {
        return super.getHeader() + ";X;Y;Z"
    }

    override fun getRow(): String {
        return super.getRow() + ";${this.rotVelX};${this.rotVelY};${this.rotVelZ}"
    }
}