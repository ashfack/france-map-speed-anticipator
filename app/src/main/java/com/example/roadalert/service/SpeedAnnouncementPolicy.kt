package com.example.roadalert.service

import com.example.roadalert.spatial.SpeedLimitPrediction
import kotlin.math.roundToInt

data class SpeedAnnouncement(
    val speech: String,
    val notification: String,
    val targetSpeed: Int?
)

class SpeedAnnouncementPolicy {

    private var lastCurrentSpeed: Int? = null
    private var announcedTargetSpeed: Int? = null
    private var pendingKey: String? = null
    private var pendingCount = 0
    private val recentAnnouncements = LinkedHashMap<String, Long>()

    fun onPrediction(
        prediction: SpeedLimitPrediction?,
        nowMillis: Long
    ): SpeedAnnouncement? {
        if (prediction == null) {
            clearPending()
            return null
        }

        if (prediction.currentSpeedLimit == announcedTargetSpeed) {
            lastCurrentSpeed = prediction.currentSpeedLimit
            announcedTargetSpeed = null
            clearPending()
            return null
        }

        val candidate = createCandidate(prediction) ?: run {
            clearPending()
            return null
        }
        if (isBuffered(candidate.key, nowMillis)) {
            clearPending()
            return null
        }

        if (candidate.key == pendingKey) {
            pendingCount++
        } else {
            pendingKey = candidate.key
            pendingCount = 1
        }

        val requiredUpdates = if (prediction.requiresConfirmation) {
            UNCERTAIN_CONFIRMATION_UPDATES
        } else {
            CONFIDENT_CONFIRMATION_UPDATES
        }
        if (pendingCount < requiredUpdates) return null

        recentAnnouncements[candidate.key] = nowMillis
        trimAnnouncementBuffer()
        lastCurrentSpeed = prediction.currentSpeedLimit
        if (candidate.targetSpeed != null) {
            announcedTargetSpeed = candidate.targetSpeed
        }
        clearPending()
        return candidate.announcement
    }

    private fun createCandidate(prediction: SpeedLimitPrediction): Candidate? {
        val nextSpeed = prediction.nextSpeedLimit
        val distance = prediction.distanceToNextSpeedLimitMeters
        if (
            nextSpeed != null &&
            distance != null &&
            distance >= 0.0 &&
            nextSpeed != prediction.currentSpeedLimit &&
            nextSpeed != announcedTargetSpeed
        ) {
            val roundedDistance = (distance / DISTANCE_ROUNDING_METERS).roundToInt() *
                DISTANCE_ROUNDING_METERS
            val speech = if (distance <= IMMEDIATE_WARNING_DISTANCE_METERS) {
                "Attention, la limitation va passer à $nextSpeed kilomètres heure"
            } else {
                "Dans environ $roundedDistance mètres, la limitation passera à $nextSpeed kilomètres heure"
            }
            return Candidate(
                key = "transition:${prediction.currentSpeedLimit}->$nextSpeed",
                targetSpeed = nextSpeed,
                announcement = SpeedAnnouncement(
                    speech = speech,
                    notification = speech,
                    targetSpeed = nextSpeed
                )
            )
        }

        if (prediction.currentSpeedLimit != lastCurrentSpeed) {
            val previousSpeed = lastCurrentSpeed?.toString() ?: "unknown"
            val speed = prediction.currentSpeedLimit
            val message = if (lastCurrentSpeed == null) {
                "Limitation de vitesse à $speed kilomètres heure"
            } else {
                "La limitation passe à $speed kilomètres heure"
            }
            return Candidate(
                key = "current:$previousSpeed->$speed",
                targetSpeed = null,
                announcement = SpeedAnnouncement(
                    speech = message,
                    notification = "Limitation actuelle : $speed km/h",
                    targetSpeed = null
                )
            )
        }
        return null
    }

    private fun isBuffered(key: String, nowMillis: Long): Boolean {
        val lastAnnouncement = recentAnnouncements[key] ?: return false
        if (nowMillis - lastAnnouncement < DUPLICATE_BUFFER_MILLIS) return true
        recentAnnouncements.remove(key)
        return false
    }

    private fun trimAnnouncementBuffer() {
        while (recentAnnouncements.size > MAX_BUFFERED_ANNOUNCEMENTS) {
            recentAnnouncements.remove(recentAnnouncements.keys.first())
        }
    }

    private fun clearPending() {
        pendingKey = null
        pendingCount = 0
    }

    private data class Candidate(
        val key: String,
        val targetSpeed: Int?,
        val announcement: SpeedAnnouncement
    )

    private companion object {
        const val CONFIDENT_CONFIRMATION_UPDATES = 1
        const val UNCERTAIN_CONFIRMATION_UPDATES = 2
        const val IMMEDIATE_WARNING_DISTANCE_METERS = 25.0
        const val DISTANCE_ROUNDING_METERS = 10.0
        const val DUPLICATE_BUFFER_MILLIS = 30_000L
        const val MAX_BUFFERED_ANNOUNCEMENTS = 16
    }
}
