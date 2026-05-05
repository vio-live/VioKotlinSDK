package live.vio.VioUI.Components.compose.product

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import live.vio.VioDesignSystem.Tokens.VioSpacing
import live.vio.VioUI.Components.VioProductCardConfig
import live.vio.VioUI.Components.VioProductSpotlight
import live.vio.VioUI.Components.VioProductSpotlightState
import live.vio.VioCore.managers.CampaignManager
import live.vio.VioUI.Managers.CartManager
import live.vio.VioUI.Managers.Product
import live.vio.VioUI.Managers.addProduct
import kotlinx.coroutines.launch

@Composable
fun VioProductSpotlight(
    componentId: String? = null,
    locationId: String? = null,
    cartManager: CartManager,
    modifier: Modifier = Modifier,
    variantOverride: VioProductCardConfig.Variant? = null,
    showAddToCartButton: Boolean = true,
    showSponsor: Boolean = false,
    sponsorPosition: String? = "topRight",
    sponsorLogoUrl: String? = null,
    imageBackgroundColor: Color = Color.White,
    controller: VioProductSpotlight = remember(componentId, locationId) { VioProductSpotlight(componentId, locationId) },
    onProductTap: (Product) -> Unit = {},
    isCampaignGated: Boolean = true,
) {
    val state by controller.state.collectAsState()
    val shouldShow = if (isCampaignGated) state.isVisible else true
    if (!shouldShow) return

    when {
        state.isLoading -> SpotlightLoading(modifier)
        state.errorMessage != null -> SpotlightError(state.errorMessage!!, controller::refresh, modifier)
        state.product != null -> SpotlightContent(
            state = state,
            cartManager = cartManager,
            onProductTap = onProductTap,
            modifier = modifier,
            variantOverride = variantOverride,
            showAddToCartButton = showAddToCartButton,
            showSponsor = showSponsor,
            sponsorPosition = sponsorPosition,
            sponsorLogoUrl = sponsorLogoUrl,
            imageBackgroundColor = imageBackgroundColor,
        )
        else -> SpotlightEmpty(modifier)
    }
}
@Composable
private fun SpotlightContent(
    state: VioProductSpotlightState,
    cartManager: CartManager,
    onProductTap: (Product) -> Unit,
    modifier: Modifier,
    variantOverride: VioProductCardConfig.Variant?,
    showAddToCartButton: Boolean,
    showSponsor: Boolean,
    sponsorPosition: String?,
    sponsorLogoUrl: String?,
    imageBackgroundColor: Color,
) {
    val scope = rememberCoroutineScope()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(VioSpacing.lg.dp),
        verticalArrangement = Arrangement.spacedBy(VioSpacing.md.dp),
    ) {
        state.highlightText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
        }
        state.product?.let { product ->
            VioProductCard(
                product = product,
                variant = variantOverride ?: VioProductCardConfig.Variant.HERO,
                showSponsor = showSponsor,
                sponsorPosition = sponsorPosition,
                sponsorLogoUrl = sponsorLogoUrl,
                imageBackgroundColor = imageBackgroundColor,
                onTap = { onProductTap(product) },
                onAddToCart = if (showAddToCartButton) {
                    { variant, quantity -> scope.launch { cartManager.addProduct(product, variant, quantity) } }
                } else null,
            )
        }
    }
}

@Composable
private fun SpotlightLoading(modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(VioSpacing.lg.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VioSpacing.sm.dp),
    ) {
        CircularProgressIndicator()
        Text("Loading spotlight…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SpotlightError(message: String, onRetry: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(VioSpacing.lg.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VioSpacing.sm.dp),
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun SpotlightEmpty(modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(VioSpacing.lg.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VioSpacing.sm.dp),
    ) {
        Text(
            "No spotlight product configured",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Configure a spotlight product in the active campaign",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
