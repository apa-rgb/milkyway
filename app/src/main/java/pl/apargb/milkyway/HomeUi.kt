package pl.apargb.milkyway

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun HomeOverview(shift: Int?) {
    if (shift == null) {
        HomeJournal()
        return
    }
    val date = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.forLanguageTag("pl-PL")))
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = Color(0xFF172F3A)) {
        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF172F3A), Color(0xFF254C55))))
            .padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("PANEL OPERACYJNY",
                        fontSize = 10.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFB6D9D7))
                    Text("Zmiana $shift",
                        fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text("Zbiorniki, plan i przebieg pracy",
                        fontSize = 12.sp, lineHeight = 16.sp, color = Color(0xFFD6E4E8))
                }
                Surface(Modifier.size(66.dp), shape = CircleShape, color = Color.White.copy(alpha = 0.1f)) {
                    Image(painterResource(R.drawable.cow_art), contentDescription = "Wesoła krówka", modifier = Modifier.padding(8.dp))
                }
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.14f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.CalendarToday, null, Modifier.size(14.dp), tint = Color(0xFFB6D9D7))
                Text(date, fontSize = 12.sp, lineHeight = 16.sp, color = Color(0xFFD6E4E8))
            }
        }
    }
}

@Composable
private fun HomeJournal() {
    val date = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.forLanguageTag("pl-PL")))
    Surface(Modifier.fillMaxWidth().testTag("home-journal"), shape = RoundedCornerShape(18.dp), color = Color(0xFF172F3A)) {
        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF172F3A), Color(0xFF254C55))))
            .padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("MILKYWAY / PRODUKCJA", fontSize = 9.sp, letterSpacing = 1.sp,
                    fontWeight = FontWeight.SemiBold, color = Color(0xFFB6D9D7))
                Surface(shape = RoundedCornerShape(6.dp), color = Color.White.copy(alpha = .1f)) {
                    Text("v${BuildConfig.VERSION_NAME}", Modifier.testTag("home-app-version").padding(horizontal = 7.dp, vertical = 3.dp),
                        fontSize = 10.sp, color = Color(0xFFD6E4E8))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Dziennik produkcji", fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text(date, fontSize = 10.sp, lineHeight = 14.sp, color = Color(0xFFD6E4E8))
                }
                Surface(Modifier.size(40.dp), shape = CircleShape, color = Color.White.copy(alpha = .1f)) {
                    Image(painterResource(R.drawable.cow_art), contentDescription = "Wesoła krówka", modifier = Modifier.padding(5.dp))
                }
            }
        }
    }
}

@Composable
internal fun ShiftSelectionCard(shift: Int, selected: Boolean, modifier: Modifier = Modifier,
                                compact: Boolean = false, onSelect: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Card(onClick = onSelect, modifier = modifier.fillMaxWidth().heightIn(min = if (compact) 44.dp else 56.dp)
        .semantics { this.selected = selected }, shape = RoundedCornerShape(if (compact) 10.dp else 14.dp),
        border = BorderStroke(1.dp, if (selected) accent else MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().heightIn(min = if (compact) 44.dp else 56.dp)
            .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 6.dp else 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
            Icon(when (shift) { 1 -> Icons.Outlined.WbSunny; 2 -> Icons.Outlined.WbTwilight; else -> Icons.Outlined.NightsStay },
                null, Modifier.size(if (compact) 14.dp else 20.dp), tint = accent)
            Text("Zmiana $shift", Modifier.weight(1f), fontSize = if (compact) 12.sp else 14.sp, fontWeight = FontWeight.SemiBold)
            if (selected && !compact) Icon(Icons.Outlined.CheckCircle, "Wybrana zmiana", Modifier.size(16.dp), tint = accent)
        }
    }
}

@Composable
internal fun HomeProductionAction(title: String, caption: String, icon: ImageVector, tone: Department,
                                  modifier: Modifier, compactIcon: Boolean = false, prominent: Boolean = false, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = if (prominent) 160.dp else 76.dp), shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = tone.tint), border = BorderStroke(1.dp, tone.accent.copy(alpha = .2f))) {
        if (prominent) Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(26.dp), tint = tone.accent)
            Text(title, fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = tone.accent, textAlign = TextAlign.Center)
            Text(caption, fontSize = 11.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        } else Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, null, Modifier.size(if (compactIcon) 16.dp else 19.dp), tint = tone.accent)
                Text(if (compactIcon) title.substringBefore("/") + "/" else title, Modifier.weight(1f),
                    fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = tone.accent)
            }
            if (compactIcon) Text(title.substringAfter("/"), fontSize = 12.sp, lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold, color = tone.accent)
            Text(caption, fontSize = 11.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun DashboardTile(title: String, caption: String, icon: ImageVector, tone: Department,
                           modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Surface(color = tone.tint, shape = RoundedCornerShape(11.dp)) {
                    Icon(icon, null, Modifier.padding(9.dp).size(22.dp), tint = tone.accent)
                }
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
                Text(caption, fontSize = 11.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
