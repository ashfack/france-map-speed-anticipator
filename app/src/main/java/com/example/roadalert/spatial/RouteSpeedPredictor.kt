package com.example.roadalert.spatial

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class RoadSegment(
    val id: Int,
    val speedLimit: Int,
    val oneway: Int,
    val startLatitudeE6: Int,
    val startLongitudeE6: Int,
    val endLatitudeE6: Int,
    val endLongitudeE6: Int
)

data class SpeedLimitPrediction(
    val currentSpeedLimit: Int,
    val nextSpeedLimit: Int?,
    val distanceToNextSpeedLimitMeters: Double?
)

object RouteSpeedPredictor {

    private data class DirectedSegment(
        val id: Int,
        val speedLimit: Int,
        val startLatitudeE6: Int,
        val startLongitudeE6: Int,
        val endLatitudeE6: Int,
        val endLongitudeE6: Int
    )

    private data class ProjectedSegment(
        val segment: DirectedSegment,
        val distanceFromRoadMeters: Double,
        val distanceToEndMeters: Double,
        val alignment: Double
    )

    private data class PointMeters(val east: Double, val north: Double)

    fun predict(
        segments: List<RoadSegment>,
        latitude: Double,
        longitude: Double,
        bearing: Float
    ): SpeedLimitPrediction? {
        if (!bearing.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            return null
        }

        val headingRadians = Math.toRadians(bearing.toDouble())
        val heading = PointMeters(sin(headingRadians), cos(headingRadians))
        val longitudeScale = METERS_PER_DEGREE * abs(cos(Math.toRadians(latitude)))
        val edges = segments.flatMap { segment ->
            when (segment.oneway) {
                1 -> listOf(segment.directed(forward = true))
                -1 -> listOf(segment.directed(forward = false))
                else -> listOf(
                    segment.directed(forward = true),
                    segment.directed(forward = false)
                )
            }
        }
        val adjacency = edges.groupBy { it.startNode }

        val matched = edges.mapNotNull { edge ->
            val start = edge.start.toMeters(latitude, longitude, longitudeScale)
            val end = edge.end.toMeters(latitude, longitude, longitudeScale)
            val dx = end.east - start.east
            val dy = end.north - start.north
            val lengthSquared = dx * dx + dy * dy
            if (lengthSquared == 0.0) return@mapNotNull null
            val length = kotlin.math.sqrt(lengthSquared)
            val alignment = (dx * heading.east + dy * heading.north) / length
            if (alignment < MIN_HEADING_ALIGNMENT) return@mapNotNull null

            val projection = (-(start.east * dx + start.north * dy) / lengthSquared)
                .coerceIn(0.0, 1.0)
            val closest = PointMeters(start.east + projection * dx, start.north + projection * dy)
            val distance = hypot(closest.east, closest.north)
            if (distance > MAX_ROAD_DISTANCE_METERS) return@mapNotNull null

            ProjectedSegment(edge, distance, (1.0 - projection) * length, alignment)
        }.minByOrNull {
            it.distanceFromRoadMeters + (1.0 - it.alignment) * HEADING_SCORE_WEIGHT_METERS
        } ?: return null

        var distanceAlongRoute = matched.distanceToEndMeters
        var previous = matched.segment
        val visited = mutableSetOf(previous.id to previous.startNode)
        if (distanceAlongRoute >= MAX_PREDICTION_DISTANCE_METERS) return null

        while (distanceAlongRoute < MAX_PREDICTION_DISTANCE_METERS) {
            val successors = adjacency[previous.endNode]
                .orEmpty()
                .filter { it.id != previous.id && (it.id to it.startNode) !in visited }
            val next = chooseSuccessor(previous, successors, latitude, longitude, longitudeScale)
                ?: return SpeedLimitPrediction(matched.segment.speedLimit, null, null)

            if (next.speedLimit != matched.segment.speedLimit) {
                return SpeedLimitPrediction(
                    currentSpeedLimit = matched.segment.speedLimit,
                    nextSpeedLimit = next.speedLimit,
                    distanceToNextSpeedLimitMeters = distanceAlongRoute
                )
            }

            val start = next.start.toMeters(latitude, longitude, longitudeScale)
            val end = next.end.toMeters(latitude, longitude, longitudeScale)
            distanceAlongRoute += hypot(end.east - start.east, end.north - start.north)
            previous = next
            visited.add(next.id to next.startNode)
        }

        return SpeedLimitPrediction(matched.segment.speedLimit, null, null)
    }

