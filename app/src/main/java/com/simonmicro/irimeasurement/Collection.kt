package com.simonmicro.irimeasurement

import android.util.Log
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.simonmicro.irimeasurement.services.CollectorService
import com.simonmicro.irimeasurement.services.StorageService
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
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
    private val metaPath: Path = Path(this.path.toString(), metaName)
    private var meta: CollectionMeta = CollectionMeta()

    companion object {
        private val logTag = Collection::class.java.name
        private const val metaName: String = "metadata.json"
        private const val bufferSize: Int = 4096

        private fun fileToMeta(file: File): CollectionMeta {
            Log.d(logTag, "Loading metadata file: ${file.toPath()}")
            return jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).readValue<CollectionMeta>(file.readText())
        }

        fun import(input: InputStream, uuidHint: UUID?): Collection {
            // Extract the .zip to a temporary path -> apps cache dir
            var tempPath = Path(StorageService.getCache().path, UUID.randomUUID().toString())
            Log.d(logTag, "Importing to $tempPath...")
            tempPath.toFile().mkdir()
            var inp = ZipInputStream(input)
            var entry: ZipEntry? = inp.nextEntry
            while(entry != null) {
                val buffer = ByteArray(bufferSize)
                val fosp = Path(tempPath.toString(), entry.name)
                val fos = FileOutputStream(fosp.toFile())
                Log.d(logTag, "Next file is: $fosp")
                val bos = BufferedOutputStream(fos, buffer.size)
                var len: Int
                while (inp.read(buffer).also { len = it } > 0) {
                    bos.write(buffer, 0, len)
                }
                bos.flush()
                bos.close()
                fos.flush()
                fos.close()
                entry = inp.nextEntry
            }
            inp.close()
            // Check the metadata.json
            Log.d(logTag, "Checking for $metaName")
            val metaFile: File = Path(tempPath.toString(), metaName).toFile()
            if(!metaFile.exists())
                throw RuntimeException("The imported collection does not contain a $metaName")
            Log.d(logTag, "Loading $metaName")
            this.fileToMeta(metaFile) // If this returns, the archive could be intact!
            var collectionUUID: UUID = uuidHint?: UUID.randomUUID()
            var finalPath = Path(StorageService.getCollectionsRoot().path, collectionUUID.toString())
            var finalFile = finalPath.toFile()
            Log.d(logTag, "Moving $collectionUUID from $tempPath to $finalPath")
            if(finalFile.exists())
                finalFile.deleteRecursively()
            Files.move(tempPath, finalPath)
            Log.i(logTag, "Import of $collectionUUID completed.")
            return Collection(collectionUUID)
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
        this.meta = fileToMeta(File(this.metaPath.toString()))
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
        this.path.toFile().deleteRecursively()
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
        Log.d(logTag, "Adding file: ${file.toPath()}")
        var fi: FileInputStream = file.inputStream()
        var buffer = ByteArray(bufferSize)
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
        Log.d(logTag, "Exporting ${this.id}...")
        var out = ZipOutputStream(output)
        for(file: File in this.path.toFile().listFiles()!!) {
            if(file.isFile)
                this.addFileToZip(out, file)
        }
        out.close()
        Log.i(logTag, "Export of ${this.id} completed.")
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