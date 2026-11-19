package com.example.expensetracker

import android.app.Activity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import org.json.JSONObject

/** "Sign in with Google" → Google ID token → ShakeTrack session token from the sheet. */
object GoogleAuth {

    /** Returns null if the user closed the account picker. Throws on any other failure. */
    suspend fun signIn(activity: Activity): JSONObject? {
        val option = GetSignInWithGoogleOption.Builder(Prefs.webClientId(activity)).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        val result = try {
            CredentialManager.create(activity).getCredential(activity, request)
        } catch (_: GetCredentialCancellationException) {
            return null
        }

        val cred = result.credential
        if (cred !is CustomCredential || cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw IllegalStateException("Unexpected sign-in result")
        }
        val idToken = GoogleIdTokenCredential.createFrom(cred.data).idToken

        val j = Api.call(activity, "login", JSONObject().put("idToken", idToken))
        Prefs.saveSession(activity, j.getString("token"), j.getJSONObject("me"))
        Prefs.saveLists(activity, j)
        return j
    }

    suspend fun signOut(activity: Activity) {
        try { Api.call(activity, "logout") } catch (_: Exception) {}
        Prefs.clearSession(activity)
        try {
            CredentialManager.create(activity).clearCredentialState(ClearCredentialStateRequest())
        } catch (_: Exception) {}
    }
}
