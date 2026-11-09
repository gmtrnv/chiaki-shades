// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Sony sign-in to learn the PSN Account ID, the same steps as scripts/psn-account-id.py.
 *
 * The sign-in page opens in the user's own browser, never inside the app: the app cannot see
 * the password or the Sony session. Sony then sends the browser to [REDIRECT_URI] with a one-time
 * code in the address; the user brings that address back (share or copy), and the code is traded
 * here for a token that is only used to read the account number and is then dropped.
 */
object PsnAuth
{
	// Remote Play Windows client (the script and chiaki use the same one)
	private const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
	private const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"

	const val REDIRECT_URI = "https://remoteplay.dl.playstation.net/remoteplay/redirect"
	private const val TOKEN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"

	val LOGIN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize" +
		"?service_entity=urn:service-entity:psn&response_type=code&client_id=$CLIENT_ID" +
		"&redirect_uri=$REDIRECT_URI&scope=psn:clientapp&request_locale=en_US&ui=pr&service_logo=ps" +
		"&layout_type=popup&smcid=remoteplay&prompt=always&PlatformPrivacyWs1=minimal&"

	private const val TIMEOUT_MS = 15000

	class Account(val accountId: String, val onlineId: String?)

	class AuthException(val reason: Reason, message: String): IOException(message)
	enum class Reason { NETWORK, CODE_REJECTED, BAD_ANSWER }

	/**
	 * The one-time code inside the address Sony ends on, or null if [text] holds none.
	 * The address may come surrounded by other words (some browsers share "title + link").
	 */
	fun extractCode(text: String): String?
	{
		val url = Regex("https://\\S+").findAll(text).map { it.value }
			.firstOrNull { it.contains("remoteplay/redirect") }
			?: return null
		return try
		{
			Uri.parse(url).getQueryParameter("code")?.takeIf { it.isNotBlank() }
		}
		catch(e: Exception) { null }
	}

	/** Looks like the Sony address, without reading the code (to offer "Use this link") */
	fun looksLikeRedirect(text: CharSequence?) = text != null && text.contains("remoteplay/redirect") && text.contains("code=")

	/** Trades the code for the account. Blocks: call it off the main thread. */
	fun fetchAccount(code: String): Account
	{
		val body = "grant_type=authorization_code&code=${URLEncoder.encode(code, "UTF-8")}&redirect_uri=$REDIRECT_URI&"
		val tokenAnswer = request(TOKEN_URL, body)
		val token = try { JSONObject(tokenAnswer).getString("access_token") }
			catch(e: Exception) { throw AuthException(Reason.BAD_ANSWER, "no access_token") }

		val infoAnswer = request(TOKEN_URL + "/" + Uri.encode(token), null)
		val info = try { JSONObject(infoAnswer) } catch(e: Exception) { throw AuthException(Reason.BAD_ANSWER, "info not json") }
		val userId = info.optString("user_id").takeIf { it.isNotEmpty() }
			?: throw AuthException(Reason.BAD_ANSWER, "no user_id")
		val onlineId = info.optString("online_id").takeIf { it.isNotBlank() }
		return Account(accountIdFromUserId(userId), onlineId)
	}

	/**
	 * The user id is an unsigned 64-bit number: read as signed, big ones would overflow.
	 * The console wants its 8 bytes, little-endian, in base64.
	 */
	fun accountIdFromUserId(userId: String): String
	{
		val value = userId.trim().toULongOrNull() ?: throw AuthException(Reason.BAD_ANSWER, "user_id not a number")
		val bytes = ByteArray(8) { i -> (value shr (8 * i)).toByte() }
		return Base64.encodeToString(bytes, Base64.NO_WRAP)
	}

	private fun request(url: String, postBody: String?): String
	{
		val connection = try { URL(url).openConnection() as HttpURLConnection }
			catch(e: IOException) { throw AuthException(Reason.NETWORK, e.toString()) }
		try
		{
			connection.connectTimeout = TIMEOUT_MS
			connection.readTimeout = TIMEOUT_MS
			val basic = Base64.encodeToString("$CLIENT_ID:$CLIENT_SECRET".toByteArray(), Base64.NO_WRAP)
			connection.setRequestProperty("Authorization", "Basic $basic")
			if(postBody != null)
			{
				connection.requestMethod = "POST"
				connection.doOutput = true
				connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
				connection.outputStream.use { it.write(postBody.toByteArray(Charsets.US_ASCII)) }
			}
			val status = connection.responseCode
			if(status != 200)
			{
				// 400 = the code is wrong, used or expired; nothing in the answer is shown or logged
				throw AuthException(if(status in 400..499) Reason.CODE_REJECTED else Reason.NETWORK, "HTTP $status")
			}
			return connection.inputStream.bufferedReader().use { it.readText() }
		}
		catch(e: AuthException) { throw e }
		catch(e: IOException) { throw AuthException(Reason.NETWORK, e.toString()) }
		finally { connection.disconnect() }
	}
}
