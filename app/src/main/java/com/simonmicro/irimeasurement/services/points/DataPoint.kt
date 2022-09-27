package com.simonmicro.irimeasurement.services.points

abstract class DataPoint(time: Long?) {
    var time: Long

    init {
        this.time = time?: System.currentTimeMillis()
    }

    abstract fun getName(): String

    open fun getHeader(): String {
        return "time"
    }

    open fun getRow(): String {
        return this.time.toString()
    }
}