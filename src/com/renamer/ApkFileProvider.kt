package com.renamer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** 1.73 (worker C): the APK-serving provider for the update
 *  checker. The build has no androidx.core on its classpath
 *  (only an androidx.collection stub jar), so there is no
 *  androidx FileProvider to reuse — this is the same contract,
 *  written small: it serves ONLY files under
 *  filesDir/update/ (the download dir), read-only, to the holder
 *  of a granted URI.
 *
 *  Authority: "<package>.apkprovider" (declared in the manifest
 *  as com.renamer.openvpn.apkprovider). The system installer gets
 *  the URI with FLAG_GRANT_READ_URI_PERMISSION, so the provider
 *  itself stays exported=false.
 *
 *  query() answers OpenableColumns.DISPLAY_NAME + SIZE — the
 *  installer asks for those before it will take the file. */
class ApkFileProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.renamer.openvpn.apkprovider"
        private const val PATH_SEGMENT = "update"

        /** content://com.renamer.openvpn.apkprovider/update/<name>
         *  for a file inside the update dir. Null when the file is
         *  not in that dir (never serve anything else). */
        fun uriFor(ctx: Context, file: File): Uri? {
            return try {
                val dir = File(ctx.filesDir, UpdateCheckerPure.UPDATE_DIR_NAME)
                val canonicalDir = dir.canonicalFile
                val canonicalFile = file.canonicalFile
                if (!canonicalFile.path.startsWith(canonicalDir.path + File.separator)) return null
                Uri.parse("content://$AUTHORITY/$PATH_SEGMENT/${Uri.encode(canonicalFile.name)}")
            } catch (_: Exception) {
                null
            }
        }
    }

    override fun onCreate(): Boolean = true

    /** Only "update/<name>" resolves, and only to a real file
     *  inside filesDir/update/. Anything else is a 404. */
    private fun fileForUri(uri: Uri): File {
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != PATH_SEGMENT) {
            throw FileNotFoundException("bad uri: $uri")
        }
        val name = segments[1]
        if (name.isBlank() || name.contains("/") || name.contains("..")) {
            throw FileNotFoundException("bad name: $name")
        }
        val dir = File(context?.filesDir, UpdateCheckerPure.UPDATE_DIR_NAME)
        val file = File(dir, name).canonicalFile
        if (!file.path.startsWith(dir.canonicalFile.path + File.separator)) {
            throw FileNotFoundException("outside update dir")
        }
        if (!file.isFile) throw FileNotFoundException("no such file: $name")
        return file
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw FileNotFoundException("read-only provider")
        val file = fileForUri(uri)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val file = try {
            fileForUri(uri)
        } catch (_: Exception) {
            return null
        }
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols)
        val values = arrayOfNulls<Any>(cols.size)
        for (i in cols.indices) {
            when (cols[i]) {
                OpenableColumns.DISPLAY_NAME -> values[i] = file.name
                OpenableColumns.SIZE -> values[i] = file.length()
            }
        }
        cursor.addRow(values)
        return cursor
    }

    override fun getType(uri: Uri): String? = UpdateCheckerPure.APK_MIME_TYPE

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
