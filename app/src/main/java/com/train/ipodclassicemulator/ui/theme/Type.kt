package com.train.ipodclassicemulator.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.train.ipodclassicemulator.R

// 🟢 Caricamento locale ed efficiente del font (Addio errori di certificato!)
val MontserratFontFamily = FontFamily(
    Font(resId = R.font.montserrat_regular, weight = FontWeight.Normal),
    Font(resId = R.font.montserrat_regular, weight = FontWeight.Medium),
    Font(resId = R.font.montserrat_regular, weight = FontWeight.Bold)
)

val IpodTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = MontserratFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp
    ),
    titleMedium = TextStyle(
        fontFamily = MontserratFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp
    ),
    labelSmall = TextStyle(
        fontFamily = MontserratFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp
    )
)