package com.homburg.diana_sign

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// Repräsentiert einen Eintrag (ID + Unterschrift)
data class SignItem(
    val id: String,
    val signatureData: String // oder was immer du speicherst
)

class SignViewModel(
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    // Liste der erfassten Einträge
    var items by mutableStateOf(listOf<SignItem>())
        private set

    fun addItem(item: SignItem) {
        items = items + item
    }

    fun removeItem(item: SignItem) {
        items = items - item
    }

    fun clearData() {
        items = emptyList()
    }
}