    private fun chooseSuccessor(
        previous: DirectedSegment,
        successors: List<DirectedSegment>,
        latitude: Double,
        longitude: Double,
        longitudeScale: Double
    ): DirectedSegment? {
        if (successors.isEmpty()) return null

        val incomingStart = previous.start.toMeters(latitude, longitude, longitudeScale)
        val incomingEnd = previous.end.toMeters(latitude, longitude, longitudeScale)
        val incomingEast = incomingEnd.east - incomingStart.east
        val incomingNorth = incomingEnd.north - incomingStart.north
        val incomingLength = hypot(incomingEast, incomingNorth)
        if (incomingLength == 0.0) return null

        val ranked = successors.mapNotNull { candidate ->
            val start = candidate.start.toMeters(latitude, longitude, longitudeScale)
            val end = candidate.end.toMeters(latitude, longitude, longitudeScale)
            val east = end.east - start.east
            val north = end.north - start.north
            val length = hypot(east, north)
            if (length == 0.0) null
            else candidate to ((incomingEast * east + incomingNorth * north) / (incomingLength * length))
        }.filter { it.second >= MIN_TURN_ALIGNMENT }
            .sortedByDescending { it.second }

        if (ranked.isEmpty()) return null
        if (
            ranked.size > 1 &&
            ranked[0].second - ranked[1].second < AMBIGUOUS_BRANCH_ALIGNMENT_GAP
        ) {
            return null
        }
        return ranked.first().first
    }

    private fun RoadSegment.directed(forward: Boolean) =
        if (forward) {
            DirectedSegment(
                id,
                speedLimit,
                startLatitudeE6,
                startLongitudeE6,
                endLatitudeE6,
                endLongitudeE6
            )
        } else {
            DirectedSegment(
                id,
                speedLimit,
                endLatitudeE6,
                endLongitudeE6,
                startLatitudeE6,
                startLongitudeE6
            )
        }

    private val DirectedSegment.startNode: Pair<Int, Int>
        get() = startLatitudeE6 to startLongitudeE6

    private val DirectedSegment.endNode: Pair<Int, Int>
        get() = endLatitudeE6 to endLongitudeE6

    private val DirectedSegment.start: PointMeters
        get() = PointMeters(startLongitudeE6.toDouble(), startLatitudeE6.toDouble())

    private val DirectedSegment.end: PointMeters
        get() = PointMeters(endLongitudeE6.toDouble(), endLatitudeE6.toDouble())

    private fun PointMeters.toMeters(
        originLatitude: Double,
        originLongitude: Double,
        longitudeScale: Double
    ) = PointMeters(
        east = (east / COORDINATE_SCALE - originLongitude) * longitudeScale,
        north = (north / COORDINATE_SCALE - originLatitude) * METERS_PER_DEGREE
    )

    private const val COORDINATE_SCALE = 1_000_000.0
    private const val METERS_PER_DEGREE = 111_320.0
    private const val MAX_ROAD_DISTANCE_METERS = 35.0
    private const val MAX_PREDICTION_DISTANCE_METERS = 1_500.0
    private const val MIN_HEADING_ALIGNMENT = 0.6427876096865394
    private const val MIN_TURN_ALIGNMENT = -0.5
    private const val HEADING_SCORE_WEIGHT_METERS = 20.0
    private const val AMBIGUOUS_BRANCH_ALIGNMENT_GAP = 0.08
}
