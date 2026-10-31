package com.tonix.wifireader

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

object Tiles {
    /** Satellite imagery (works in the AR minimap, so use it everywhere) */
    fun satellite(): OnlineTileSourceBase = object : OnlineTileSourceBase("TonixSat", 0, 19, 256, ".jpg",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")) {
        override fun getTileURLString(i: Long): String =
            getBaseUrl() + MapTileIndex.getZoom(i) + "/" + MapTileIndex.getY(i) + "/" + MapTileIndex.getX(i)
    }
}
