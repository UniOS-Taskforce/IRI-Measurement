package com.simonmicro.irimeasurement.services.points

abstract class DataPoint {
    var time: Long

    constructor(time: Long?) {
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