package com.example.roadalert.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteSpeedPredictorTest {

    @Test
    fun followsConnectedSegmentsAndMeasuresToNextSpeedChange() {
        val prediction = RouteSpeedPredictor.predict(
            segments = listOf(
                segment(1, 50, 0.0, 0.0, 0.0, 0.001),
                segment(2, 50, 0.0, 0.001, 0.0, 0.002),
                segment(3, 30, 0.0, 0.002, 0.0, 0.003)
            ),
            latitude = 0.0,
            longitude = 0.00025,
            bearing = 90f
        )

        assertEquals(50, prediction?.currentSpeedLimit)
        assertEquals(30, prediction?.nextSpeedLimit)
        assertEquals(194.81, prediction?.distanceToNextSpeedLimitMeters!!, 1.0)
    }

    @Test
    fun respectsReverseOnewayDirection() {
        val segments = listOf(
            segment(1, 30, 0.0, 0.0, 0.0, 0.001, oneway = -1),
            segment(2, 50, 0.0, 0.001, 0.0, 0.002, oneway = -1)
        )

        val prediction = RouteSpeedPredictor.predict(
            segments = segments,
            latitude = 0.0,
            longitude = 0.00175,
            bearing = 270f
        )

        assertEquals(50, prediction?.currentSpeedLimit)
        assertEquals(30, prediction?.nextSpeedLimit)
    }

    @Test
    fun doesNotInventATransitionAcrossDisconnectedRoads() {
        val prediction = RouteSpeedPredictor.predict(
            segments = listOf(
                segment(1, 50, 0.0, 0.0, 0.0, 0.001),
                segment(2, 30, 0.0, 0.0011, 0.0, 0.002)
            ),
            latitude = 0.0,
            longitude = 0.00025,
            bearing = 90f
        )

        assertEquals(50, prediction?.currentSpeedLimit)
        assertNull(prediction?.nextSpeedLimit)
        assertNull(prediction?.distanceToNextSpeedLimitMeters)
    }

    @Test
    fun returnsNoMatchWhenGpsIsTooFarFromMappedRoad() {
        val prediction = RouteSpeedPredictor.predict(
            segments = listOf(segment(1, 50, 0.0, 0.0, 0.0, 0.001)),
            latitude = 0.001,
            longitude = 0.0005,
            bearing = 90f
        )

        assertNull(prediction)
    }

    private fun segment(
        id: Int,
        speed: Int,
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double,
        oneway: Int = 0
    ) = RoadSegment(
        id = id,
        speedLimit = speed,
        oneway = oneway,
        startLatitudeE6 = (startLat * COORDINATE_SCALE).toInt(),
        startLongitudeE6 = (startLon * COORDINATE_SCALE).toInt(),
        endLatitudeE6 = (endLat * COORDINATE_SCALE).toInt(),
        endLongitudeE6 = (endLon * COORDINATE_SCALE).toInt()
    )

    private companion object {
        const val COORDINATE_SCALE = 1_000_000.0
    }
}
