package com.simonmicro.irimeasurement.services

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.nio.file.Path
import java.util.*
import kotlin.io.path.Path
import kotlin.io.path.isDirectory

class StorageCollection(val id: UUID) {
    data class CollectionMeta (
        var creation: Date = Date()
    )

    private val path: Path = Path(StorageService.getCollectionsRoot().absolutePath.toString(), this.id.toString())
    private val metaPath: Path = Path(this.path.toString(), "metadata.json")
    private var meta: CollectionMeta = CollectionMeta()

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
}