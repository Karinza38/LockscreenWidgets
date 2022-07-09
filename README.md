# Building

Lockscreen Widgets makes use of some hidden APIs. Since reflection gets tedious, I use a modified android.jar that exposes the hidden APIs so they can be used like normal.
If you want to build Lockscreen Widgets for yourself, make sure you use the android.jar from the following repo: https://github.com/Reginer/aosp-android-jar.
Lockscreen Widgets is currently built against API 32. Download the `android-32` JAR from the above repo and copy it to `YOUR-SDK-LOCATION/platforms/android-32/android.jar`. Make sure to back the original version up in case something goes wrong.