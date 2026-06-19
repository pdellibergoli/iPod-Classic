package com.train.ipodclassicemulator.data.repository

data class TrackModel(
    val id: String,
    val title: String,
    val artist: String,
    val isSpotify: Boolean,
    val uri: String
)