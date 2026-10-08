package pl.apargb.milkyway

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class Department(val title: String, val accent: Color, val tint: Color, val icon: ImageVector) {
    Reception("Odbieralnia", Color(0xFF245DA8), Color(0xFFEAF2FF), Icons.Outlined.LocalShipping),
    Processing("Aparatownia", Color(0xFF08796B), Color(0xFFE4F5F0), Icons.Outlined.Tune),
    Butter("Masłownia", Color(0xFF8D590C), Color(0xFFFFF3D9), Icons.Outlined.WbSunny),
    Powder("Proszkownia", Color(0xFF7045A4), Color(0xFFF2EBFC), Icons.Outlined.Grain),
    Oils("Oleje", Color(0xFF9A4364), Color(0xFFFCECF2), Icons.Outlined.Opacity)
}

fun departmentFor(group: String): Department = when (group.substringBefore(" —")) {
    "Odbieralnia" -> Department.Reception
    "Aparatownia" -> Department.Processing
    "Masłownia" -> Department.Butter
    "Proszkownia" -> Department.Powder
    "Oleje" -> Department.Oils
    else -> error("Nieznany dział: $group")
}

val Tank.department: Department get() = departmentFor(group)

val EmptyTankColor = Color(0xFF267347)

@Composable
fun LaboratoryBadge(measuredAt: Long, modifier: Modifier = Modifier) {
    Surface(modifier.semantics { contentDescription = "Pomiar z laboratorium: ${dateLabel(measuredAt)}" },
        color = EmptyTankColor.copy(alpha = 0.1f), contentColor = EmptyTankColor, shape = RoundedCornerShape(6.dp)) {
        Row(Modifier.padding(horizontal = 5.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(Icons.Outlined.Science, null, Modifier.size(12.dp))
            Text("LAB", fontSize = 9.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

val MilkywayColors = lightColorScheme(
    primary = Color(0xFF186B60), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDF3EC), onPrimaryContainer = Color(0xFF124A40),
    secondary = Color(0xFF506761), secondaryContainer = Color(0xFFEDF4F1),
    onSecondaryContainer = Color(0xFF304A42),
    background = Color(0xFFF4F7F8), onBackground = Color(0xFF1B2D35),
    surface = Color.White, onSurface = Color(0xFF1B2D35),
    surfaceVariant = Color(0xFFEBF0F2), onSurfaceVariant = Color(0xFF52646D),
    surfaceContainer = Color(0xFFEDF2F3), surfaceContainerLow = Color.White,
    surfaceContainerHigh = Color(0xFFE7EDEF), surfaceContainerHighest = Color(0xFFE0E7E9),
    outline = Color(0xFF77888F), outlineVariant = Color(0xFFDCE5E8)
)

@Composable
fun DepartmentBadge(department: Department) {
    Surface(color = department.tint, contentColor = department.accent, shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(6.dp).background(department.accent, CircleShape))
            Text(department.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}
