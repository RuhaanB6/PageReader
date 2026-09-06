package com.pagereader.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Only the styles this app actually uses get a deliberate value; everything
 * else falls through to Material's own default scale.
 *
 * `displaySmall` carries the instruction card (`CaptureScreen`) and the stage
 * caption (`ProcessingScreen`) -- both are read at arm's length, often at an
 * angle, by someone who may be low-vision rather than blind, so it is set
 * large and bold rather than at Material's default (36sp, regular weight is
 * legible from a desk but not while holding a phone up to a page).
 *
 * `bodyLarge` carries page text in the text-list view (`PageScreen`'s
 * `BlockRow`, moved over from the old `ReadingScreen`) and is sized for
 * sustained reading rather than a UI label.
 */
val Typography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.3.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.4.sp,
    ),
)
