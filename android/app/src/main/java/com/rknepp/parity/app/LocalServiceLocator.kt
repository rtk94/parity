package com.rknepp.parity.app

import androidx.compose.runtime.compositionLocalOf
import com.rknepp.parity.ServiceLocator

val LocalServiceLocator = compositionLocalOf<ServiceLocator> {
    error("ServiceLocator not provided. Wrap your Composable in CompositionLocalProvider.")
}
