package com.simonmicro.irimeasurement.services

import android.content.Context
import android.os.storage.StorageManager
import androidx.core.content.getSystemService
import java.io.File
import java.util.*
import kotlin.collections.ArrayList

class StorageService {
    companion object {
        private var context: Context? = null
        private var filesUuid: UUID? = null

        fun initWithContext(context: Context) {
            this.context = context
            this.filesUuid = context.getSystemService<StorageManager>()!!.getUuidForPath(context.filesDir)
        }

        fun getFreeSpaceBytes(): Long {
            return context!!.filesDir.usableSpace
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

        fun getCollectionsRoot(): File {
            this.context!!.filesDir.mkdir()
            var ret = File(this.context!!.filesDir, "collections")
            ret.mkdir()
            return ret
        }

        fun listCollections(): ArrayList<StorageCollection> {
            var all: File = this.getCollectionsRoot()
            var list: ArrayList<StorageCollection> = ArrayList()
            for(it: File in all.listFiles()) {
                if(it.isDirectory) {
                    list.add(StorageCollection(UUID.fromString(it.name)))
                }
            }
            return list
        }
    }
}