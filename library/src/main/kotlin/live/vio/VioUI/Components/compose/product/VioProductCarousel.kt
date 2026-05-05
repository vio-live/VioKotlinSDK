package live.vio.VioUI.Components.compose.product

import live.vio.VioUI.Components.compose.common.SponsorBadgeContainer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyListState
import live.vio.VioCore.analytics.AnalyticsManager
import live.vio.VioCore.managers.CampaignManager
import live.vio.VioDesignSystem.Tokens.VioSpacing
import live.vio.VioUI.Components.ProductCarouselLayout
import live.vio.VioUI.Components.VioProductCardConfig
import live.vio.VioUI.Components.VioProductCarousel
import live.vio.VioUI.Components.VioProductCarouselState
import live.vio.VioUI.Managers.CartManager
import live.vio.VioUI.Managers.Product
import live.vio.VioUI.Managers.addProduct
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun VioProductCarousel(
    componentId: String? = null,
    locationId: String? = null,
    cartManager: CartManager,
    modifier: Modifier = Modifier,
    layout: String? = null,
    layoutOverride: ProductCarouselLayout? = null,
    showAddToCartButton: Boolean = false,
    showSponsor: Boolean = false,
    sponsorPosition: String? = "topRight",
    sponsorLogoUrl: String? = null,
    imageBackgroundColor: Color = Color.White,
    controller: live.vio.VioUI.Components.VioProductCarousel = remember(componentId, locationId, layoutOverride, layout) {
        val resolvedOverride = layoutOverride ?: layout?.let { ProductCarouselLayout.fromConfig(it) }
        live.vio.VioUI.Components.VioProductCarousel(
            componentId = componentId, 
            locationId = locationId,
            layoutOverride = resolvedOverride
        )
    },
    onProductTap: (Product) -> Unit = {},
    isCampaignGated: Boolean = true,
) {
    val state by controller.state.collectAsState()
    val shouldShow = if (isCampaignGated) state.isVisible else true
    if (!shouldShow) return
    val analyticsTapHandler = remember(state.componentId, state.componentName, state.campaignId, onProductTap) {
        { product: Product ->
            trackCarouselProductTap(product, state)
            onProductTap(product)
        }
    }

    when {
        state.isLoading && state.products.isEmpty() -> CarouselLoading(modifier)
        state.errorMessage != null && state.products.isEmpty() -> CarouselError(
            state.errorMessage ?: "Unable to load products",
            controller::refresh,
            modifier,
        )
        state.products.isEmpty() -> CarouselEmpty(modifier)
        else -> {
            val resolvedLayout = layoutOverride ?: layout?.let { ProductCarouselLayout.fromConfig(it) }
            CarouselContent(
                products = state.products,
                state = state,
                cartManager = cartManager,
                onProductTap = analyticsTapHandler,
                modifier = modifier,
                layoutOverride = resolvedLayout,
                showAddToCartButton = showAddToCartButton,
                showSponsor = showSponsor,
                sponsorPosition = sponsorPosition,
                sponsorLogoUrl = sponsorLogoUrl ?: state.sponsorLogoUrl,
                imageBackgroundColor = imageBackgroundColor,
            )
        }
    }
}

@Composable
private fun CarouselContent(
    products: List<Product>,
    state: VioProductCarouselState,
    cartManager: CartManager,
    onProductTap: (Product) -> Unit,
    modifier: Modifier,
    layoutOverride: ProductCarouselLayout?,
    showAddToCartButton: Boolean,
    showSponsor: Boolean,
    sponsorPosition: String?,
    sponsorLogoUrl: String?,
    imageBackgroundColor: Color,
) {
    val layoutToUse = layoutOverride ?: state.layout
    
    SponsorBadgeContainer(
        showSponsor = showSponsor,
        sponsorPosition = sponsorPosition,
        sponsorLogoUrl = sponsorLogoUrl,
    ) {
        when (layoutToUse) {
            ProductCarouselLayout.FULL -> FullCarousel(products, state, cartManager, onProductTap, modifier, showAddToCartButton, imageBackgroundColor)
            ProductCarouselLayout.COMPACT -> CompactCarousel(products, state, cartManager, onProductTap, modifier, showAddToCartButton, imageBackgroundColor)
            ProductCarouselLayout.HORIZONTAL -> HorizontalCarousel(products, state, cartManager, onProductTap, modifier, showAddToCartButton, imageBackgroundColor)
        }
    }
}

