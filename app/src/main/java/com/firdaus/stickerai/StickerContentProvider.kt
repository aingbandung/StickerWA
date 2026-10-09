package com.firdaus.stickerai

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * ContentProvider sesuai spesifikasi paket stiker pihak ketiga WhatsApp.
 * WhatsApp membaca:
 *  - content://<authority>/metadata            -> info paket
 *  - content://<authority>/metadata/<id>       -> info satu paket
 *  - content://<authority>/stickers/<id>       -> daftar stiker + emoji
 *  - content://<authority>/stickers_asset/<id>/<file> -> file gambar (WebP / PNG tray)
 */
class StickerContentProvider : ContentProvider() {

    private lateinit var matcher: UriMatcher
    private var authority: String = ""

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        authority = StickerPackStore.authority(ctx)
        matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(authority, "metadata", CODE_METADATA)
            addURI(authority, "metadata/*", CODE_METADATA_SINGLE)
            addURI(authority, "stickers/*", CODE_STICKERS)
            addURI(authority, "stickers_asset/*/*", CODE_ASSET)
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        val ctx = context ?: return null
        return when (matcher.match(uri)) {
            CODE_METADATA -> metadataCursor(ctx, uri)
            CODE_METADATA_SINGLE -> {
                if (uri.lastPathSegment == StickerPackStore.IDENTIFIER) metadataCursor(ctx, uri)
                else emptyMetadataCursor(uri)
            }
            CODE_STICKERS -> {
                if (uri.lastPathSegment == StickerPackStore.IDENTIFIER) stickersCursor(ctx, uri)
                else MatrixCursor(STICKER_COLUMNS)
            }
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
    }

    private fun metadataCursor(ctx: Context, uri: Uri): Cursor {
        val cursor = MatrixCursor(METADATA_COLUMNS)
        cursor.newRow()
            .add(StickerPackStore.IDENTIFIER)
            .add(StickerPackStore.PACK_NAME)
            .add(StickerPackStore.PUBLISHER)
            .add(StickerPackStore.TRAY_FILE)
            .add("") // android_play_store_link
            .add("") // ios_app_download_link
            .add("") // publisher email
            .add("") // publisher website
            .add("") // privacy policy website
            .add("") // license agreement website
            .add(StickerPackStore.imageDataVersion(ctx))
            .add(0) // whatsapp_will_not_cache_stickers
            .add(0) // animated_sticker_pack
        cursor.setNotificationUri(ctx.contentResolver, uri)
        return cursor
    }

    private fun emptyMetadataCursor(uri: Uri): Cursor = MatrixCursor(METADATA_COLUMNS)

    private fun stickersCursor(ctx: Context, uri: Uri): Cursor {
        val cursor = MatrixCursor(STICKER_COLUMNS)
        for (f in StickerPackStore.stickerFiles(ctx)) {
            cursor.addRow(arrayOf<Any>(f.name, StickerPackStore.DEFAULT_EMOJI, "Stiker"))
        }
        cursor.setNotificationUri(ctx.contentResolver, uri)
        return cursor
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        val ctx = context ?: return null
        if (matcher.match(uri) != CODE_ASSET) return null
        val segments = uri.pathSegments
        if (segments.size < 3) return null
        val fileName = segments[segments.size - 1]
        val identifier = segments[segments.size - 2]
        if (identifier != StickerPackStore.IDENTIFIER) {
            throw FileNotFoundException("Unknown pack: $identifier")
        }
        // cegah path traversal
        if (fileName != File(fileName).name) {
            throw FileNotFoundException("Invalid file name")
        }
        val file = File(StickerPackStore.dir(ctx), fileName)
        if (!file.exists()) throw FileNotFoundException("File not found: $fileName")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, file.length())
    }

    override fun getType(uri: Uri): String? {
        return when (matcher.match(uri)) {
            CODE_METADATA -> "vnd.android.cursor.dir/vnd.$authority.metadata"
            CODE_METADATA_SINGLE -> "vnd.android.cursor.item/vnd.$authority.metadata"
            CODE_STICKERS -> "vnd.android.cursor.dir/vnd.$authority.stickers"
            CODE_ASSET ->
                if (uri.lastPathSegment?.endsWith(".png") == true) "image/png" else "image/webp"
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Not supported")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int =
        throw UnsupportedOperationException("Not supported")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = throw UnsupportedOperationException("Not supported")

    companion object {
        private const val CODE_METADATA = 1
        private const val CODE_METADATA_SINGLE = 2
        private const val CODE_STICKERS = 3
        private const val CODE_ASSET = 4

        private val METADATA_COLUMNS = arrayOf(
            "sticker_pack_identifier",
            "sticker_pack_name",
            "sticker_pack_publisher",
            "sticker_pack_icon",
            "android_play_store_link",
            "ios_app_download_link",
            "sticker_pack_publisher_email",
            "sticker_pack_publisher_website",
            "sticker_pack_privacy_policy_website",
            "sticker_pack_license_agreement_website",
            "image_data_version",
            "whatsapp_will_not_cache_stickers",
            "animated_sticker_pack"
        )

        private val STICKER_COLUMNS = arrayOf(
            "sticker_file_name",
            "sticker_emoji",
            "sticker_accessibility_text"
        )
    }
}
