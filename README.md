<div align="center">

### **English** | [Русский](README_RU.md)

</div>

# LADB

A local ADB shell for Android!

# About this fork

This is a fork of [tytydraco/LADB](https://github.com/tytydraco/LADB). The app works the same way; the changes are
listed below.

- The license check and the Google Play Services dependency are removed, along with unused permissions
- Russian translation alongside the original English, including the shell output
- The language can be switched in the app, and follows the system per-app language on Android 13 and up
- Material 3 interface, with dynamic colors on Android 12 and up and a themed icon on Android 13 and up
- Targets Android 17, including the local network permission that port discovery now requires
- Deprecated platform APIs replaced with current ones, with the same behavior down to Android 8
- More reliable first connection: discovery waits for the port, and the connection is verified and retried
- The pairing is named LADB in the wireless debugging settings
- Logging is stripped from release builds
- Signed builds for every ABI, plus a universal one, are produced by GitHub Actions with the key from this repository

# How does it work?

LADB bundles an ADB server within the app libraries. Normally, this server cannot connect to the local device because it
requires an active USB connection. However, Android's Wireless ADB Debugging feature allows the server and the client to
speak to each other locally.

# Initial Setup

Use split-screen more or a pop-out window with LADB and Settings at the same time. This is because Android will
invalidate the pairing information if the dialog gets dismissed. Add a Wireless Debugging connection, and copy the
pairing code and port into LADB. Keep both windows open until the Settings dialog dismisses itself.

# Issues

LADB is sadly incompatible with Shizuku at the current moment. That means that if you have Shiuzuku installed, LADB will
usually fail to connect properly. You must uninstall it and reboot to use LADB.

# Troubleshooting

Most errors can be fixed by clearing the app data for LADB, removing all Wireless Debugging connections from Settings,
and rebooting.

# License

The license is mostly permissive other than it does not allow unofficial builds to be released to the Google Play Store.

# Privacy Policy

LADB does not send any device data outside the app. Your data is not collected or processed.
