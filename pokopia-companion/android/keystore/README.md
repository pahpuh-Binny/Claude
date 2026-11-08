# Sideload signing key

`sideload.keystore` signs the APKs this project builds, so every build
has the same signature and a newer APK installs over an older one
without wiping your saved progress.

This key is **not secret**: its passwords are in `app/build.gradle.kts`.
It is only meant for installing the app on your own devices. If you ever
publish the app to a store, generate your own private key and keep it out
of the repository.
