package live.vio.VioUI.Components.compose.offers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import live.vio.VioCore.configuration.VioConfiguration
import live.vio.VioCore.managers.CampaignManager
import live.vio.VioCore.models.ComponentManager
import live.vio.VioCore.models.OfferBannerConfig
import live.vio.VioUI.Components.compose.product.VioImageLoader
import live.vio.VioUI.Components.compose.product.VioImageLoaderDefaults
import live.vio.VioUI.Components.compose.theme.adaptiveVioColors
import kotlinx.coroutines.delay

@Composable
fun VioOfferBannerDynamic(
    modifier: Modifier = Modifier,
    onNavigateToStore: (() -> Unit)? = null,
    imageLoader: VioImageLoader = VioImageLoaderDefaults.current,
    showSponsor: Boolean = false,
    sponsorPosition: String? = "topRight",
    sponsorLogoUrl: String? = null,
) {
    val componentManager = remember { ComponentManager.shared }
    val campaignManager = remember { CampaignManager.shared }
    val activeBanner by componentManager.activeBanner.collectAsState()
    val activeBannerComponent by componentManager.activeBannerComponent.collectAsState()
    val isConnected by componentManager.isConnected.collectAsState()
    val isCampaignActive by campaignManager.isCampaignActive.collectAsState()
    val currentCampaign by campaignManager.currentCampaign.collectAsState()

    var isLoading by remember { mutableStateOf(true) }
    var hasShownInitialSkeleton by remember { mutableStateOf(false) }

    val shouldShow = remember(activeBanner, isLoading, isCampaignActive, currentCampaign) {
        // Campaign must be active and not paused
        val campaignId = VioConfiguration.shared.state.value.liveShow.campaignId
        val baseCheck = if (campaignId > 0) {
            isCampaignActive && currentCampaign?.isPaused != true
        } else {
            true // Legacy/Auto-loading behavior
        }
        
        baseCheck && (activeBanner != null || isLoading)
    }

    LaunchedEffect(Unit) {
        if (activeBanner != null) {
            hasShownInitialSkeleton = true
            isLoading = false
        } else {
            hasShownInitialSkeleton = false
            delay(300) // Small delay to show skeleton nicely
            hasShownInitialSkeleton = true
            componentManager.connect()
        }
    }

    LaunchedEffect(activeBanner, isConnected) {
        if (activeBanner != null) {
            isLoading = false
            hasShownInitialSkeleton = true
        } else if (isConnected && !isLoading) {
            // Connected but no banner - stop loading
            isLoading = false
        }
    }

    if (!shouldShow && !isLoading) return

    Box(modifier = modifier.fillMaxWidth()) {
        val showSkeleton = (isLoading && activeBanner == null) || (!hasShownInitialSkeleton && activeBanner == null)

        AnimatedVisibility(
            visible = showSkeleton,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            OfferBannerSkeleton()
        }

        AnimatedVisibility(
            visible = activeBanner != null && !showSkeleton,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            activeBanner?.let { config ->
                VioOfferBanner(
                    config = config,
                    onNavigateToStore = onNavigateToStore,
                    imageLoader = imageLoader,
                    showSponsor = showSponsor,
                    sponsorPosition = sponsorPosition,
                    sponsorLogoUrl = sponsorLogoUrl ?: activeBannerComponent?.sponsor?.logoUrl,
                )
            }
        }
    }
}

@Composable
private fun OfferBannerSkeleton() {
    val colors = adaptiveVioColors()
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceSecondary.copy(alpha = 0.1f))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Logo skeleton
                Box(
                    modifier = Modifier
                        .size(width = 100.dp, height = 16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.surfaceSecondary.copy(alpha = alpha))
                )
                // Title skeleton
                Box(
                    modifier = Modifier
                        .size(width = 150.dp, height = 24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.surfaceSecondary.copy(alpha = alpha))
                )
                // Subtitle skeleton
                Box(
                    modifier = Modifier
                        .size(width = 120.dp, height = 12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.surfaceSecondary.copy(alpha = alpha * 0.8f))
                )
                // Countdown pills skeleton
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(4) {
                        Box(
                            modifier = Modifier
                                .size(width = 36.dp, height = 24.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(colors.surfaceSecondary.copy(alpha = alpha))
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Badge skeleton
                Box(
                    modifier = Modifier
                        .size(width = 80.dp, height = 32.dp)
                        .clip(CircleShape)
                        .background(colors.surfaceSecondary.copy(alpha = alpha * 1.2f))
                )
                // Button skeleton
                Box(
                    modifier = Modifier
                        .size(width = 100.dp, height = 28.dp)
                        .clip(CircleShape)
                        .background(colors.surfaceSecondary.copy(alpha = alpha * 1.2f))
                )
            }
        }
    }
}
