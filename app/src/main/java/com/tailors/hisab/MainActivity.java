package com.tailors.hisab;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int RC_SIGN_IN = 701;
    private WebView webView;
    private FirebaseAuth auth;
    private FirebaseFirestore db;
    private GoogleSignInClient googleClient;
    private boolean cloudReady = false;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(com.tailors.hisab.R.string.default_web_client_id))
            .requestEmail().build();
        googleClient = GoogleSignIn.getClient(this, gso);
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        webView.addJavascriptInterface(new AppBridge(), "TailorApp");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
        if (auth.getCurrentUser() != null) {
            webView.postDelayed(() -> loadCloud(), 700);
        }
    }

    private void signIn() {
        cloudReady = false;
        startActivityForResult(googleClient.getSignInIntent(), RC_SIGN_IN);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_SIGN_IN) {
            try {
                com.google.android.gms.auth.api.signin.GoogleSignInAccount account =
                    GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException.class);
                AuthCredential credential = GoogleAuthProvider.getCredential(account.getIdToken(), null);
                auth.signInWithCredential(credential).addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) loadCloud();
                    else showError("Google লগইন ব্যর্থ: " + task.getException().getLocalizedMessage());
                });
            } catch (Exception e) {
                showError("Google লগইন হয়নি। Firebase-এ SHA-1 সেট করা আছে কি না দেখুন।");
            }
        }
    }

    private void loadCloud() {
        if (auth.getCurrentUser() == null) return;
        String uid = auth.getCurrentUser().getUid();
        db.collection("users").document(uid).get().addOnSuccessListener((DocumentSnapshot snap) -> {
            cloudReady = true;
            String email = auth.getCurrentUser() == null ? "" : auth.getCurrentUser().getEmail();
            String payload = snap.exists() ? snap.getString("payload") : null;
            String payloadArg = payload == null ? "null" : JSONObject.quote(payload);
            String emailArg = JSONObject.quote(email == null ? "" : email);
            webView.post(() -> webView.evaluateJavascript(
                "window.onFirebaseSession && window.onFirebaseSession(" + emailArg + "," + payloadArg + ")", null));
        }).addOnFailureListener(e -> showError("Cloud ডেটা পড়া যায়নি। Firestore Rules পরীক্ষা করুন।"));
    }

    private void saveCloud(String payload) {
        if (!cloudReady || auth.getCurrentUser() == null) return;
        String uid = auth.getCurrentUser().getUid();
        Map<String, Object> data = new HashMap<>();
        data.put("payload", payload);
        data.put("updatedAt", com.google.firebase.firestore.FieldValue.serverTimestamp());
        db.collection("users").document(uid).set(data)
            .addOnFailureListener(e -> showError("Cloud-এ সেভ হয়নি। Firestore Rules পরীক্ষা করুন।"));
    }

    private void showError(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    private class AppBridge {
        @JavascriptInterface public void signIn() { runOnUiThread(() -> MainActivity.this.signIn()); }
        @JavascriptInterface public void sync(String json) { runOnUiThread(() -> saveCloud(json)); }
        @JavascriptInterface public void signOut() {
            runOnUiThread(() -> {
                cloudReady = false;
                auth.signOut();
                googleClient.signOut();
                webView.post(() -> webView.evaluateJavascript("window.onFirebaseSignedOut && window.onFirebaseSignedOut()", null));
            });
        }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}