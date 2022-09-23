package com.simonmicro.irimeasurement

import android.os.Build
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.simonmicro.irimeasurement.services.CollectorService
import com.simonmicro.irimeasurement.services.StorageService
import com.simonmicro.irimeasurement.services.points.*
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.collections.ArrayList
import kotlin.io.path.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText

class Collection(val id: UUID) {
    data class CollectionMeta (
        var started: Date = Date(0),
        var finished: Date? = null,
        var pointCount: Long = 0,
        var dataSets: ArrayList<String> = ArrayList(),
        var locationTags: ArrayList<String> = ArrayList(),
        var version: String = "",
        var device: String = "", // For internal use only
        var model: String = "-",
        var manufacturer: String = "-"
    )

    private val path: Path = Path(StorageService.getCollectionsRoot().absolutePath.toString(), this.id.toString())
    private val metaPath: Path = Path(this.path.toString(), metaName)
    private var meta: CollectionMeta = CollectionMeta()

    companion object {
        private val log = com.simonmicro.irimeasurement.util.Log(Collection::class.java.name)
        private const val metaName: String = "metadata.json"
        private const val bufferSize: Int = 4096

        private fun fileToMeta(file: File): CollectionMeta {
            this.log.d("Loading metadata file: ${file.toPath()}")
            return jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).readValue<CollectionMeta>(file.readText())
        }

        fun import(input: InputStream, uuidHint: UUID?): Collection {
            // Extract the .zip to a temporary path -> apps cache dir
            var tempPath = Path(StorageService.getCache().path, UUID.randomUUID().toString())
            this.log.d("Importing to $tempPath...")
            tempPath.toFile().mkdir()
            var inp = ZipInputStream(input)
            var entry: ZipEntry? = inp.nextEntry
            while(entry != null) {
                val buffer = ByteArray(bufferSize)
                val fosp = Path(tempPath.toString(), entry.name)
                val fos = FileOutputStream(fosp.toFile())
                this.log.d("Next file is: $fosp")
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
            this.log.d("Checking for $metaName")
            val metaFile: File = Path(tempPath.toString(), metaName).toFile()
            if(!metaFile.exists())
                throw RuntimeException("The imported collection does not contain a $metaName")
            this.log.d("Loading $metaName")
            this.fileToMeta(metaFile) // If this returns, the archive could be intact!
            var collectionUUID: UUID = uuidHint?: UUID.randomUUID()
            var finalPath = Path(StorageService.getCollectionsRoot().path, collectionUUID.toString())
            var finalFile = finalPath.toFile()
            this.log.d("Moving $collectionUUID from $tempPath to $finalPath")
            if(finalFile.exists())
                finalFile.deleteRecursively()
            Files.move(tempPath, finalPath)
            this.log.i("Import of $collectionUUID completed.")
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
                "Location Tags: ${this.meta.locationTags.joinToString(prefix = "{", postfix = "}") { it }}\n" +
                "Size: ${StorageService.getBytesBetterString(this.getSizeBytes())}\n" +
                "Version: ${this.meta.version}\n" +
                "Device: ${this.meta.model} (${this.meta.manufacturer})"
    }

    fun getMeta(): CollectionMeta {
        return this.meta
    }

    fun create() {
        if(this.exist())
            return
        this.path.toFile().mkdir()
        // Init some fields only on fresh creation
        this.meta.started = Date()
        this.meta.version = BuildConfig.VERSION_NAME
        this.meta.device = Build.DEVICE
        this.meta.model = Build.MODEL
        this.meta.manufacturer = Build.MANUFACTURER
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

    fun completed(locationTags: ArrayList<String>) {
        this.meta.locationTags = locationTags
        this.meta.finished = Date()
        this.writeMetaData()
    }

    fun addCrashReport(e: Exception) {
        Path(this.path.toString(), "crash-${Date().time}.txt").writeText("Message: ${e.message}\nTrace: ${e.stackTraceToString()}\ntoString(): $e")
        this.writeMetaData()
    }

    private fun addFileToZip(out: ZipOutputStream, file: File) {
        log.d("Adding file: ${file.toPath()}")
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
        log.d("Exporting ${this.id}...")
        var out = ZipOutputStream(output)
        for(file: File in this.path.toFile().listFiles()!!) {
            if(file.isFile)
                this.addFileToZip(out, file)
        }
        out.close()
        log.i("Export of ${this.id} completed.")
    }

    fun <T: DataPoint> addPoints(points: List<T>) {
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

    fun <T: DataPoint> getPoints(factory: (row: List<String>?) -> T): List<T> {
        var points: ArrayList<T> = ArrayList()
        var pointsFile: File = Path(this.path.toString(), factory(null).getName() + ".csv").toFile()
        if(pointsFile.exists()) {
            // Now read the file line by line and recreate the points from it
            var readHeader = false
            for(line in pointsFile.readLines()) {
                if(!readHeader) {
                    readHeader = true
                    continue
                }
                points.add(factory(line.split(";")))
            }
        }
        return points
    }

    fun isInUse(): Boolean {
        return (CollectorService.instance != null && CollectorService.instance!!.collection!!.id == this.id)
    }
}