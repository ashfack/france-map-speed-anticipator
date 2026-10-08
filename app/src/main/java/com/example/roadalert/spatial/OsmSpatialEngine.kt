package com.example.roadalert.spatial

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream

class OsmSpatialEngine(private val context: Context) {

    private val dbName = "region_osm.sqlite"
    private val dbFile: File = context.getDatabasePath(dbName)

    init {
        copyDatabaseFromAssetsIfNeeded()
    }

    private fun copyDatabaseFromAssetsIfNeeded() {
        if (!dbFile.exists()) {
            dbFile.parentFile?.mkdirs()
            context.assets.open(dbName).use { input ->
                FileOutputStream(dbFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    /**
     * Cherche la vitesse limite autorisée aux coordonnées GPS données.
     * @return La vitesse maximale en km/h, ou null si non trouvée/non spécifiée.
     */
    fun getSpeedLimitAt(lat: Double, lon: Double): Int? {
        if (!dbFile.exists()) return null

        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        val delta = 0.0015 // ~150 mètres d'écart autorisés

        val query = """
            SELECT maxspeed
            FROM ways
            WHERE min_lat <= ? AND max_lat >= ?
              AND min_lon <= ? AND max_lon >= ?
            LIMIT 1
        """.trimIndent()

        val cursor = db.rawQuery(
            query,
            arrayOf(
                (lat + delta).toString(),
                (lat - delta).toString(),
                (lon + delta).toString(),
                (lon - delta).toString()
            )
        )

        var speedLimit: Int? = null
        if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex("maxspeed")
            if (idx != -1 && !cursor.isNull(idx)) {
                speedLimit = cursor.getInt(idx)
            }
        }

        cursor.close()
        db.close()
        return speedLimit
    }
}