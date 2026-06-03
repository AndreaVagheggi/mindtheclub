package com.bolimot.mindtheclub.functions

import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import com.bolimot.mindtheclub.dataModels.ImagesItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun createImageItems(cursor: Cursor): List<ImagesItem> {
    val dateFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    val itemsByMonth = LinkedHashMap<String, MutableList<Uri>>()

    while (cursor.moveToNext()) {
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
        val dateAddedSeconds = cursor.getLong(dateAddedColumn)
        val dateAdded = Date(dateAddedSeconds * 1000)
        val dateString = dateFormat.format(dateAdded)
        val imageUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idColumn))

        itemsByMonth.getOrPut(dateString) { mutableListOf() }.add(imageUri)
    }
    cursor.close()

    val galleryItems = mutableListOf<ImagesItem>()
    itemsByMonth.forEach { (month, uris) ->
        galleryItems.add(ImagesItem.DateHeader(month))
        uris.forEach { uri ->
            galleryItems.add(ImagesItem.ImageItem(uri))
        }
    }

    return galleryItems
}

fun createVideoItems(cursor: Cursor): List<ImagesItem> {
    val dateFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    val itemsByMonth = LinkedHashMap<String, MutableList<Uri>>()

    while (cursor.moveToNext()) {
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
        val dateAddedSeconds = cursor.getLong(dateAddedColumn)
        val dateAdded = Date(dateAddedSeconds * 1000)
        val dateString = dateFormat.format(dateAdded)
        val imageUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idColumn))

        itemsByMonth.getOrPut(dateString) { mutableListOf() }.add(imageUri)
    }
    cursor.close()

    val galleryItems = mutableListOf<ImagesItem>()
    itemsByMonth.forEach { (month, uris) ->
        galleryItems.add(ImagesItem.DateHeader(month))
        uris.forEach { uri ->
            galleryItems.add(ImagesItem.ImageItem(uri))
        }
    }

    return galleryItems
}