package pl.apargb.milkyway

/** Capacity is expressed in litres; null means the user has not supplied it yet. */
data class Tank(val id: String, val name: String, val description: String, val capacityLitres: Long?, val group: String)

object AppContent {
    // Catalog from Zbiorniki.xlsx; live stock and measurements are persisted separately.
    val tanks: List<Tank> = listOf(
        Tank("LBT 1", "LBT 1", "Mleko odtłuszczone", 100000L, "Odbieralnia — LBT / LAT"),
        Tank("LBT 2", "LBT 2", "Mleko odtłuszczone", 100000L, "Odbieralnia — LBT / LAT"),
        Tank("LBT 3", "LBT 3", "Mleko odtłuszczone", 100000L, "Odbieralnia — LBT / LAT"),
        Tank("LAT 1", "LAT 1", "Mleko pełne", 100000L, "Odbieralnia — LBT / LAT"),
        Tank("LAT 2", "LAT 2", "Śmietana", 100000L, "Odbieralnia — LBT / LAT"),
        Tank("LAT 3", "LAT 3", "Mleko pełne", 70000L, "Odbieralnia — LBT / LAT"),
        Tank("LAT 4", "LAT 4", "Serwatka / uniwersalny", 70000L, "Odbieralnia — LBT / LAT"),
        Tank("LAT 5", "LAT 5", "Maślanka z wirowania", 60000L, "Odbieralnia — LBT / LAT"),
        Tank("LAT 6", "LAT 6", "Maślanka z wirowania", 40000L, "Odbieralnia — LBT / LAT"),
        Tank("O-Tank 6", "O-Tank 6", "Śmietana", 15000L, "Odbieralnia — śmietana"),
        Tank("O-Tank 7", "O-Tank 7", "Śmietana", 15000L, "Odbieralnia — śmietana"),
        Tank("Olej 12", "Olej 12", "Olej", 10000L, "Oleje"),
        Tank("Olej 13", "Olej 13", "Olej", 20000L, "Oleje"),
        Tank("Olej 14", "Olej 14", "Olej", 20000L, "Oleje"),
        Tank("Olej 15", "Olej 15", "Olej", 10000L, "Oleje"),
        Tank("Olej 16", "Olej 16", "Olej", 60000L, "Oleje"),
        Tank("Olej 17", "Olej 17", "Olej", 60000L, "Oleje"),
        Tank("MBT 1", "MBT 1", "UHT / przygotowanie miksów", null, "Aparatownia"),
        Tank("MBT 2", "MBT 2", "UHT / przygotowanie miksów", null, "Aparatownia"),
        Tank("MBT 3", "MBT 3", "UHT / przygotowanie miksów", null, "Aparatownia"),
        Tank("LFT 1", "LFT 1", "Maślanka do wirowania", 10000L, "Aparatownia"),
        Tank("LFT 2", "LFT 2", "Maślanka do wirowania", 10000L, "Aparatownia"),
        Tank("M-Tank 1", "M-Tank 1", "Masłownia", 10000L, "Masłownia"),
        Tank("M-Tank 2", "M-Tank 2", "Masłownia", 10000L, "Masłownia"),
        Tank("M-Tank 3", "M-Tank 3", "Masłownia", 10000L, "Masłownia"),
        Tank("M-Tank 4", "M-Tank 4", "Masłownia", 10000L, "Masłownia"),
        Tank("M-Tank 5", "M-Tank 5", "Wyłączony", null, "Masłownia"),
        Tank("M-Tank 6", "M-Tank 6", "Masłownia", 2000L, "Masłownia"),
        Tank("LDT 1", "LDT 1", "Masłownia", 20000L, "Masłownia"),
        Tank("LDT 2", "LDT 2", "Masłownia", 20000L, "Masłownia"),
        Tank("Silos 1", "Silos 1", "Podgęszczona", 60000L, "Proszkownia — silosy"),
        Tank("Silos 2", "Silos 2", "Proszkownia", 60000L, "Proszkownia — silosy"),
        Tank("Silos 3", "Silos 3", "Proszkownia", 60000L, "Proszkownia — silosy"),
        Tank("Silos 4", "Silos 4", "Proszkownia", 100000L, "Proszkownia — silosy"),
        Tank("Silos 6", "Silos 6", "Rzadka (6 Bx)", 150000L, "Proszkownia — silosy"),
        Tank("Silos 7", "Silos 7", "Proszkownia", 150000L, "Proszkownia — silosy"),
        Tank("Silos 8", "Silos 8", "Podgęszczona (19)", 20000L, "Proszkownia — silosy"),
        Tank("Silos 9", "Silos 9", "Proszkownia", 100000L, "Proszkownia — silosy"),
        Tank("Silos 10", "Silos 10", "Proszkownia", 100000L, "Proszkownia — silosy"),
        Tank("Silos 5", "Silos 5", "Woda", 40000L, "Proszkownia — silosy"),
        Tank("Kryst. 1", "Kryst. 1", "Proszkownia", 15000L, "Proszkownia — krystalizatory"),
        Tank("Kryst. 2", "Kryst. 2", "Proszkownia", 15000L, "Proszkownia — krystalizatory"),
        Tank("Kryst. 3", "Kryst. 3", "Proszkownia", 10000L, "Proszkownia — krystalizatory"),
        Tank("Kryst. 4", "Kryst. 4", "Proszkownia", 10000L, "Proszkownia — krystalizatory"),
        Tank("Kryst. 5", "Kryst. 5", "Proszkownia", 10000L, "Proszkownia — krystalizatory"),
        Tank("Kryst. 6", "Kryst. 6", "Proszkownia", 10000L, "Proszkownia — krystalizatory"),
    )
    val softlabDescription: String? = null
    val reminders: List<String> = emptyList()
}

fun capacityLabel(litres: Long?): String = litres?.let {
    java.text.NumberFormat.getIntegerInstance(java.util.Locale.forLanguageTag("pl-PL"))
        .format(it) + " l"
} ?: "Do uzupełnienia"
