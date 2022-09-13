package com.simonmicro.irimeasurement

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.simonmicro.irimeasurement.services.CollectorService
import com.simonmicro.irimeasurement.services.StorageService
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.lang.RuntimeException
import java.nio.file.Path
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.collections.ArrayList
import kotlin.io.path.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText

class Collection(val id: UUID) {
    data class CollectionMeta (
        var started: Date = Date(),
        var finished: Date? = null,
        var pointCount: Long = 0,
        var dataSets: ArrayList<String> = ArrayList(),
        var version: String = BuildConfig.VERSION_NAME
    )

    private val path: Path = Path(StorageService.getCollectionsRoot().absolutePath.toString(), this.id.toString())
    private val metaPath: Path = Path(this.path.toString(), "metadata.json")
    private var meta: CollectionMeta = CollectionMeta()

    companion object {
        fun import(): Collection {
            TODO("Not implemented: Collection import")
        }
    }

    init {
        this.readMetaData()
    }

    private fun writeMetaData() {
        this.metaPath.toFile().writeText(jacksonObjectMapper().writeValueAsString(this.meta))
    }

    private fun readMetaData() {
        if(!this.exist())
            return
        this.meta = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).readValue<CollectionMeta>(File(this.metaPath.toString()).readText())
    }

    private fun exist(): Boolean {
        return this.path.isDirectory()
    }

    fun toSnackbarString(): String {
        return "ID: ${this.id}\n" +
                "Started: ${this.meta.started}\n" +
                "Finished: ${this.meta.finished}\n" +
                "Points: ${this.meta.pointCount}\n" +
                "Sets: ${this.meta.dataSets.joinToString(prefix = "{", postfix = "}") { it }}\n" +
                "Size: ${StorageService.getBytesBetterString(this.getSizeBytes())}"
    }

    fun getMeta(): CollectionMeta {
        return this.meta
    }

    fun create() {
        if(this.exist())
            return
        this.path.toFile().mkdir()
        this.writeMetaData()
    }

    fun reload() {
        if(!this.exist())
            return
        this.readMetaData()
    }

    fun remove() {
        // Delete all sub-files
        for(file in this.path.toFile().listFiles()!!) {
            if(file.isFile)
                file.delete()
        }
        // Delete collection folder itself
        this.path.toFile().delete()
    }

    fun getSizeBytes(): Long {
        var size: Long = 0
        for(file in this.path.toFile().listFiles()!!) {
            if(file.isFile)
                size += file.length()
        }
        return size
    }

    fun completed() {
        this.meta.finished = Date()
        this.writeMetaData()
    }

    fun addCrashReport(e: Exception) {
        Path(this.path.toString(), "crash-${Date().time}.txt").writeText("Message: ${e.message}\nTrace: ${e.stackTraceToString()}\ntoString(): $e")
        this.writeMetaData()
    }

    private fun addFileToZip(out: ZipOutputStream, file: File) {
        val bufferSize = 2048
        var fi: FileInputStream = file.inputStream()
        var buffer: ByteArray = ByteArray(bufferSize)
        val origin = BufferedInputStream(fi, bufferSize)
        val entry = ZipEntry(file.name)
        out.putNextEntry(entry)
        var count: Int
        while (origin.read(buffer, 0, bufferSize).also { count = it } != -1) {
            out.write(buffer, 0, count)
        }
        origin.close()
    }

    fun export(output: OutputStream) {
        var out = ZipOutputStream(output)
        for(file: File in this.path.toFile().listFiles()!!) {
            if(file.isFile)
                this.addFileToZip(out, file)
        }
        out.close()
    }

    fun <T: CollectorService.DataPoint> addPoints(points: ArrayList<T>) {
        if(this.meta.finished != null)
            throw RuntimeException("You tried to add to a completed collection")
        if(points.size == 0)
            return
        var out: File = Path(this.path.toString(), points[0].getName() + ".csv").toFile()
        if(!out.isFile) {
            out.appendText(points[0].getHeader() + "\n")
        }
        for(point in points)
            out.appendText(point.getRow() + "\n")
        this.meta.pointCount += points.size
        if(points[0].getName() !in this.meta.dataSets)
            this.meta.dataSets.add(points[0].getName())
        this.writeMetaData()
    }
}