# Sideload signing key

`sideload.keystore` signs the APKs this project builds, so every build has
the same signature and a newer APK installs over an older one without
wiping your bookmarks, sessions or downloaded videos.

This key is **not secret**: its passwords are in `app/build.gradle.kts`.
It is only meant for installing the app on your own devices. Before you
publish the app anywhere, generate your own private key and keep it out of
the repository:

```sh
keytool -genkeypair -keystore my-release.keystore -alias release \
  -keyalg RSA -keysize 4096 -validity 10000
```

Note that the downloaded-video vault is encrypted with a key held in the
phone's Android Keystore, not with this signing key.
