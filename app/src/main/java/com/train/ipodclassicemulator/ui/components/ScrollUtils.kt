package com.train.ipodclassicemulator.ui.components

import androidx.compose.foundation.lazy.LazyListState

/**
 * Scrolla la lista SOLO se l'elemento [selectedIndex] è uscito dall'area
 * attualmente visibile — non ad ogni cambio di selezione.
 *
 * - Se l'elemento è sopra la vista -> lo porta in cima (scroll verso l'alto)
 * - Se l'elemento è sotto la vista -> lo porta in fondo (scroll verso il basso)
 * - Se è già visibile -> non fa nulla
 *
 * Questo replica il comportamento delle liste sull'iPod Classic originale,
 * dove la lista scorre "a pagina" solo quando serve, in modo identico
 * scendendo o salendo.
 */
suspend fun LazyListState.scrollToKeepSelectedVisible(selectedIndex: Int) {
    val info = layoutInfo
    val allVisible = info.visibleItemsInfo
    if (allVisible.isEmpty()) {
        animateScrollToItem(selectedIndex)
        return
    }

    // Consideriamo "visibili" solo gli elementi NON tagliati dai bordi dello
    // schermo: altrimenti l'ultima riga può restare mostrata a metà perché
    // veniva già contata come "in vista" pur essendo tagliata.
    val viewportStart = info.viewportStartOffset
    val viewportEnd = info.viewportEndOffset
    val fullyVisible = allVisible.filter { item ->
        item.offset >= viewportStart && (item.offset + item.size) <= viewportEnd
    }

    if (fullyVisible.isEmpty()) {
        // Nessun elemento del tutto visibile (es. lista più piccola dello schermo):
        // forziamo comunque l'allineamento.
        animateScrollToItem(selectedIndex)
        return
    }

    val firstVisible = fullyVisible.first().index
    val lastVisible = fullyVisible.last().index

    when {
        selectedIndex < firstVisible -> {
            // Uscito sopra: porta l'elemento in cima alla vista
            animateScrollToItem(selectedIndex)
        }
        selectedIndex > lastVisible -> {
            // Uscito sotto (o tagliato): porta l'elemento interamente in fondo alla vista
            val visibleCount = fullyVisible.size
            val targetFirstIndex = (selectedIndex - visibleCount + 1).coerceAtLeast(0)
            animateScrollToItem(targetFirstIndex)
        }
        // Altrimenti è già interamente visibile: nessuno scroll necessario
    }
}