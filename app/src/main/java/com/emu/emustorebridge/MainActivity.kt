package com.emu.emustorebridge

import android.accounts.Account
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PasswordCredential
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.api.client.auth.openidconnect.IdToken
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom


class MainActivity : AppCompatActivity() {
    private val TAG = "GoogleDriveActivity"
    private val WEB_CLIENT_ID = "198250407664-f9qhah3d2jun6o61odlgug7e11cl4pc0.apps.googleusercontent.com"
    val transport = NetHttpTransport()
    val jsonFactory = GsonFactory.getDefaultInstance()
    private var driveService: Drive? = null
    private lateinit var credentialManager: CredentialManager
    private lateinit var signInLauncher: ActivityResultLauncher<Intent>
    private lateinit var authorizationLauncher: ActivityResultLauncher<Intent>


    fun generateNonce(): String {
        Log.i(TAG, "Generating nonce")
        val byteArray = ByteArray(16) // 128-bit nonce
        SecureRandom().nextBytes(byteArray)
        return byteArray.joinToString("") { "%02x".format(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate called")

        credentialManager = CredentialManager.create(this)
        Log.i(TAG, "Credential manager created")

        signInLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                handleSignInResult(task)
            } else {
                Log.e(TAG, "Sign-in failed with result code ${result.resultCode}")
            }
        }

        authorizationLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                // Retry getting the token after the user has granted permission
                handleAuthorizationResult()
            } else {
                Log.e(TAG, "Authorization failed with result code ${result.resultCode}")
            }
        }

        val googleIdOption: GetGoogleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(WEB_CLIENT_ID)
            .setAutoSelectEnabled(true)
            .setNonce(generateNonce())
            .build()

        Log.i(TAG, "GoogleIdOption create: $googleIdOption")

        val request: GetCredentialRequest = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        Log.i(TAG, "Credential requested with request $request")
        Log.i(TAG, "Using package ${application.packageName} and sha ${getSHA1Fingerprint()}")

        lifecycleScope.launch {
            try {
                val result = credentialManager.getCredential(
                    request = request,
                    context = this@MainActivity,
                )
                Log.i(TAG, "Received getCredential result $result")
                handleSignIn(result)
            } catch (e: GetCredentialException) {
                handleFailure(e)
            }
        }
    }

    fun handleSignIn(result: GetCredentialResponse) {
        // Handle the successfully returned credential.
        val credential = result.credential
        Log.i(TAG, "Signing in with credentials $credential")

        when (credential) {

            // Passkey credential
            is PublicKeyCredential -> {
                Log.i(TAG, "Using PublicKeyCredential")
                // Share responseJson such as a GetCredentialResponse on your server to
                // validate and authenticate
                var responseJson = credential.authenticationResponseJson
            }

            // Password credential
            is PasswordCredential -> {
                Log.i(TAG, "Using PasswordCredential")
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

                        Log.i(TAG, "GoogleIdTokenCredential received for ${googleIdTokenCredential.displayName}");

                        val verifier = GoogleIdTokenVerifier.Builder(
                            transport,
                            jsonFactory
                        )
                            .setAudience(listOf(WEB_CLIENT_ID))
                            .build()
//
                        lifecycleScope.launch(Dispatchers.IO) {

                            val idToken: GoogleIdToken =
                                verifier.verify(googleIdTokenCredential.idToken)
                            if (idToken != null) {
                                val payload: IdToken.Payload = idToken.payload

                                // Print user identifier
                                val userId: String = payload.getSubject()
                                Log.i(TAG, "User ID: $userId")

                                // Get profile information from payload
//                        val email: String = payload.getEmail()
//                        val emailVerified: Boolean =
//                            java.lang.Boolean.valueOf(payload.getEmailVerified())
                                val name = payload.get("name") as? String ?: ""
                                val pictureUrl = payload.get("picture") as? String ?: ""
                                val familyName = payload.get("family_name") as? String ?: ""
                                val givenName = payload.get("given_name") as? String ?: ""

                                Log.i(TAG, "User given name: $givenName")
                                Log.i(TAG, "User name: $name")
                                Log.i(TAG, "User picture URL: $pictureUrl")
                                Log.i(TAG, "User family name: $familyName")
                            } else {
                                Log.e(TAG, "Invalid null id token")
                            }
                        }

                        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                            .requestEmail()
                            .requestScopes(Scope(DriveScopes.DRIVE_READONLY))
                            .build()

                        val googleSignInClient = GoogleSignIn.getClient(this, gso)

                        val signInIntent = googleSignInClient.signInIntent
                        signInLauncher.launch(signInIntent)


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

    private fun handleAuthorizationResult() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val googleAccount = GoogleSignIn.getLastSignedInAccount(this@MainActivity)?.account
                if (googleAccount != null) {
                    val accessToken = GoogleAuthUtil.getToken(
                        this@MainActivity,
                        googleAccount,
                        "oauth2:${DriveScopes.DRIVE_READONLY}"
                    )
                    // Use the access token to access Google Drive
                    Log.i(TAG, "Access Token after authorization: $accessToken")
                } else {
                    Log.e(TAG, "Google Account is null after authorization")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception in handleAuthorizationResult: ${e.message}")
            }
        }
    }

    private fun handleSignInResult(completedTask: Task<GoogleSignInAccount>) {
        try {
            val account = completedTask.getResult(ApiException::class.java)
            // Signed in successfully
            val googleAccount = account.account

            // Get an access token
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val accessToken = googleAccount?.let {
                        GoogleAuthUtil.getToken(
                            this@MainActivity,
                            it,
                            "oauth2:${DriveScopes.DRIVE_READONLY}"
                        )
                    }
                    // Use the access token to access Google Drive
                    Log.i(TAG, "Access Token: $accessToken")
                    if (googleAccount != null) {
                        useDriveApi(googleAccount)
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "IO Exception: ${e.message}")
                } catch (e: UserRecoverableAuthException) {
                    // Prompt the user for authorization
                    e.intent?.let { authorizationLauncher.launch(it) }
                } catch (e: GoogleAuthException) {
                    Log.e(TAG, "Google Auth Exception: ${e.message}")
                }
            }
        } catch (e: ApiException) {
            Log.e(TAG, "Sign-in failed: ${e.statusCode}")
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
        Log.i(TAG, "Listing google drive files")

        lifecycleScope.launch(Dispatchers.IO) {
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
            } catch (e: UserRecoverableAuthIOException) {
                Log.e(TAG, "UserRecoverableAuthIOException: ${e.message}", e)
                val intent = e.intent
                withContext(Dispatchers.Main) {
                    authorizationLauncher.launch(intent)
                    listFiles() // TODO remember to stop the recurse
                }
            }
            catch (e: Exception) {
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

    fun getSHA1Fingerprint(): String? {
        try {
            val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val cert = info.signingInfo.apkContentsSigners[0]
            val md = MessageDigest.getInstance("SHA-1")
            val sha1 = md.digest(cert.toByteArray())
            return sha1.joinToString(":") { "%02X".format(it) }
        } catch (e: Exception) {
            Log.e("AuthDebug", "Unable to get SHA1 fingerprint", e)
        }
        return null
    }

}
