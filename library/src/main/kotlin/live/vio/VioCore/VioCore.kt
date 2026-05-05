package live.vio.VioCore

import live.vio.VioCore.configuration.CartConfiguration
import live.vio.VioCore.configuration.ConfigurationLoader
import live.vio.VioCore.configuration.LiveShowConfiguration
import live.vio.VioCore.configuration.NetworkConfiguration
import live.vio.VioCore.configuration.VioConfiguration
import live.vio.VioCore.configuration.VioTheme
import live.vio.VioCore.configuration.UIConfiguration
import live.vio.VioCore.models.Price
import live.vio.VioCore.models.Product
import live.vio.VioCore.models.ProductImage
import live.vio.VioCore.models.Variant
import live.vio.VioCore.managers.CacheManager
import live.vio.VioCore.utils.VioLogger

typealias VioProduct = Product
typealias VioPrice = Price
typealias VioVariant = Variant
typealias VioProductImage = ProductImage

typealias VioSDKConfiguration = VioConfiguration
typealias VioSDKTheme = VioTheme
typealias VioCartConfiguration = CartConfiguration
typealias VioNetworkConfiguration = NetworkConfiguration
typealias VioUIConfiguration = UIConfiguration
typealias VioLiveShowConfiguration = LiveShowConfiguration
typealias VioConfigurationLoader = ConfigurationLoader
typealias VioSDKLogger = VioLogger
typealias VioSDKCacheManager = CacheManager

typealias VioPlacementLocation = live.vio.VioCore.placements.VioPlacementLocation
typealias VioPlacementComponentRegistration = live.vio.VioCore.placements.VioPlacementComponentRegistration

fun registerPlacementLocation(location: VioPlacementLocation) {
    live.vio.VioCore.placements.VioPlacementRegistry.registerLocation(location)
}

fun registerPlacementComponent(registration: VioPlacementComponentRegistration) {
    live.vio.VioCore.placements.VioPlacementRegistry.registerComponent(registration)
}
