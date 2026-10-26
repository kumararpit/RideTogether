package com.example.data.offline.model

/**
 * Type of an offline mesh message. Mirrors the existing [com.example.data.model.QuickMessageType]
 * for quick messages and adds SOS and telemetry types for offline delivery.
 */
enum class OfflineMessageType(val label: String) {
    QUICK_MESSAGE("Quick message"),
    SOS("SOS alert"),
    LOCATION_UPDATE("Location update"),
    CUSTOM("Custom payload")
}
