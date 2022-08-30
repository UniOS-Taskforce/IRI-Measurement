package com.simonmicro.irimeasurement.services

import android.content.Context
import android.os.storage.StorageManager
import androidx.core.content.getSystemService
import java.util.*

class StorageService {
    companion object {
        private var manager: StorageManager? = null
        private var filesUuid: UUID? = null

        fun initWithContext(context: Context) {
            manager = context.getSystemService<StorageManager>()!!
            filesUuid = manager!!.getUuidForPath(context.filesDir)
        }

        /**
         * These bytes are may not free and must be allocated to get freed by the system
         */
        fun getFreeSpaceBytes(): Long {
            return manager?.getAllocatableBytes(filesUuid!!)!!
        }

        fun getFreeSpaceBetterString(): String {
            var free = this.getFreeSpaceBytes()
            var unit: String = "Bytes"
            if(free > 1024) {
                free /= 1024
                unit = "KiB"
            }
            if(free > 1024) {
                free /= 1024
                unit = "MiB"
            }
            if(free > 1024) {
                free /= 1024
                unit = "GiB"
            }
            return "$free $unit"
        }

        fun getFreeSpaceNormalString(): String {
            var free = this.getFreeSpaceBytes()
            var unit: String = "Bytes"
            if(free > 1000) {
                free /= 1000
                unit = "KB"
            }
            if(free > 1000) {
                free /= 1000
                unit = "MB"
            }
            if(free > 1000) {
                free /= 1000
                unit = "GB"
            }
            return "$free $unit"
        }
    }
}