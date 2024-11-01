package com.emu.emustorebridge

import android.accounts.Account
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PasswordCredential
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.launch
import java.security.SecureRandom

class MainActivity : AppCompatActivity() {
    private val TAG = "GoogleDriveActivity"
    private var driveService: Drive? = null
    private lateinit var credentialManager: CredentialManager

    fun generateNonce(): String {
        val byteArray = ByteArray(16) // 128-bit nonce
        SecureRandom().nextBytes(byteArray)
        return byteArray.joinToString("") { "%02x".format(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Credential Manager
        credentialManager = CredentialManager.create(this)

        val googleIdOption: GetGoogleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId("YOUR_SERVER_CLIENT_ID")
            .setAutoSelectEnabled(true)
            .setNonce(generateNonce())
            .build()

        val request: GetCredentialRequest = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        lifecycleScope.launch {
            try {
                val result = credentialManager.getCredential(
                    request = request,
                    context = this@MainActivity,
                )
                handleSignIn(result)
            } catch (e: GetCredentialException) {
                handleFailure(e)
            }
        }
    }

    fun handleSignIn(result: GetCredentialResponse) {
        // Handle the successfully returned credential.
        val credential = result.credential

        when (credential) {

            // Passkey credential
            is PublicKeyCredential -> {
                // Share responseJson such as a GetCredentialResponse on your server to
                // validate and authenticate
                var responseJson = credential.authenticationResponseJson
            }

            // Password credential
            is PasswordCredential -> {
                // Send ID and password to your server to validate and authenticate.
                val username = credential.id
                val password = credential.password
            }

            // GoogleIdToken credential
            is CustomCredential -> {
                if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    try {
                        // Use googleIdTokenCredential and extract the ID to validate and
                        // authenticate on your server.
                        val googleIdTokenCredential = GoogleIdTokenCredential
                            .createFrom(credential.data)
                        // You can use the members of googleIdTokenCredential directly for UX
                        // purposes, but don't use them to store or control access to user
                        // data. For that you first need to validate the token:
                        // pass googleIdTokenCredential.getIdToken() to the backend server.
//                        GoogleIdTokenVerifier verifier = ... // see validation instructions
//                        GoogleIdToken idToken = verifier.verify(idTokenString);
                        // To get a stable account identifier (e.g. for storing user data),
                        // use the subject ID:
//                        idToken.getPayload().getSubject()
                    } catch (e: GoogleIdTokenParsingException) {
                        Log.e(TAG, "Received an invalid google id token response", e)
                    }
                } else {
                    // Catch any unrecognized custom credential type here.
                    Log.e(TAG, "Unexpected type of credential")
                }
            }

            else -> {
                // Catch any unrecognized credential type here.
                Log.e(TAG, "Unexpected type of credential")
            }
        }
    }

    private fun useDriveApi(account: Account) {
        val credential: GoogleAccountCredential = GoogleAccountCredential.usingOAuth2(
            applicationContext, listOf(DriveScopes.DRIVE_FILE)
        )
        credential.selectedAccount = account

        driveService = Drive.Builder(
            NetHttpTransport(),
            GsonFactory(),
            credential
        )
            .setApplicationName("Your Application Name")
            .build()

        listFiles()
    }

    private fun listFiles() {
        lifecycleScope.launch {
            try {
                val result = driveService?.files()?.list()
                    ?.setPageSize(10)
                    ?.setFields("nextPageToken, files(id, name)")
                    ?.execute()

                val files = result?.files
                if (files != null && files.isNotEmpty()) {
                    for (file in files) {
                        Log.d(TAG, "File ID: ${file.id}, Name: ${file.name}")
                    }
                } else {
                    Log.d(TAG, "No files found.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error retrieving files", e)
            }
        }
    }

    private fun handleFailure(e: Exception) {
        if (e is GetCredentialException) {
            Log.e(TAG, "Credential Error: ${e.message}")
            // Handle credential-specific exceptions
        } else {
            Log.e(TAG, "Unknown Error: ${e.message}")
            // Handle other exceptions
        }
    }
}
