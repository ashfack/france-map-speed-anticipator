package com.example.roadalert.spatial

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.cos

class OsmSpatialEngine(private val context: Context) {

    private val dbName = "region_osm.sqlite"
    private val dbAssetName = "$dbName.gz"
    private val dbFile: File = context.getDatabasePath(dbName)

    init {
        installBundledDatabaseIfNeeded()
    }

    private fun installBundledDatabaseIfNeeded() {
        val assetVersion = context.assets.open(VERSION_ASSET).bufferedReader().use {
            it.readText().trim().toIntOrNull()
                ?: throw IllegalStateException("Invalid $VERSION_ASSET asset")
        }
        require(assetVersion >= SEGMENT_DATABASE_VERSION) {
            "Bundled OSM database version $assetVersion is unsupported"
        }
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val installedVersion = preferences.getInt(INSTALLED_VERSION, -1)
        if (dbFile.exists() && installedVersion == assetVersion) return

        dbFile.parentFile?.mkdirs()
        val replacement = File(dbFile.parentFile, "$dbName.new")
        try {
            context.assets.open(dbAssetName).use { asset ->
                GZIPInputStream(asset.buffered()).use { input ->
                    FileOutputStream(replacement).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
            }

            if (!hasSpatialIndex(replacement)) {
                throw IllegalStateException(
                    "Bundled database version $assetVersion has no spatial segment index"
                )
            }

            Os.rename(replacement.absolutePath, dbFile.absolutePath)
            preferences.edit().putInt(INSTALLED_VERSION, assetVersion).apply()
        } finally {
            replacement.delete()
        }
    }

    private fun hasSpatialIndex(file: File): Boolean {
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            db.version == SEGMENT_DATABASE_VERSION &&
                db.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'segment_index'",
                    null
                ).use { it.moveToFirst() }
        } finally {
            db.close()
        }
    }

    /**
     * Retourne la limitation du segment routier proche et compatible avec le cap GPS.
     */
    fun getSpeedLimitPrediction(
        lat: Double,
        lon: Double,
        bearing: Float?
    ): SpeedLimitPrediction? {
        if (!dbFile.exists() || bearing == null || !bearing.isFinite()) return null

        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            return getSpeedLimitFromSegments(db, lat, lon, bearing)
        } finally {
            db.close()
        }
    }

    private fun getSpeedLimitFromSegments(
        db: SQLiteDatabase,
        lat: Double,
        lon: Double,
        bearing: Float
    ): SpeedLimitPrediction? {
        val latitudeDelta = SEGMENT_SEARCH_RADIUS_METERS / METERS_PER_DEGREE
        val longitudeScaleFactor = abs(cos(Math.toRadians(lat))).coerceAtLeast(0.01)
        val longitudeDelta = latitudeDelta / longitudeScaleFactor
        val query = """
            SELECT s.segment_id, s.way_id, s.maxspeed, s.start_lat_e6, s.start_lon_e6,
                   s.end_lat_e6, s.end_lon_e6, s.oneway
            FROM segment_index AS i
            JOIN segments AS s ON s.segment_id = i.segment_id
            WHERE i.min_lat <= ? AND i.max_lat >= ?
              AND i.min_lon <= ? AND i.max_lon >= ?
        """.trimIndent()
        val cursor = db.rawQuery(
            query,
            arrayOf(
                (lat + latitudeDelta).toString(),
                (lat - latitudeDelta).toString(),
                (lon + longitudeDelta).toString(),
                (lon - longitudeDelta).toString()
            )
        )

        val candidates = mutableListOf<RoadSegment>()
        cursor.use {
            val idIndex = it.getColumnIndexOrThrow("segment_id")
            val wayIdIndex = it.getColumnIndexOrThrow("way_id")
            val speedIndex = it.getColumnIndexOrThrow("maxspeed")
            val startLatIndex = it.getColumnIndexOrThrow("start_lat_e6")
            val startLonIndex = it.getColumnIndexOrThrow("start_lon_e6")
            val endLatIndex = it.getColumnIndexOrThrow("end_lat_e6")
            val endLonIndex = it.getColumnIndexOrThrow("end_lon_e6")
            val onewayIndex = it.getColumnIndexOrThrow("oneway")
            while (it.moveToNext()) {
                candidates += RoadSegment(
                    id = it.getInt(idIndex),
                    wayId = it.getLong(wayIdIndex),
                    speedLimit = it.getInt(speedIndex),
                    oneway = it.getInt(onewayIndex),
                    startLatitudeE6 = it.getInt(startLatIndex),
                    startLongitudeE6 = it.getInt(startLonIndex),
                    endLatitudeE6 = it.getInt(endLatIndex),
                    endLongitudeE6 = it.getInt(endLonIndex)
                )
            }
        }

        return RouteSpeedPredictor.predict(candidates, lat, lon, bearing)
    }

    private companion object {
        const val METERS_PER_DEGREE = 111_320.0
        const val SEGMENT_SEARCH_RADIUS_METERS = 1_550.0
        const val VERSION_ASSET = "region_osm.version"
        const val SEGMENT_DATABASE_VERSION = 3
        const val PREFERENCES = "osm_database"
        const val INSTALLED_VERSION = "installed_version"
    }
}
