package com.emu.emustorebridge

import android.accounts.Account
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PasswordCredential
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.GetCredentialException
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.emu.emustorebridge.databinding.FragmentGoogleDriveBinding
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
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
import java.security.SecureRandom

class GoogleDriveFragment : Fragment() {
    private var _binding: FragmentGoogleDriveBinding? = null
    private val binding get() = _binding!!
    private lateinit var googleSignInClient: GoogleSignInClient
    private val GOOGLE_SIGN_IN_REQUEST_CODE = 100

    private val TAG = "GoogleDriveActivity"
    private val WEB_CLIENT_ID =
        "198250407664-f9qhah3d2jun6o61odlgug7e11cl4pc0.apps.googleusercontent.com"
    val transport = NetHttpTransport()
    val jsonFactory = GsonFactory.getDefaultInstance()
    private var driveService: Drive? = null
    private lateinit var credentialManager: CredentialManager
    private lateinit var signInLauncher: ActivityResultLauncher<Intent>
    private lateinit var authorizationLauncher: ActivityResultLauncher<Intent>

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGoogleDriveBinding.inflate(inflater, container, false)

        credentialManager = CredentialManager.create(requireContext())
        Log.i(TAG, "Credential manager created")

        signInLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                val googleAccount = handleSignInResult(task)
                if (googleAccount != null) {
                    useDriveApi(googleAccount)
                    listFiles()
                }
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

        val googleIdOption: GetGoogleIdOption = createGoogleIdOption();

        val request: GetCredentialRequest = createCredentialRequest(googleIdOption)

//        lifecycleScope.launch {
//            try {
//                val result = credentialManager.getCredential(
//                    request = request,
//                    context = requireContext(),
//                )
//                Log.i(TAG, "Received getCredential result $result")
//                authenticateGoogleUser(result)
//                authorizeGoogleDrive()
//
//            } catch (e: GetCredentialException) {
//                handleFailure(e)
//                Log.i(TAG, "Prompting google user signin")
//                promptGoogleSignIn()
//            }
//        }

        return binding.root

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(requireActivity(), gso)

        binding.btnSignIn.setOnClickListener {
            val signInIntent = googleSignInClient.signInIntent
            startActivityForResult(signInIntent, GOOGLE_SIGN_IN_REQUEST_CODE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == GOOGLE_SIGN_IN_REQUEST_CODE) {
            val account: GoogleSignInAccount? = GoogleSignIn.getSignedInAccountFromIntent(data).result
            account?.let {
                binding.tvSignInStatus.text = "Signed in as: ${it.displayName}"
            }
        }
    }

    fun generateNonce(): String {
        Log.i(TAG, "Generating nonce")
        val byteArray = ByteArray(16) // 128-bit nonce
        SecureRandom().nextBytes(byteArray)
        return byteArray.joinToString("") { "%02x".format(it) }
    }

    private fun promptGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .build()

