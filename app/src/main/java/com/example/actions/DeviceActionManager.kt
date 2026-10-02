package com.example.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

data class ContactMatch(
    val id: String,
    val name: String,
    val phoneNumber: String
)

data class ActionResult(
    val success: Boolean,
    val action: String,
    val message: String,
    val contacts: List<ContactMatch>? = null
)

/**
 * Handles safe, verified Android device actions requested by Doria/Gemini Live.
 */
class DeviceActionManager(private val context: Context) {

    companion object {
        private const val TAG = "DoriaDeviceActions"

        private val ALLOWED_APPS = mapOf(
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "instagram" to "com.instagram.android",
            "spotify" to "com.spotify.music",
            "chrome" to "com.android.chrome",
            "google chrome" to "com.android.chrome",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "gmail" to "com.google.android.gm",
            "camera" to "com.google.android.GoogleCamera",
            "settings" to "com.android.settings",
            "calculator" to "com.google.android.calculator",
            "calendar" to "com.google.android.calendar"
        )
    }

    /**
     * Executes openWhatsApp action.
     */
    fun openWhatsApp(): ActionResult {
        Log.d(TAG, "Executing openWhatsApp action")
        val pm = context.packageManager

        // Try direct package launch
        val launchIntent = pm.getLaunchIntentForPackage("com.whatsapp")
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            Log.d(TAG, "WhatsApp launched via package manager")
            return ActionResult(true, "openWhatsApp", "WhatsApp opened successfully")
        }

        // Try deep link
        try {
            val deepLinkIntent = Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (deepLinkIntent.resolveActivity(pm) != null) {
                context.startActivity(deepLinkIntent)
                Log.d(TAG, "WhatsApp opened via deep link")
                return ActionResult(true, "openWhatsApp", "WhatsApp opened via deep link")
            }
        } catch (e: Exception) {
            Log.w(TAG, "WhatsApp deep link failed", e)
        }

        // Web fallback
        return try {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://web.whatsapp.com")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
            Log.d(TAG, "WhatsApp opened via web browser")
            ActionResult(true, "openWhatsApp", "WhatsApp Web opened in browser")
        } catch (e: Exception) {
            Log.e(TAG, "Could not open WhatsApp", e)
            ActionResult(false, "openWhatsApp", "WhatsApp is not installed on this device")
        }
    }

    /**
     * Executes openApp action with allowlist security validation.
     */
    fun openApp(appName: String): ActionResult {
        val cleanName = appName.trim().lowercase()
        Log.d(TAG, "Executing openApp for '$appName' (normalized: '$cleanName')")

        if (cleanName.contains("whatsapp")) {
            return openWhatsApp()
        }

        val targetPackage = ALLOWED_APPS[cleanName]
            ?: ALLOWED_APPS.entries.firstOrNull { cleanName.contains(it.key) }?.value

        val pm = context.packageManager

        if (targetPackage != null) {
            val launchIntent = pm.getLaunchIntentForPackage(targetPackage)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                Log.d(TAG, "App '$appName' ($targetPackage) launched successfully")
                return ActionResult(true, "openApp", "$appName opened successfully")
            }
        }

        // Special handling for system actions
        if (cleanName.contains("camera")) {
            try {
                val camIntent = Intent("android.media.action.STILL_IMAGE_CAMERA").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(camIntent)
                return ActionResult(true, "openApp", "Camera opened")
            } catch (e: Exception) {
                Log.w(TAG, "Direct camera launch failed", e)
            }
        } else if (cleanName.contains("setting")) {
            try {
                val setIntent = Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(setIntent)
                return ActionResult(true, "openApp", "Settings opened")
            } catch (e: Exception) {
                Log.w(TAG, "Settings launch failed", e)
            }
        }

        Log.w(TAG, "App '$appName' could not be opened or is not supported")
        return ActionResult(false, "openApp", "Could not open $appName. The app may not be installed or supported.")
    }

    /**
     * Validates URL and opens with browser.
     */
    fun openUrl(url: String): ActionResult {
        Log.d(TAG, "Executing openUrl: $url")
        var validUrl = url.trim()
        if (!validUrl.startsWith("http://") && !validUrl.startsWith("https://")) {
            validUrl = "https://$validUrl"
        }

        return try {
            val parsedUri = Uri.parse(validUrl)
            val scheme = parsedUri.scheme
            if (scheme != "http" && scheme != "https") {
                return ActionResult(false, "openUrl", "Invalid or unsafe URL scheme: $scheme")
            }

            val intent = Intent(Intent.ACTION_VIEW, parsedUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d(TAG, "Opened URL: $validUrl")
            ActionResult(true, "openUrl", "Opened $validUrl")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open URL $url", e)
            ActionResult(false, "openUrl", "Failed to open web link: ${e.message}")
        }
    }

    /**
     * Safely initiates phone call via system dialer.
     */
    fun makeCall(phoneNumber: String): ActionResult {
        val cleanNumber = phoneNumber.replace(Regex("[^0-9+*#]"), "")
        Log.d(TAG, "Executing makeCall to: $cleanNumber")

        if (cleanNumber.isBlank()) {
            return ActionResult(false, "makeCall", "Invalid phone number provided")
        }

        return try {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanNumber")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialIntent)
            Log.d(TAG, "Phone dialer launched with: $cleanNumber")
            ActionResult(true, "makeCall", "Opening dialer for $cleanNumber")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate call to $phoneNumber", e)
            ActionResult(false, "makeCall", "Could not start call: ${e.message}")
        }
    }

    /**
     * Searches device contacts for contactName.
     */
    fun callContact(contactName: String): ActionResult {
        Log.d(TAG, "Executing callContact for: $contactName")

        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_CONTACTS permission not granted")
            return ActionResult(
                false,
                "callContact",
                "Contact permission is required to search contacts. Please grant Contacts permission in the app."
            )
        }

        val matches = searchContacts(contactName)
        Log.d(TAG, "Found ${matches.size} contacts matching '$contactName'")

        return when {
            matches.isEmpty() -> {
                ActionResult(false, "callContact", "No contact found matching '$contactName'.")
            }
            matches.size == 1 -> {
                val contact = matches[0]
                makeCall(contact.phoneNumber)
                ActionResult(
                    true,
                    "callContact",
                    "Calling ${contact.name} (${contact.phoneNumber})",
                    matches
                )
            }
            else -> {
                ActionResult(
                    false,
                    "callContact",
                    "Multiple contacts found for '$contactName': ${matches.joinToString { it.name }}. Which one would you like to call?",
                    matches
                )
            }
        }
    }

    private fun searchContacts(query: String): List<ContactMatch> {
        val result = mutableListOf<ContactMatch>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone._ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$query%")

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC LIMIT 5"
            )
            cursor?.let {
                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val idIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone._ID)

                while (it.moveToNext()) {
                    val id = if (idIndex >= 0) it.getString(idIndex) else ""
                    val name = if (nameIndex >= 0) it.getString(nameIndex) else "Unknown"
                    val number = if (numberIndex >= 0) it.getString(numberIndex) else ""
                    if (number.isNotBlank()) {
                        result.add(ContactMatch(id, name, number))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying contacts", e)
        } finally {
            cursor?.close()
        }

        return result
    }
}
