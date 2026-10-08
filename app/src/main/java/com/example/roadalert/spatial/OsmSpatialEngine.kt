package com.example.roadalert.spatial

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class OsmSpatialEngine(private val context: Context) {

    private val dbName = "region_osm.sqlite"
    private val dbAssetName = "$dbName.gz"
    private val dbFile: File = context.getDatabasePath(dbName)

    private data class SegmentCandidate(
        val speedLimit: Int,
        val startLatitude: Double,
        val startLongitude: Double,
        val endLatitude: Double,
        val endLongitude: Double,
        val oneway: Int
    )

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
    fun getSpeedLimitAt(lat: Double, lon: Double, bearing: Float?): Int? {
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
    ): Int? {
        val latitudeDelta = MAX_LOOK_AHEAD_METERS / METERS_PER_DEGREE
        val longitudeScaleFactor = abs(cos(Math.toRadians(lat))).coerceAtLeast(0.01)
        val longitudeDelta = latitudeDelta / longitudeScaleFactor
        val query = """
            SELECT s.maxspeed, s.start_lat_e6, s.start_lon_e6,
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

        val candidates = mutableListOf<SegmentCandidate>()
        cursor.use {
            val speedIndex = it.getColumnIndexOrThrow("maxspeed")
            val startLatIndex = it.getColumnIndexOrThrow("start_lat_e6")
            val startLonIndex = it.getColumnIndexOrThrow("start_lon_e6")
            val endLatIndex = it.getColumnIndexOrThrow("end_lat_e6")
            val endLonIndex = it.getColumnIndexOrThrow("end_lon_e6")
            val onewayIndex = it.getColumnIndexOrThrow("oneway")
            while (it.moveToNext()) {
                candidates += SegmentCandidate(
                    speedLimit = it.getInt(speedIndex),
                    startLatitude = it.getInt(startLatIndex) / COORDINATE_SCALE,
                    startLongitude = it.getInt(startLonIndex) / COORDINATE_SCALE,
                    endLatitude = it.getInt(endLatIndex) / COORDINATE_SCALE,
                    endLongitude = it.getInt(endLonIndex) / COORDINATE_SCALE,
                    oneway = it.getInt(onewayIndex)
                )
            }
        }

        val heading = Math.toRadians(bearing.toDouble())
        val headingEast = sin(heading)
        val headingNorth = cos(heading)
        val longitudeScale = METERS_PER_DEGREE * longitudeScaleFactor
        val minimumAlignment = cos(Math.toRadians(MAX_DIRECTION_DEVIATION_DEGREES))

        return candidates.mapNotNull { candidate ->
            val startEast = (candidate.startLongitude - lon) * longitudeScale
            val startNorth = (candidate.startLatitude - lat) * METERS_PER_DEGREE
            val endEast = (candidate.endLongitude - lon) * longitudeScale
            val endNorth = (candidate.endLatitude - lat) * METERS_PER_DEGREE
            val segmentEast = endEast - startEast
            val segmentNorth = endNorth - startNorth
            val segmentLength = hypot(segmentEast, segmentNorth)
            if (segmentLength == 0.0) return@mapNotNull null

            val alignment =
                (segmentEast * headingEast + segmentNorth * headingNorth) / segmentLength
            val directionMatches = when (candidate.oneway) {
                1 -> alignment >= minimumAlignment
                -1 -> alignment <= -minimumAlignment
                else -> abs(alignment) >= minimumAlignment
            }
            if (!directionMatches) return@mapNotNull null

            val segmentLengthSquared = segmentEast * segmentEast + segmentNorth * segmentNorth
            val projection = (
                -(startEast * segmentEast + startNorth * segmentNorth) / segmentLengthSquared
                ).coerceIn(0.0, 1.0)
            val closestEast = startEast + projection * segmentEast
            val closestNorth = startNorth + projection * segmentNorth
            val forwardMeters = closestEast * headingEast + closestNorth * headingNorth
            val lateralMeters = abs(closestEast * headingNorth - closestNorth * headingEast)
            val distanceMeters = hypot(closestEast, closestNorth)

            if (
                forwardMeters >= -MAX_BEHIND_METERS &&
                forwardMeters <= MAX_LOOK_AHEAD_METERS &&
                lateralMeters <= MAX_LATERAL_DISTANCE_METERS
            ) {
                candidate to (distanceMeters + maxOf(0.0, -forwardMeters) * 2.0)
            } else {
                null
            }
        }
            .minByOrNull { it.second }
            ?.first
            ?.speedLimit
    }

    private companion object {
        const val METERS_PER_DEGREE = 111_320.0
        const val COORDINATE_SCALE = 1_000_000.0
        const val MAX_LOOK_AHEAD_METERS = 200.0
        const val MAX_LATERAL_DISTANCE_METERS = 35.0
        const val MAX_BEHIND_METERS = 15.0
        const val MAX_DIRECTION_DEVIATION_DEGREES = 50.0
        const val VERSION_ASSET = "region_osm.version"
        const val SEGMENT_DATABASE_VERSION = 3
        const val PREFERENCES = "osm_database"
        const val INSTALLED_VERSION = "installed_version"
    }
}
