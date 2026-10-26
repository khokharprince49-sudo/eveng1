# Build G1 Text Bridge online with GitHub Actions

This repository includes `.github/workflows/build-apk.yml`. GitHub Actions runs the Android build on a cloud Linux runner, so you do not need to install Android Studio, the Android SDK, the NDK, or Buildozer on your own machine.

## Upload the project

1. Sign in to GitHub and create a new repository (private is fine).
2. Upload the **contents** of this `G1TextBridge` folder to the repository root. Make sure the hidden `.github/workflows/build-apk.yml` file is included. Alternatively, push the extracted folder with Git/GitHub Desktop.
3. Commit the files to the `main` branch.

## Start/retrieve the build

1. Open the repository on GitHub and select **Actions**.
2. Select **Build G1 Text Bridge APK** and click **Run workflow** (or let it run after the initial push).
3. Open the running workflow. If it fails, open the failed step and inspect its log.
4. When it succeeds, open the run's **Artifacts** section and download `G1-Text-Bridge-debug-apk`.
5. Extract the ZIP artifact; the `.apk` file is inside.

The workflow uses GitHub-hosted runners. Build time depends on dependency downloads and runner availability. The APK is a debug build, not a Play Store release.

## Important limitations

A completed build proves only that Android packaging succeeded. G1 Bluetooth connectivity and text rendering have not been verified against physical Even Realities G1 glasses. Test with your own device before relying on it. The app is an unofficial prototype.
