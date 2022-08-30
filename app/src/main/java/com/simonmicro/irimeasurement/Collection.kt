package com.simonmicro.irimeasurement

import android.R.attr.data
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.simonmicro.irimeasurement.services.StorageService
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.Path
import kotlin.io.path.isDirectory


class Collection(val id: UUID) {
    data class CollectionMeta (
        var creation: Date = Date()
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
        File(this.metaPath.toString()).writeText(jacksonObjectMapper().writeValueAsString(this.meta))
    }

    private fun readMetaData() {
        if(!this.exist())
            return
        this.meta = jacksonObjectMapper().readValue<CollectionMeta>(File(this.metaPath.toString()).readText())
    }

    private fun exist(): Boolean {
        return this.path.isDirectory()
    }

    fun toSnackbarString(): String {
        return "ID: ${this.id}\nFrom: ${this.meta.creation}"
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

    fun remove() {
        // Delete all sub-files
        for(file in this.path.toFile().listFiles()) {
            if(file.isFile)
                file.delete()
        }
        // Delete collection folder itself
        this.path.toFile().delete()
    }

    fun addFileToZip(out: ZipOutputStream, file: File) {
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
        for(file: File in this.path.toFile().listFiles()) {
            if(file.isFile)
                this.addFileToZip(out, file)
        }
        out.close()
    }
}