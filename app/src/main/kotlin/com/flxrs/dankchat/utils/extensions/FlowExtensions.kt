package com.flxrs.dankchat.utils.extensions

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

inline fun <T> Flow<T>.observe(viewLifecycleOwner: LifecycleOwner, crossinline collector: (value: T) -> Unit) {
    viewLifecycleOwner.lifecycleScope.launchWhenResumed {
        collect {
            collector(it)
        }
    }
}