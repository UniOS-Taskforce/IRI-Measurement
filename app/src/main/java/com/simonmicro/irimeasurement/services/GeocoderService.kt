package com.simonmicro.irimeasurement.services

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.CursorFactory
import android.database.sqlite.SQLiteOpenHelper
import android.location.Geocoder
import com.simonmicro.irimeasurement.BuildConfig
import java.io.Closeable
import java.io.File
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import java.util.*
import kotlin.io.path.Path
import kotlin.io.path.exists

fun LocalDate.toDate(): Date = Date.from(this.atStartOfDay(ZoneId.systemDefault()).toInstant())

class GeocoderService(context: Context): Closeable {
    inner class DatabaseContext(base: Context?, private var parentDir: Path) : ContextWrapper(base) {
        // After the solution in https://stackoverflow.com/a/9168969/11664229
        override fun getDatabasePath(nameReq: String): File {
            var name = nameReq
            if (!nameReq.endsWith(".db"))
                name += ".db"
            val p = Path(parentDir.toString(), name)
            if(!p.parent.exists())
                p.parent.toFile().mkdirs()
            return p.toFile()
        }

        /* this version is called for android devices >= api-11. thank to @damccull for fixing this. */
        override fun openOrCreateDatabase(name: String, mode: Int, factory: CursorFactory, errorHandler: DatabaseErrorHandler?): SQLiteDatabase {
            return openOrCreateDatabase(name, mode, factory)
        }

        /* this version is called for android devices < api-11 */
        override fun openOrCreateDatabase(name: String, mode: Int, factory: CursorFactory): SQLiteDatabase {
            return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), null)
        }
    }

    inner class GeoCacheDBHandler(context: Context): SQLiteOpenHelper(context, "geocoder", null, 30) {
        private val tableName = "cache"
        private val columnNameTime = "time"
        private val columnNameLatitude = "lat"
        private val columnNameLongitude = "lon"
        private val columnNameAddress = "addr"

        override fun onCreate(db: SQLiteDatabase?) {
            db?.execSQL("CREATE TABLE $tableName ($columnNameTime INTEGER PRIMARY KEY, $columnNameLatitude DOUBLE, $columnNameLongitude DOUBLE, $columnNameAddress TEXT)")
        }

        override fun onUpgrade(db: SQLiteDatabase?, p1: Int, p2: Int) {
            db?.execSQL("DROP TABLE IF EXISTS $tableName")
            onCreate(db)
        }

        fun get(lat: Double, lon: Double): String? {
            val db = this.readableDatabase
            db.rawQuery("SELECT $columnNameAddress FROM $tableName WHERE $columnNameLatitude = ? AND $columnNameLongitude = ? LIMIT 1", arrayOf(lat.toString(), lon.toString())).use {
                if(it.moveToFirst()) {
                    val index = it.getColumnIndex(columnNameAddress)
                    if(index >= 0)
                        return it.getString(index)
                }
            }
            return null
        }

        fun set(lat: Double, lon: Double, address: String) {
            this.writableDatabase.execSQL("INSERT INTO $tableName ($columnNameTime, $columnNameLatitude, $columnNameLongitude, $columnNameAddress) VALUES (?, ?, ?, ?)", arrayOf(Date().time, lat, lon, address))
        }

        fun expire() {
            this.writableDatabase.execSQL("DELETE FROM $tableName WHERE $columnNameTime < ?", arrayOf(LocalDate.now().minusWeeks(1).toDate().time))
        }

        fun count(): Long {
            val db = this.readableDatabase
            db.rawQuery("SELECT COUNT(*) FROM $tableName", null).use {
                if(it.moveToFirst())
                    return it.getLong(0)
            }
            return 0
        }
    }

    private val log = com.simonmicro.irimeasurement.util.Log(GeocoderService::class.java.name)
    private val geocoder: Geocoder
    private val db: GeoCacheDBHandler
    private var reqCnt = 0L
    private var reqCachedCnt = 0L

    init {
        this.geocoder = Geocoder(context)
        this.db = GeoCacheDBHandler(DatabaseContext(context, Path(StorageService.getCache().path)))
        log.d("Loaded cache with ${db.count()} entries.")
    }

    private fun getLocation(lat: Double, lon: Double): String? {
        val addresses = geocoder.getFromLocation(lat, lon, 1)
        if(addresses != null && addresses.isNotEmpty()) {
            val address = addresses[0]
            if (address.maxAddressLineIndex >= 0)
                return address.getAddressLine(0)
        }
        return null
    }

    fun getCachedLocation(lat: Double, lon: Double): String? {
        reqCnt += 1
        val cached = this.db.get(lat, lon)
        if(cached == null) {
            val resolved = this.getLocation(lat, lon)
            if(resolved != null) {
                this.db.set(lat, lon, resolved)
                return resolved
            }
        } else
            reqCachedCnt += 1
        return cached
    }

    override fun close() {
        val old = this.db.count()
        this.db.expire()
        val new = this.db.count()
        if(old != new)
            this.log.d("Expired ${old - new} entries")
        this.db.close()
        this.log.d("Closed cache of $new addresses after processing $reqCnt requests ($reqCachedCnt cached)")
    }
}