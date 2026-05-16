package com.chhanda.ai.data.sync

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoogleDriveRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private fun getDriveService(account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(DriveScopes.DRIVE_FILE, DriveScopes.DRIVE_APPDATA)
        )
        credential.selectedAccount = account.account
        
        return Drive.Builder(
            AndroidHttp.newCompatibleTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("Chhanda AI")
            .build()
    }

    suspend fun uploadFile(account: GoogleSignInAccount, fileName: String, content: ByteArray, mimeType: String = "application/json"): String? = withContext(Dispatchers.IO) {
        try {
            val service = getDriveService(account)
            val fileMetadata = File().apply {
                name = fileName
                parents = listOf("appDataFolder") // Use appDataFolder for private storage
            }
            
            val mediaContent = com.google.api.client.http.InputStreamContent(mimeType, ByteArrayInputStream(content))
            
            // Check if file exists to update or create
            val existingFile = service.files().list()
                .setSpaces("appDataFolder")
                .setQ("name = '$fileName'")
                .execute()
                .files.firstOrNull()

            if (existingFile != null) {
                service.files().update(existingFile.id, null, mediaContent).execute().id
            } else {
                service.files().create(fileMetadata, mediaContent).execute().id
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun downloadFile(account: GoogleSignInAccount, fileName: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val service = getDriveService(account)
            val existingFile = service.files().list()
                .setSpaces("appDataFolder")
                .setQ("name = '$fileName'")
                .execute()
                .files.firstOrNull() ?: return@withContext null

            val outputStream = ByteArrayOutputStream()
            service.files().get(existingFile.id).executeMediaAndDownloadTo(outputStream)
            outputStream.toByteArray()
        } catch (e: Exception) {
            null
        }
    }
}
