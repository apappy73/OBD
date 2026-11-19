package com.example.obdapp.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.obdapp.obd.PidSample

/**
 * Plain SQLite (SQLiteOpenHelper) as specified — no Room.
 *
 * Tables:
 *  - samples:      recorded live-data readings (time series per PID)
 *  - dtc_events:   log of DTC read/clear actions
 *  - vehicle_info: last known key/value vehicle info (VIN, protocol, ...)
 */
class ObdDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE samples (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                pid TEXT NOT NULL,
                value REAL NOT NULL,
                unit TEXT NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX idx_samples_pid_ts ON samples(pid, ts)")
        db.execSQL(
            """CREATE TABLE dtc_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                kind TEXT NOT NULL,
                codes TEXT NOT NULL
            )"""
        )
        db.execSQL(
            """CREATE TABLE vehicle_info (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Phase 1: destructive migration is fine for personal use.
        db.execSQL("DROP TABLE IF EXISTS samples")
        db.execSQL("DROP TABLE IF EXISTS dtc_events")
        db.execSQL("DROP TABLE IF EXISTS vehicle_info")
        onCreate(db)
    }

    fun insertSamples(samples: List<PidSample>) {
        if (samples.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (s in samples) {
                val v = s.value ?: continue
                val cv = ContentValues(4).apply {
                    put("ts", s.timestamp)
                    put("pid", s.pidKey)
                    put("value", v)
                    put("unit", s.unit)
                }
                db.insert("samples", null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Most recent [limit] numeric samples for a PID, oldest first. */
    fun recentSamples(pidKey: String, limit: Int = 300): List<Pair<Long, Float>> {
        val out = ArrayList<Pair<Long, Float>>()
        readableDatabase.query(
            "samples", arrayOf("ts", "value"), "pid = ?", arrayOf(pidKey),
            null, null, "ts DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) out.add(c.getLong(0) to c.getFloat(1))
        }
        return out.asReversed()
    }

    fun logDtcEvent(kind: String, codes: List<String>) {
        val cv = ContentValues(3).apply {
            put("ts", System.currentTimeMillis())
            put("kind", kind)
            put("codes", codes.joinToString(","))
        }
        writableDatabase.insert("dtc_events", null, cv)
    }

    fun saveVehicleInfo(info: Map<String, String>) {
        if (info.isEmpty()) return
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            for ((k, v) in info) {
                val cv = ContentValues(3).apply {
                    put("key", k)
                    put("value", v)
                    put("updated_at", now)
                }
                db.insertWithOnConflict("vehicle_info", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getVehicleInfo(): Map<String, String> {
        val out = linkedMapOf<String, String>()
        readableDatabase.query(
            "vehicle_info", arrayOf("key", "value"), null, null, null, null, "key ASC"
        ).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getString(1)
        }
        return out
    }

    companion object {
        private const val DB_NAME = "obd_history.db"
        private const val DB_VERSION = 1
    }
}
