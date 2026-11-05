# Android Automotive OS & Pleos Connect Proguard Rules
-keep class com.hyundai.rentalhelper.data.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class android.car.** { *; }

