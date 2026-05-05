package live.vio.VioCore.placements

import live.vio.VioCore.utils.VioLogger

/**
 * Describes a slot location exposed by the host app's UI.
 */
public data class VioPlacementLocation(
    val id: String,
    val displayName: String? = null,
)

public object VioPlacementRegistry {
    private val locationsById = mutableMapOf<String, VioPlacementLocation>()
    private val componentsByType = mutableMapOf<String, VioPlacementComponentRegistration>()

    public fun registerLocation(location: VioPlacementLocation) {
        locationsById[location.id] = location
        VioLogger.info("Registered placement location: id=${location.id}, displayName=${location.displayName}", "VioPlacementRegistry")
    }

    public fun registeredLocations(): List<VioPlacementLocation> =
        locationsById.values.sortedBy { it.id }

    public fun locationForId(id: String): VioPlacementLocation? = locationsById[id]

    public fun registerComponent(registration: VioPlacementComponentRegistration) {
        componentsByType[registration.componentType] = registration
    }

    public fun registeredComponents(): List<VioPlacementComponentRegistration> =
        componentsByType.values.toList()

    public fun manifestPayload(): Map<String, Any> {
        return mapOf(
            "locations" to registeredLocations().map { location ->
                buildMap<String, Any> {
                    put("id", location.id)
                    location.displayName?.let { put("displayName", it) }
                }
            },
        )
    }

    internal fun _resetForTesting() {
        locationsById.clear()
        componentsByType.clear()
    }
}
