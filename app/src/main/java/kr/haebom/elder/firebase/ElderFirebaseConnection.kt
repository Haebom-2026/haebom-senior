package kr.haebom.elder.firebase

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source

internal object ElderFirebaseConnection {
    const val PROJECT_ID = "copilot-demo-c9a74"

    sealed interface Result {
        data object MissingConfiguration : Result
        data object DifferentProject : Result
        data object NonAnonymousAccount : Result
        data class AuthFailed(val code: String) : Result
        data class FirestoreFailed(val code: String) : Result
        data class Connected(val elderId: String, val profileExists: Boolean) : Result
    }

    fun verify(context: Context, result: (Result) -> Unit) {
        val app = FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
        if (app == null) {
            result(Result.MissingConfiguration)
            return
        }
        if (app.options.projectId != PROJECT_ID) {
            result(Result.DifferentProject)
            return
        }
        verify(FirebaseAuth.getInstance(app), FirebaseFirestore.getInstance(app), result)
    }

    internal fun verify(
        auth: FirebaseAuth,
        fireStore: FirebaseFirestore,
        result: (Result) -> Unit,
    ) {
        val user = auth.currentUser
        when {
            user == null -> auth.signInAnonymously().addOnCompleteListener { task ->
                val signedIn = if (task.isSuccessful) task.result?.user else null
                if (signedIn?.isAnonymous == true) {
                    verifyProfileRead(fireStore, signedIn.uid, result)
                } else {
                    val code = (task.exception as? FirebaseAuthException)?.errorCode ?: "SIGN_IN_FAILED"
                    result(Result.AuthFailed(code))
                }
            }
            !user.isAnonymous -> result(Result.NonAnonymousAccount)
            else -> verifyProfileRead(fireStore, user.uid, result)
        }
    }

    private fun verifyProfileRead(
        fireStore: FirebaseFirestore,
        elderId: String,
        result: (Result) -> Unit,
    ) {
        fireStore.collection("users").document(elderId).get(Source.SERVER).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                result(Result.Connected(elderId, task.result.exists()))
            } else {
                val code = (task.exception as? FirebaseFirestoreException)?.code?.name ?: "READ_FAILED"
                result(Result.FirestoreFailed(code))
            }
        }
    }
}
