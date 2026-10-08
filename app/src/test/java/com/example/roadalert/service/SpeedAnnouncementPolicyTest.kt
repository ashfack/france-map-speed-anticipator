package com.example.roadalert.service

import com.example.roadalert.spatial.SpeedLimitPrediction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedAnnouncementPolicyTest {

    @Test
    fun highConfidencePredictionAnnouncesOnFirstUpdate() {
        val policy = SpeedAnnouncementPolicy()

        val announcement = policy.onPrediction(prediction(distance = 120.0), now = 0)

        assertEquals(
            "Dans environ 120 mètres, la limitation passera à 30 kilomètres heure",
            announcement?.speech
        )
    }

    @Test
    fun uncertainPredictionWaitsForOneConsistentUpdate() {
        val policy = SpeedAnnouncementPolicy()

        assertNull(policy.onPrediction(prediction(uncertain = true), now = 0))
        val announcement = policy.onPrediction(prediction(uncertain = true), now = 1_000)

        assertEquals(
            "Dans environ 120 mètres, la limitation passera à 30 kilomètres heure",
            announcement?.speech
        )
    }

    @Test
    fun veryShortDistanceIsAnnouncedImmediatelyWithoutDistanceGate() {
        val policy = SpeedAnnouncementPolicy()

        val announcement = policy.onPrediction(prediction(distance = 7.0), now = 0)

        assertEquals(
            "Attention, la limitation va passer à 30 kilomètres heure",
            announcement?.speech
        )
    }

    @Test
    fun sameTransitionIsBufferedButDifferentTransitionIsNot() {
        val policy = SpeedAnnouncementPolicy()

        assertEquals(30, policy.onPrediction(prediction(), now = 0)?.targetSpeed)
        assertNull(policy.onPrediction(prediction(), now = 5_000))
        assertEquals(
            50,
            policy.onPrediction(
                SpeedLimitPrediction(30, 50, 500.0, requiresConfirmation = false),
                now = 5_001
            )?.targetSpeed
        )
    }

    @Test
    fun reachingAnnouncedLimitDoesNotCauseDuplicateAnnouncement() {
        val policy = SpeedAnnouncementPolicy()

        policy.onPrediction(prediction(), now = 0)
        val crossing = policy.onPrediction(
            SpeedLimitPrediction(30, 50, 300.0, requiresConfirmation = false),
            now = 1_000
        )

        assertNull(crossing)
    }

    private fun prediction(
        distance: Double = 120.0,
        uncertain: Boolean = false
    ) = SpeedLimitPrediction(
        currentSpeedLimit = 50,
        nextSpeedLimit = 30,
        distanceToNextSpeedLimitMeters = distance,
        requiresConfirmation = uncertain
    )
}
