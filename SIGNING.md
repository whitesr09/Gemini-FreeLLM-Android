# Permanent Android signing

This project keeps the application ID \`com.nshd.geminifreellm\` unchanged so future releases remain eligible to update the same installed app.

The release keystore is intentionally **not stored in this public repository**. GitHub Actions reads these four repository secrets:

- \`ANDROID_SIGNING_KEYSTORE_B64\` — complete Base64 content of the private JKS file.
- \`ANDROID_SIGNING_STORE_PASSWORD\` — JKS store password.
- \`ANDROID_SIGNING_KEY_ALIAS\` — signing key alias.
- \`ANDROID_SIGNING_KEY_PASSWORD\` — signing key password.

The workflow creates the keystore only inside the temporary GitHub runner and signs the release APK. Keep an independent offline backup of the original JKS and its passwords. Never commit the keystore or these passwords.

## One-time migration

The currently installed debug build was created with a different, non-persistent GitHub Actions debug certificate. It cannot be updated by a newly generated release key. Before installing the first permanent release, export any user data that must survive an uninstall and keep the current APK as a fallback. After the permanent release is installed, future releases use the same signing key and can be installed as normal updates.

See the Android signing documentation for the requirement to protect the signing key and use the same key for self-managed updates.
