package com.rhodesisland.terminal.ui.feed

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rhodesisland.terminal.AppContainer
import com.rhodesisland.terminal.ui.affinity.AffinityScreen

private const val FEED_AFFINITY_ROUTE = "feed_affinity/{characterId}"
private fun feedAffinityRoute(characterId: String): String = "feed_affinity/${Uri.encode(characterId)}"

/**
 * 通讯页自身的导航容器：好感页作为独立 destination 替换卡片流，
 * 不再在 CharacterFeedScreen 内条件渲染而叠在当前背景、顶部按钮与底栏之上。
 */
@Composable
fun CharacterFeedHost(
    container: AppContainer,
    bottomBarHeight: Dp,
    onAccent: (Color?) -> Unit,
    onOpenChat: (String) -> Unit,
    onNavigateToCharacters: () -> Unit,
    onOpenEncounter: () -> Unit,
    onOpenGroupChat: () -> Unit,
) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "feed_root", modifier = Modifier.fillMaxSize()) {
        composable("feed_root") {
            CharacterFeedScreen(
                container = container,
                bottomBarHeight = bottomBarHeight,
                onAccent = onAccent,
                onOpenChat = onOpenChat,
                onNavigateToCharacters = onNavigateToCharacters,
                onOpenEncounter = onOpenEncounter,
                onOpenGroupChat = onOpenGroupChat,
                onOpenAffinity = { characterId -> navController.navigate(feedAffinityRoute(characterId)) },
            )
        }
        composable(
            route = FEED_AFFINITY_ROUTE,
            arguments = listOf(navArgument("characterId") { type = NavType.StringType }),
        ) { entry ->
            val characterId = entry.arguments?.getString("characterId").orEmpty()
            val characters by container.characterRepository.characters.collectAsState(initial = emptyList())
            val character = characters.firstOrNull { it.id == characterId }
            if (character != null) {
                Box(Modifier.fillMaxSize().background(Color.Transparent).padding(bottom = bottomBarHeight)) {
                    AffinityScreen(
                        container = container,
                        character = character,
                        imageUrl = if (character.isCustom && character.image.isNotBlank()) character.image else container.assetRepository.getSelectionPicture(character.id),
                        onBack = { navController.popBackStack() },
                        onOpenEventConversation = { onOpenChat(character.id) },
                    )
                }
            }
        }
    }
}