        val googleSignInClient = GoogleSignIn.getClient(requireContext(), gso)
        val signInIntent = googleSignInClient.signInIntent
        signInLauncher.launch(signInIntent)
    }

    fun authenticateGoogleUser(result: GetCredentialResponse) {
        Log.i(TAG, "Authenticating google user")
        val credential = result.credential
        Log.i(TAG, "Signing in with credentials $credential")

        when (credential) {

            // Passkey credential
            is PublicKeyCredential -> {
                handlePublicKeyCredential(credential)
            }

            // Password credential
            is PasswordCredential -> {
                handlePasswordCredential(credential)
            }

            // GoogleIdToken credential
            is CustomCredential -> {
                handleCustomCredential(credential)
            }
        }
    }

    private fun authorizeGoogleDrive() {
        Log.i(TAG, "Authorizing google drive")
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_READONLY))
            .build()

        val googleSignInClient = GoogleSignIn.getClient(requireContext(), gso)

        val signInIntent = googleSignInClient.signInIntent
        signInLauncher.launch(signInIntent)
    }

    private fun createGoogleIdOption(): GetGoogleIdOption {
        val googleIdOption: GetGoogleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(WEB_CLIENT_ID)
            .setAutoSelectEnabled(true)
            .setNonce(generateNonce())
            .build()

        Log.i(TAG, "GoogleIdOption create: $googleIdOption")

        return googleIdOption;
    }

    private fun createCredentialRequest(googleIdOption: GetGoogleIdOption): GetCredentialRequest {
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        Log.i(TAG, "Credential request created $request")

        return request;
    }

    private fun handlePublicKeyCredential(credential: PublicKeyCredential) {
        Log.i(TAG, "Using PublicKeyCredential")
        var responseJson = credential.authenticationResponseJson
        // TODO
    }

    private fun handlePasswordCredential(credential: PasswordCredential) {
        Log.i(TAG, "Using PasswordCredential")
        val username = credential.id
        val password = credential.password
        // TODO
    }

    private fun handleCustomCredential(credential: CustomCredential) {
        if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            try {
                val googleIdTokenCredential = GoogleIdTokenCredential
                    .createFrom(credential.data)

                Log.i(
                    TAG,
                    "GoogleIdTokenCredential received for ${googleIdTokenCredential.displayName}"
                );

                verifyToken(googleIdTokenCredential)
            } catch (e: GoogleIdTokenParsingException) {
                Log.e(TAG, "Received an invalid google id token response", e)
            }
        } else {
            Log.e(TAG, "Unexpected type of credential")
        }
    }

    private fun verifyToken(googleIdTokenCredential: GoogleIdTokenCredential) {
        val verifier = GoogleIdTokenVerifier.Builder(
            transport,
            jsonFactory
        )
            .setAudience(listOf(WEB_CLIENT_ID))
            .build()

        lifecycleScope.launch(Dispatchers.IO) {
            val idToken: GoogleIdToken? =
                verifier.verify(googleIdTokenCredential.idToken)
            if (idToken != null) {
                val payload: IdToken.Payload = idToken.payload

                val userId: String = payload.getSubject()
                val name = payload.get("name") as? String ?: ""
                Log.i(TAG, "User ID: $userId\nName: $name")
            } else {
                Log.e(TAG, "Invalid null id token")
            }
        }
    }

    private fun handleAuthorizationResult() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val googleAccount = GoogleSignIn.getLastSignedInAccount(requireContext())?.account
                if (googleAccount != null) {
                    val accessToken = GoogleAuthUtil.getToken(
                        requireContext(),
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

    private fun handleSignInResult(completedTask: Task<GoogleSignInAccount>): Account? {
        try {
            val account = completedTask.getResult(ApiException::class.java)
            // Signed in successfully
            val googleAccount = account.account

            // Get an access token
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val accessToken = googleAccount?.let {
                        GoogleAuthUtil.getToken(
                            requireContext(),
                            it,
                            "oauth2:${DriveScopes.DRIVE_READONLY}"
                        )
                    }
                    // Use the access token to access Google Drive
                    Log.i(TAG, "Access Token: $accessToken")
                } catch (e: IOException) {
                    Log.e(TAG, "IO Exception: ${e.message}")
                } catch (e: UserRecoverableAuthException) {
                    // Prompt the user for authorization
                    e.intent?.let { authorizationLauncher.launch(it) } // TODO fix infinite loop
                } catch (e: GoogleAuthException) {
                    Log.e(TAG, "Google Auth Exception: ${e.message}")
                }
            }
            return googleAccount
        } catch (e: ApiException) {
            Log.e(TAG, "Sign-in failed: ${e.statusCode}")
        }
        return null
    }


    private fun useDriveApi(account: Account) {
        Log.i(TAG, "Using google drive API")
        val credential: GoogleAccountCredential = GoogleAccountCredential.usingOAuth2(
            requireContext(), listOf(DriveScopes.DRIVE_FILE)
        )
        credential.selectedAccount = account

        driveService = Drive.Builder(
            NetHttpTransport(),
            GsonFactory(),
            credential
        )
            .setApplicationName("Your Application Name")
            .build()
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
                }
                // TODO fix infinite loop
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
