package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================================================
// "Quiet Momentum" palette — warm paper + sage green.
//
// Rule above all: **color = information**. Every hue means something (due / overdue / high-yield /
// memory strength / rating / success). No decorative color. Rating colors form a warm→cool ramp
// (effort→ease); "Forgot" is warm sienna, NOT alarm-red (red drives avoidance and makes users lie
// to the scheduler, corrupting FSRS data).
//
// The legacy names (RedPrimary/RedLight/RedDark/Teal/Mint/Amber/HighYieldOrange/…) are kept as
// ALIASES pointing at their new roles so existing screens keep compiling during the migration.
// ============================================================================================

// ---------------- LIGHT ----------------
// Surfaces — warm paper, never pure white
val PaperSurface        = Color(0xFFFBF9F4)
val PaperContainerLow   = Color(0xFFF4F1EA)
val PaperContainer      = Color(0xFFEFEBE2)
val PaperContainerHigh  = Color(0xFFE8E4D9)
val PaperCard           = Color(0xFFFFFFFF)
val OutlineSoft         = Color(0xFFD8D3C4) // 1dp borders only, never text
val OutlineStrong       = Color(0xFFA9A393)

// Text
val InkPrimary          = Color(0xFF211F1A)
val InkVariant          = Color(0xFF5C594E)
val InkFaint            = Color(0xFF8A8677) // decorative/timestamps only, >= 12sp

// Brand — sage (also = "Good")
val Sage                = Color(0xFF4E7A5A)
val SageContainer       = Color(0xFFDCEEDD)
val OnSageContainer     = Color(0xFF1B3322)
val SageDeep            = Color(0xFF35573F)

// Semantic — memory ratings (warm→cool = effort→ease)
val Forgot              = Color(0xFFB3452E) // burnt sienna (pressed/solid)
val ForgotContainer     = Color(0xFFF9E3DC)
val OnForgotContainer   = Color(0xFF8C3220)
val Hard                = Color(0xFFC07C22) // ochre
val HardContainer       = Color(0xFFF7EAD3)
val OnHardContainer     = Color(0xFF8A5A10)
val Good                = Sage              // "Good" is the brand color
val GoodContainer       = SageContainer
val OnGoodContainer     = SageDeep
val Easy                = Color(0xFF33689B) // steel blue (cool = effortless)
val EasyContainer       = Color(0xFFDFEAF5)
val OnEasyContainer     = Color(0xFF2A5580)

// Semantic — status
val HighYield           = Color(0xFFB3641F) // amber-brown (pre-attentive against sage/neutral)
val HighYieldContainer  = Color(0xFFF8E9D7)
val OnHighYield         = Color(0xFF7A4310)
val Overdue             = Color(0xFFA85B32) // warm & recoverable, never error-red
val OverdueContainer    = Color(0xFFF4E3D6)
val Strong              = Color(0xFF3E7D5C) // "Strong" mastery
val DangerRed           = Color(0xFFB3261E) // destructive actions ONLY (delete)

// Memory-strength gradient (new → strong): "seed → forest"
val Strength0 = Color(0xFFC9BFA9)
val Strength1 = Color(0xFFB7C9A0)
val Strength2 = Color(0xFF8FBB8C)
val Strength3 = Color(0xFF5E9A6B)
val Strength4 = Color(0xFF35734B)
val StrengthGradient = listOf(Strength0, Strength1, Strength2, Strength3, Strength4)

// ---------------- DARK ("paper at night") ----------------
val PaperSurfaceDark        = Color(0xFF171612) // warm near-black, not #000
val PaperContainerLowDark   = Color(0xFF1E1D18)
val PaperContainerDark      = Color(0xFF26241E)
val PaperCardDark           = Color(0xFF211F1A)
val OutlineDark             = Color(0xFF3A382F)
val InkPrimaryDark          = Color(0xFFECE8DC) // warm off-white, avoids halation
val InkVariantDark          = Color(0xFFABA795)
val SageDark                = Color(0xFF8FBF9A) // desaturated sage for dark
val OnSageDark              = Color(0xFF14301D)
val SageContainerDark       = Color(0xFF2C4634)
val OnSageContainerDark     = Color(0xFFC9E5CE)
val ForgotDark              = Color(0xFFD98D77)
val ForgotContainerDark     = Color(0xFF3C2620)
val OnForgotContainerDark   = Color(0xFFD98D77)
val HardDark                = Color(0xFFDBA55C)
val HardContainerDark       = Color(0xFF3A2E1A)
val OnHardContainerDark     = Color(0xFFDBA55C)
val GoodDark                = SageDark
val GoodContainerDark       = Color(0xFF243528)
val EasyDark                = Color(0xFF86ADD1)
val EasyContainerDark       = Color(0xFF22303E)
val OnEasyContainerDark     = Color(0xFF86ADD1)
val HighYieldTone           = Color(0xFFE8B877)
val HighYieldContainerDark  = Color(0xFF3A2E1D)
val DangerRedDark           = Color(0xFFF2B8B5)
val OverdueDark             = Color(0xFFD59A72) // lighter terracotta, legible on dark paper
val OverdueContainerDark    = Color(0xFF3A2A1E)
val StrengthDark0 = Color(0xFF4A463C)
val StrengthDark1 = Color(0xFF4E5C44)
val StrengthDark2 = Color(0xFF4E7A5A)
val StrengthDark3 = Color(0xFF6BA47C)
val StrengthDark4 = Color(0xFF8FBF9A)
val StrengthGradientDark = listOf(StrengthDark0, StrengthDark1, StrengthDark2, StrengthDark3, StrengthDark4)

// ---------------- LEGACY ALIASES (keep existing screens compiling during migration) ----------------
val BackgroundOffWhite = PaperSurface
val SurfaceSoftLight   = PaperContainer
val RedPrimary         = Sage
val RedLight           = SageContainer
val RedDark            = SageDeep
val Teal               = Strong
val TealContainer      = SageContainer
val Mint               = Strong
val Amber              = HighYield
val AmberContainer     = HighYieldContainer
val HighYieldOrange    = HighYield
val WeakAmber          = Overdue
val ErrorRed           = DangerRed
val TextDark           = InkPrimary
val TextMuted          = InkVariant
val BorderLight        = OutlineSoft

val BackgroundDark     = PaperSurfaceDark
val SurfaceDark        = PaperCardDark
val SurfaceSoftDark    = PaperContainerDark
val PrimaryDark        = SageDark
val TealDark           = StrengthDark4
val MintDark           = StrengthDark4
val AmberDark          = HighYieldTone
val HighYieldDark      = HighYieldTone
val ErrorDark          = DangerRedDark
val TextLight          = InkPrimaryDark
val TextMutedDark      = InkVariantDark
val BorderDark         = OutlineDark
