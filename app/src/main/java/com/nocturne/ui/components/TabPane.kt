package com.nocturne.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A tab's scrolling body. Portrait = single column; landscape = the
 * prototype's 2-column `pane-cols` masonry (cards flow down col 1 then col 2,
 * `full` items span both columns).
 */
class TabItem(
    val full: Boolean = false,
    val content: @Composable () -> Unit,
)

@Composable
fun TabPane(
    landscape: Boolean,
    items: List<TabItem>,
    modifier: Modifier = Modifier,
) {
    if (landscape) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(11.2.dp),
        ) {
            items.forEach { item ->
                if (item.full) {
                    item(span = { GridItemSpan(maxLineSpan) }) { item.content() }
                } else {
                    item { item.content() }
                }
            }
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.8.dp, vertical = 11.2.dp),
            verticalArrangement = Arrangement.spacedBy(11.2.dp),
        ) {
            items.forEach { it.content() }
        }
    }
}
