package com.simonmicro.irimeasurement.services

import android.content.Context
import android.os.storage.StorageManager
import androidx.core.content.getSystemService
import com.simonmicro.irimeasurement.Collection
import java.io.File
import java.nio.file.Path
import java.util.*
import kotlin.collections.ArrayList
import kotlin.io.path.Path

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

        fun getTutorialConfigPath(): Path {
            return Path(context!!.filesDir.path, "tutorial.cfg")
        }

        fun getBytesBetterString(bytes: Long): String {
            var free = bytes
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

        fun getBytesNormalString(bytes: Long): String {
            var free = bytes
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
            val ret = File(this.context!!.filesDir, "collections")
            ret.mkdir()
            return ret
        }

        fun getCache(): File {
            this.context!!.cacheDir.mkdir()
            val ret = File(this.context!!.cacheDir, "collections")
            ret.mkdir()
            return ret
        }

        fun listCollections(): ArrayList<Collection> {
            val all: File = this.getCollectionsRoot()
            val list: ArrayList<Collection> = ArrayList()
            for(it: File in all.listFiles()!!) {
                if(it.isDirectory) {
                    list.add(Collection(UUID.fromString(it.name)))
                }
            }
            return list
        }
    }
}