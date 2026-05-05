package live.vio.VioCore.placements

/**
 * Describes how many products a placement component binds to.
 */
public enum class VioProductBindingMode {
    SINGLE,
    MULTIPLE,
    CATEGORY,
}

/**
 * Descriptor del tipo de componente que el partner puede registrar.
 */
public data class VioPlacementComponentRegistration(
    val componentType: String,
    val productMode: VioProductBindingMode,
    val maxProducts: Int? = null,
)
