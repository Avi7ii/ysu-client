package me.weishu.kernelsu.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

// Host-app adapter only; the vendored KernelSU rendering files remain unchanged.
val LocalHostDarkTheme = compositionLocalOf { false }

@Composable
fun isInDarkTheme(): Boolean = LocalHostDarkTheme.current
