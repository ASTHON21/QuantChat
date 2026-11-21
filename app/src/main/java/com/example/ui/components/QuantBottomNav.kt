package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.QuantEmerald
import com.example.ui.theme.QuantOnSurface
import com.example.ui.theme.QuantOnSurfaceVariant
import com.example.ui.theme.QuantSurface
import com.example.ui.theme.QuantSurfaceHigh
import com.example.ui.theme.QuantTextStyles

enum class QuantTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    CHATS("chats", "Chats", Icons.Filled.ChatBubble, Icons.Outlined.ChatBubbleOutline, "nav_tab_chats"),
    MODELS("models", "Models", Icons.Filled.Memory, Icons.Outlined.Memory, "nav_tab_models")
}

@Composable
fun QuantBottomNav(
    currentRoute: String?,
    onSelectTab: (QuantTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(QuantSurface.copy(alpha = 0.94f))
            .navigationBarsPadding()
    ) {
        HorizontalDivider(color = QuantSurfaceHigh.copy(alpha = 0.7f), thickness = 0.8.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuantTab.entries.forEach { tab ->
                val isSelected = currentRoute == tab.route
                val tint = if (isSelected) QuantEmerald else QuantOnSurfaceVariant.copy(alpha = 0.72f)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag(tab.testTag)
                        .clickable { onSelectTab(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                        contentDescription = tab.label,
                        tint = tint,
                        modifier = Modifier.size(21.dp)
                    )
                    Text(
                        text = tab.label,
                        style = QuantTextStyles.LabelSm.copy(
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        ),
                        color = if (isSelected) QuantOnSurface else tint,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
        }
    }
}