@Composable
private fun FullCarousel(
    products: List<Product>,
    state: VioProductCarouselState,
    cartManager: CartManager,
    onProductTap: (Product) -> Unit,
    modifier: Modifier,
    showAddToCartButton: Boolean,
    imageBackgroundColor: Color,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    AutoScrollEffect(products, state, listState)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = VioSpacing.md.dp),
    ) {
        val heroCardWidth = remember(constraints.maxWidth, density) {
            with(density) { constraints.maxWidth.toDp() * 0.82f }
        }
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(VioSpacing.lg.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = VioSpacing.lg.dp),
        ) {
            items(products, key = { it.id }) { product ->
                VioProductCard(
                    product = product,
                    variant = VioProductCardConfig.Variant.HERO,
                    onTap = { onProductTap(product) },
                    onAddToCart = if (showAddToCartButton) {
                        { variant, quantity -> scope.launch { cartManager.addProduct(product, variant, quantity) } }
                    } else null,
                    modifier = Modifier
                        .width(heroCardWidth),
                    imageBackgroundColor = imageBackgroundColor,
                )
            }
        }
    }
}

@Composable
private fun CompactCarousel(
    products: List<Product>,
    state: VioProductCarouselState,
    cartManager: CartManager,
    onProductTap: (Product) -> Unit,
    modifier: Modifier,
    showAddToCartButton: Boolean,
    imageBackgroundColor: Color,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    AutoScrollEffect(products, state, listState)
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(VioSpacing.md.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = VioSpacing.md.dp),
    ) {
        items(products, key = { it.id }) { product ->
            VioProductCard(
                product = product,
                variant = VioProductCardConfig.Variant.GRID,
                onTap = { onProductTap(product) },
                onAddToCart = if (showAddToCartButton) {
                    { variant, quantity -> scope.launch { cartManager.addProduct(product, variant, quantity) } }
                } else null,
                modifier = Modifier
                    .width(220.dp),
                imageBackgroundColor = imageBackgroundColor,
            )
        }
    }
}

@Composable
private fun HorizontalCarousel(
    products: List<Product>,
    state: VioProductCarouselState,
    cartManager: CartManager,
    onProductTap: (Product) -> Unit,
    modifier: Modifier,
    showAddToCartButton: Boolean,
    imageBackgroundColor: Color,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    AutoScrollEffect(products, state, listState)
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(VioSpacing.md.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = VioSpacing.lg.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = VioSpacing.md.dp),
    ) {
        items(products, key = { it.id }) { product ->
            VioProductCard(
                product = product,
                variant = VioProductCardConfig.Variant.LIST,
                onTap = { onProductTap(product) },
                onAddToCart = if (showAddToCartButton) {
                    { variant, quantity -> scope.launch { cartManager.addProduct(product, variant, quantity) } }
                } else null,
                modifier = Modifier
                    .width(320.dp),
                imageBackgroundColor = imageBackgroundColor,
            )
        }
    }
}

@Composable
private fun AutoScrollEffect(
    products: List<Product>,
    state: VioProductCarouselState,
    listState: LazyListState,
) {
    LaunchedEffect(products, state.autoPlay, state.intervalMillis) {
        if (!state.autoPlay || products.size <= 1) return@LaunchedEffect
        while (isActive) {
            delay(state.intervalMillis)
            if (!state.autoPlay || products.size <= 1) break
            if (listState.isScrollInProgress) continue
            val nextIndex = (listState.firstVisibleItemIndex + 1) % products.size
            listState.animateScrollToItem(nextIndex)
        }
    }
}

@Composable
private fun CarouselLoading(modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(VioSpacing.lg.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VioSpacing.sm.dp),
    ) {
        CircularProgressIndicator()
        Text("Loading products...", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CarouselError(message: String, onRetry: () -> Unit, modifier: Modifier) {
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
private fun CarouselEmpty(modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(VioSpacing.lg.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VioSpacing.sm.dp),
    ) {
        Text("No products available", style = MaterialTheme.typography.bodyMedium)
    }
}

private fun trackCarouselProductTap(product: Product, state: VioProductCarouselState) {
    val componentId = state.componentId ?: return
    AnalyticsManager.trackProductViewed(
        productId = product.id.toString(),
        productName = product.title,
        productPrice = product.price.amount.toDouble(),
        productCurrency = product.price.currencyCode,
        source = "product_carousel",
        componentId = componentId,
        componentType = "product_carousel",
    )
}
