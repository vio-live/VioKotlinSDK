package live.vio.VioUI.Components.compose.product

import live.vio.VioUI.Components.compose.common.SponsorBadgeContainer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import live.vio.VioDesignSystem.Tokens.VioColors
import live.vio.VioDesignSystem.Tokens.VioSpacing
import live.vio.VioUI.Components.ProductStoreDisplayType
import live.vio.VioUI.Components.VioProductCardConfig
import live.vio.VioUI.Components.VioProductStore
import live.vio.VioUI.Components.compose.utils.toVioColor
import live.vio.VioCore.managers.CampaignManager
import live.vio.VioUI.Managers.CartManager
import live.vio.VioUI.Managers.Product
import live.vio.VioUI.Managers.addProduct
import kotlinx.coroutines.launch

@Composable
fun VioProductStore(
    componentId: String? = null,
    locationId: String? = null,
    cartManager: CartManager,
    modifier: Modifier = Modifier,
    isScrollEnabled: Boolean = true,
    showSponsor: Boolean = false,
    sponsorPosition: String? = "topRight",
    sponsorLogoUrl: String? = null,
    imageBackgroundColor: Color = Color.White,
    controller: VioProductStore = remember(componentId, locationId) { VioProductStore(componentId, locationId) },
    isCampaignGated: Boolean = true,
) {
    val state by controller.state.collectAsState()
    val shouldShow = if (isCampaignGated) state.isVisible else true
    if (!shouldShow || state.isMarketUnavailable) return

    when {
        state.isLoading && state.products.isEmpty() -> LoadingState(modifier)
        state.errorMessage != null && state.products.isEmpty() -> ErrorState(
            message = state.errorMessage ?: "Unable to load products",
            onRetry = controller::refresh,
            modifier = modifier,
        )
        state.products.isEmpty() -> EmptyState(modifier)
        state.displayType == ProductStoreDisplayType.GRID -> SponsorBadgeContainer(
            showSponsor = showSponsor,
            sponsorPosition = sponsorPosition,
            sponsorLogoUrl = sponsorLogoUrl,
        ) {
            GridContent(
                products = state.products,
                columns = state.columns,
                cartManager = cartManager,
                modifier = modifier,
                isScrollEnabled = isScrollEnabled,
                imageBackgroundColor = imageBackgroundColor,
            )
        }
        else -> SponsorBadgeContainer(
            showSponsor = showSponsor,
            sponsorPosition = sponsorPosition,
            sponsorLogoUrl = sponsorLogoUrl,
        ) {
            ListContent(
                products = state.products,
                cartManager = cartManager,
                modifier = modifier,
                isScrollEnabled = isScrollEnabled,
                imageBackgroundColor = imageBackgroundColor,
            )
        }
    }
}

@Composable
private fun GridContent(
    products: List<Product>,
    columns: Int,
    cartManager: CartManager,
    modifier: Modifier,
    isScrollEnabled: Boolean,
    imageBackgroundColor: Color,
) {
    val scope = rememberCoroutineScope()
    if (isScrollEnabled) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns.coerceAtLeast(1)),
            verticalArrangement = Arrangement.spacedBy(VioSpacing.md.dp),
            horizontalArrangement = Arrangement.spacedBy(VioSpacing.md.dp),
            contentPadding = PaddingValues(vertical = VioSpacing.md.dp),
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = VioSpacing.md.dp),
        ) {
            items(products, key = { it.id }) { product ->
                VioProductCard(
                    product = product,
                    variant = VioProductCardConfig.Variant.GRID,
                    imageBackgroundColor = imageBackgroundColor,
                    onAddToCart = { variant, quantity ->
                        scope.launch { cartManager.addProduct(product, variant, quantity) }
                    },
                )
            }
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = VioSpacing.md.dp, vertical = VioSpacing.md.dp),
            verticalArrangement = Arrangement.spacedBy(VioSpacing.md.dp)
        ) {
            val rows = products.chunked(columns.coerceAtLeast(1))
            for (rowProducts in rows) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(VioSpacing.md.dp)
                ) {
                    for (product in rowProducts) {
                        Box(modifier = Modifier.weight(1f)) {
                            VioProductCard(
                                product = product,
                                variant = VioProductCardConfig.Variant.GRID,
                                imageBackgroundColor = imageBackgroundColor,
                                onAddToCart = { variant, quantity ->
                                    scope.launch { cartManager.addProduct(product, variant, quantity) }
                                },
                            )
                        }
                    }
                    // Add spacers for empty grid cells in the last row
                    if (rowProducts.size < columns) {
                        repeat(columns - rowProducts.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListContent(
    products: List<Product>,
    cartManager: CartManager,
    modifier: Modifier,
    isScrollEnabled: Boolean,
    imageBackgroundColor: Color,
) {
    val scope = rememberCoroutineScope()
    if (isScrollEnabled) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(VioSpacing.md.dp),
            contentPadding = PaddingValues(vertical = VioSpacing.md.dp),
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = VioSpacing.md.dp),
        ) {
            items(products, key = { it.id }) { product ->
                VioProductCard(
                    product = product,
                    variant = VioProductCardConfig.Variant.LIST,
                    imageBackgroundColor = imageBackgroundColor,
                    onAddToCart = { variant, quantity ->
                        scope.launch { cartManager.addProduct(product, variant, quantity) }
                    },
                )
            }
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = VioSpacing.md.dp, vertical = VioSpacing.md.dp),
            verticalArrangement = Arrangement.spacedBy(VioSpacing.md.dp)
        ) {
            for (product in products) {
                VioProductCard(
                    product = product,
                    variant = VioProductCardConfig.Variant.LIST,
                    imageBackgroundColor = imageBackgroundColor,
                    onAddToCart = { variant, quantity ->
                        scope.launch { cartManager.addProduct(product, variant, quantity) }
                    },
                )
            }
        }
    }
}

@Composable
private fun LoadingState(modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(VioSpacing.xl.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VioSpacing.sm.dp),
    ) {
        CircularProgressIndicator()
        Text(
            "Loading products...",
            style = MaterialTheme.typography.bodyMedium,
            color = VioColors.textSecondary.toVioColor(),
        )
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(VioSpacing.xl.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VioSpacing.sm.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            "Error loading products",
            style = MaterialTheme.typography.bodyLarge,
            color = VioColors.textPrimary.toVioColor(),
        )
        if (message.isNotBlank()) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = VioColors.textSecondary.toVioColor(),
                textAlign = TextAlign.Center,
            )
        }
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                contentColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Text("Retry")
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(VioSpacing.xl.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VioSpacing.sm.dp),
    ) {
        Text(
            text = "No products available",
            style = MaterialTheme.typography.bodyLarge,
            color = VioColors.textPrimary.toVioColor(),
        )
        Text(
            text = "Products will appear here when available",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = VioColors.textSecondary.toVioColor(),
        )
    }
}
