// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include <jni.h>

#include <android/log.h>

#include <chiaki/common.h>
#include <chiaki/log.h>
#include <chiaki/session.h>
#include <chiaki/discoveryservice.h>
#include <chiaki/regist.h>
#include <chiaki/gkcrypt.h>

#include <string.h>
#include <stdlib.h>
#include <pthread.h>
#include <sys/prctl.h>
#include <linux/in.h>
#include <linux/in6.h>
#include <arpa/inet.h>

#include "video-decoder.h"
#include "audio-decoder.h"
#include "audio-output.h"
#include "log.h"
#include "chiaki-jni.h"

static char *strdup_jni(const char *str)
{
	if(!str)
		return NULL;
	char *r = strdup(str);
	if(!r)
		return NULL;
	for(char *c=r; *c; c++)
	{
		if(*c & (1 << 7))
			*c = '?';
	}
	return r;
}

jobject jnistr_from_ascii(JNIEnv *env, const char *str)
{
	if(!str)
		return NULL;
	char *s = strdup_jni(str);
	if(!s)
		return NULL;
	jobject r = E->NewStringUTF(env, s);
	free(s);
	return r;
}

static jbyteArray jnibytearray_create(JNIEnv *env, const uint8_t *buf, size_t buf_size)
{
	jbyteArray r = E->NewByteArray(env, buf_size);
	E->SetByteArrayRegion(env, r, 0, buf_size, (const jbyte *)buf);
	return r;
}

static jobject get_kotlin_global_object(JNIEnv *env, const char *id)
{
	size_t idlen = strlen(id);
	char *sig = malloc(idlen + 3);
	if(!sig)
		return NULL;
	sig[0] = 'L';
	memcpy(sig + 1, id, idlen);
	sig[1 + idlen] = ';';
	sig[1 + idlen + 1] = '\0';
	jclass cls = E->FindClass(env, id);
	jfieldID field_id = E->GetStaticFieldID(env, cls, "INSTANCE", sig);
	jobject r = E->GetStaticObjectField(env, cls, field_id);
	free(sig);
	return r;
}

static ChiakiLog global_log;
JavaVM *global_vm;

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved)
{
	global_vm = vm;

	android_chiaki_file_log_init(&global_log, CHIAKI_LOG_ALL & ~CHIAKI_LOG_VERBOSE, NULL);
	CHIAKI_LOGI(&global_log, "Loading Chiaki Library");
	ChiakiErrorCode err = chiaki_lib_init();
	CHIAKI_LOGI(&global_log, "Chiaki Library Init Result: %s\n", chiaki_error_string(err));
	// once per process, before any stream: the CPU's AES/GMAC are only used if they match mbedtls
	bool crypto_hw = chiaki_gkcrypt_hw_selftest();
	CHIAKI_LOGI(&global_log, "Crypto: %s", crypto_hw ? "AES/GMAC by hardware (self-test OK)" : "AES/GMAC by software");
	return JNI_VERSION;
}

static pthread_key_t jni_detach_key;
static pthread_once_t jni_detach_once = PTHREAD_ONCE_INIT;

static void jni_detach_at_thread_exit(void *value)
{
	(void)value;
	(*global_vm)->DetachCurrentThread(global_vm);
}

static void jni_detach_key_create()
{
	pthread_key_create(&jni_detach_key, jni_detach_at_thread_exit);
}

/**
 * A native thread joins Java once, keeping its own name, and leaves when it ends. The fork joined and
 * left again for every event (a new Java thread object each time, many per second with rumble) and
 * joined without a name, so Android renamed the thread "Thread-N" with a new N every time: one of the
 * busiest threads in the CPU figures could not be told apart.
 */
JNIEnv *attach_thread_jni()
{
	JNIEnv *env;
	int r = (*global_vm)->GetEnv(global_vm, (void **)&env, JNI_VERSION);
	if(r == JNI_OK)
		return env;

	char name[16] = { 0 };
	prctl(PR_GET_NAME, (unsigned long)name, 0, 0, 0);
	JavaVMAttachArgs args = { JNI_VERSION, name[0] ? name : NULL, NULL };
	if((*global_vm)->AttachCurrentThread(global_vm, &env, &args) == 0)
	{
		pthread_once(&jni_detach_once, jni_detach_key_create);
		pthread_setspecific(jni_detach_key, (void *)1);
		return env;
	}

	CHIAKI_LOGE(&global_log, "Failed to get JNIEnv from JavaVM or attach");
	return NULL;
}

JNIEXPORT jstring JNICALL JNI_FCN(errorCodeToString)(JNIEnv *env, jobject obj, jint value)
{
	return E->NewStringUTF(env, chiaki_error_string((ChiakiErrorCode)value));
}

JNIEXPORT jstring JNICALL JNI_FCN(quitReasonToString)(JNIEnv *env, jobject obj, jint value)
{
	return E->NewStringUTF(env, chiaki_quit_reason_string((ChiakiQuitReason)value));
}

JNIEXPORT jboolean JNICALL JNI_FCN(quitReasonIsError)(JNIEnv *env, jobject obj, jint value)
{
	return chiaki_quit_reason_is_error(value);
}

JNIEXPORT jobject JNICALL JNI_FCN(videoProfilePreset)(JNIEnv *env, jobject obj, jint resolution_preset, jint fps_preset, jobject codec)
{
	ChiakiConnectVideoProfile profile = { 0 };
	chiaki_connect_video_profile_preset(&profile, (ChiakiVideoResolutionPreset)resolution_preset, (ChiakiVideoFPSPreset)fps_preset);
	jclass profile_class = E->FindClass(env, BASE_PACKAGE"/ConnectVideoProfile");
	jmethodID profile_ctor = E->GetMethodID(env, profile_class, "<init>", "(IIIIL"BASE_PACKAGE"/Codec;)V");
	return E->NewObject(env, profile_class, profile_ctor, profile.width, profile.height, profile.max_fps, profile.bitrate, codec);
}

typedef struct android_chiaki_session_t
{
	ChiakiSession session;
	ChiakiLog *log;
	jobject java_session;
	jclass java_session_class;
	jmethodID java_session_event_connected_meth;
	jmethodID java_session_event_login_pin_request_meth;
	jmethodID java_session_event_quit_meth;
	jmethodID java_session_event_rumble_meth;
	jfieldID java_controller_state_buttons;
	jfieldID java_controller_state_l2_state;
	jfieldID java_controller_state_r2_state;
	jfieldID java_controller_state_left_x;
	jfieldID java_controller_state_left_y;
	jfieldID java_controller_state_right_x;
	jfieldID java_controller_state_right_y;
	jfieldID java_controller_state_touches;
	jfieldID java_controller_state_gyro_x;
	jfieldID java_controller_state_gyro_y;
	jfieldID java_controller_state_gyro_z;
	jfieldID java_controller_state_accel_x;
	jfieldID java_controller_state_accel_y;
	jfieldID java_controller_state_accel_z;
	jfieldID java_controller_state_orient_x;
	jfieldID java_controller_state_orient_y;
	jfieldID java_controller_state_orient_z;
	jfieldID java_controller_state_orient_w;
	jfieldID java_controller_touch_x;
	jfieldID java_controller_touch_y;
	jfieldID java_controller_touch_id;

	AndroidChiakiVideoDecoder video_decoder;
	AndroidChiakiAudioDecoder audio_decoder;
	void *audio_output;

	// PS5 haptics turned into rumble (see android_chiaki_haptics_frame): the last values sent to Java
	int haptics_left;
	int haptics_right;
	bool haptics_seen;
} AndroidChiakiSession;

/**
 * PS5 games vibrate only a DualSense, with "haptics": a sound for its two motors (frames of 10 ms,
 * 3 kHz stereo 16-bit samples) instead of the rumble messages of PS4 games. With the DualSense
 * mode on (Settings: vibration in PS5 games), each frame's loudness per side becomes a rumble
 * value 0..255, as chiaki-ng does on desktop for controllers without those motors. Only real
 * changes reach Java: a frame comes 100 times a second.
 */
#define HAPTICS_GATE 48

static void android_chiaki_haptics_frame(uint8_t *buf, size_t buf_size, void *user)
{
	AndroidChiakiSession *session = user;
	size_t samples = buf_size / 4;
	if(!session->haptics_seen)
	{
		session->haptics_seen = true;
		CHIAKI_LOGI(session->log, "Haptics: first frame of %zu bytes, turned into rumble", buf_size);
	}
	if(!samples)
		return;
	uint32_t sum_left = 0, sum_right = 0;
	for(size_t i = 0; i < samples; i++)
	{
		int16_t l = (int16_t)(buf[i * 4] | (buf[i * 4 + 1] << 8));
		int16_t r = (int16_t)(buf[i * 4 + 2] | (buf[i * 4 + 3] << 8));
		sum_left += l < 0 ? -(int32_t)l : l;
		sum_right += r < 0 ? -(int32_t)r : r;
	}
	// the average loudness (0..32768) to 0..255; haptics rarely use the whole range, so ×2
	int left = (int)(sum_left / samples / 64);
	int right = (int)(sum_right / samples / 64);
	if(left > 255) left = 255;
	if(right > 255) right = 255;
	// 2.6.5: below this it is the soft background hum of many PS5 games (7–18 %, it can follow the
	// music): not felt on a DualSense, a steady buzz on a phone's motor. A simple motor
	// already dropped it; a motor with strength control played it.
	if(left < HAPTICS_GATE) left = 0;
	if(right < HAPTICS_GATE) right = 0;
	bool stopped = (left == 0 && right == 0) != (session->haptics_left == 0 && session->haptics_right == 0);
	if(!stopped && abs(left - session->haptics_left) < 16 && abs(right - session->haptics_right) < 16)
		return;
	session->haptics_left = left;
	session->haptics_right = right;

	JNIEnv *env = attach_thread_jni();
	if(!env)
		return;
	E->CallVoidMethod(env, session->java_session, session->java_session_event_rumble_meth, (jint)left, (jint)right);
}

static void android_chiaki_event_cb(ChiakiEvent *event, void *user)
{
	AndroidChiakiSession *session = user;

	// only these reach Java: the rest needs no JNI at all
	switch(event->type)
	{
		case CHIAKI_EVENT_CONNECTED:
		case CHIAKI_EVENT_LOGIN_PIN_REQUEST:
		case CHIAKI_EVENT_QUIT:
		case CHIAKI_EVENT_RUMBLE:
			break;
		default:
			return;
	}

	JNIEnv *env = attach_thread_jni();
	if(!env)
		return;

	switch(event->type)
	{
		case CHIAKI_EVENT_CONNECTED:
			E->CallVoidMethod(env, session->java_session,
							  session->java_session_event_connected_meth);
			break;
		case CHIAKI_EVENT_LOGIN_PIN_REQUEST:
			E->CallVoidMethod(env, session->java_session,
							  session->java_session_event_login_pin_request_meth,
							  (jboolean)event->login_pin_request.pin_incorrect);
			break;
		case CHIAKI_EVENT_QUIT:
		{
			char *reason_str = strdup_jni(event->quit.reason_str);
			jstring reason_str_java = reason_str ? E->NewStringUTF(env, reason_str) : NULL;
			E->CallVoidMethod(env, session->java_session,
							  session->java_session_event_quit_meth,
							  (jint)event->quit.reason,
							  reason_str_java);
			if(reason_str_java)
				E->DeleteLocalRef(env, reason_str_java);
			free(reason_str);
			break;
		}
		case CHIAKI_EVENT_RUMBLE:
			E->CallVoidMethod(env, session->java_session,
							  session->java_session_event_rumble_meth,
							  (jint)event->rumble.left,
							  (jint)event->rumble.right);
			break;
		default:
			break;
	}
}

JNIEXPORT void JNICALL JNI_FCN(sessionCreate)(JNIEnv *env, jobject obj, jobject result, jobject connect_info_obj, jstring log_file_str, jboolean log_verbose, jobject java_session)
{
	AndroidChiakiSession *session = NULL;
	ChiakiLog *log = malloc(sizeof(ChiakiLog));
	const char *log_file = log_file_str ? E->GetStringUTFChars(env, log_file_str, NULL) : NULL;
	android_chiaki_file_log_init(log, log_verbose ? CHIAKI_LOG_ALL : (CHIAKI_LOG_ALL & ~CHIAKI_LOG_VERBOSE), log_file);
	if(log_file)
		E->ReleaseStringUTFChars(env, log_file_str, log_file);

	ChiakiErrorCode err = CHIAKI_ERR_SUCCESS;
	char *host_str = NULL;

	jclass result_class = E->GetObjectClass(env, result);

	jclass connect_info_class = E->GetObjectClass(env, connect_info_obj);
	jboolean ps5 = E->GetBooleanField(env, connect_info_obj, E->GetFieldID(env, connect_info_class, "ps5", "Z"));
	jstring host_string = E->GetObjectField(env, connect_info_obj, E->GetFieldID(env, connect_info_class, "host", "Ljava/lang/String;"));
	jbyteArray regist_key_array = E->GetObjectField(env, connect_info_obj, E->GetFieldID(env, connect_info_class, "registKey", "[B"));
	jbyteArray morning_array = E->GetObjectField(env, connect_info_obj, E->GetFieldID(env, connect_info_class, "morning", "[B"));
	jobject connect_video_profile_obj = E->GetObjectField(env, connect_info_obj, E->GetFieldID(env, connect_info_class, "videoProfile", "L"BASE_PACKAGE"/ConnectVideoProfile;"));
	jclass connect_video_profile_class = E->GetObjectClass(env, connect_video_profile_obj);

	ChiakiConnectInfo connect_info = { 0 };
	connect_info.ps5 = ps5;

	const char *str_borrow = E->GetStringUTFChars(env, host_string, NULL);
	connect_info.host = host_str = strdup(str_borrow);
	E->ReleaseStringUTFChars(env, host_string, str_borrow);
	if(!connect_info.host)
	{
		err = CHIAKI_ERR_MEMORY;
		goto beach;
	}

	if(E->GetArrayLength(env, regist_key_array) != sizeof(connect_info.regist_key))
	{
		CHIAKI_LOGE(log, "Regist Key passed from Java has invalid length");
		err = CHIAKI_ERR_INVALID_DATA;
		goto beach;
	}
	jbyte *bytes = E->GetByteArrayElements(env, regist_key_array, NULL);
	memcpy(connect_info.regist_key, bytes, sizeof(connect_info.regist_key));
	E->ReleaseByteArrayElements(env, regist_key_array, bytes, JNI_ABORT);

	if(E->GetArrayLength(env, morning_array) != sizeof(connect_info.morning))
	{
		CHIAKI_LOGE(log, "Morning passed from Java has invalid length");
		err = CHIAKI_ERR_INVALID_DATA;
		goto beach;
	}
	bytes = E->GetByteArrayElements(env, morning_array, NULL);
	memcpy(connect_info.morning, bytes, sizeof(connect_info.morning));
	E->ReleaseByteArrayElements(env, morning_array, bytes, JNI_ABORT);

	connect_info.video_profile.width = (unsigned int)E->GetIntField(env, connect_video_profile_obj, E->GetFieldID(env, connect_video_profile_class, "width", "I"));
	connect_info.video_profile.height = (unsigned int)E->GetIntField(env, connect_video_profile_obj, E->GetFieldID(env, connect_video_profile_class, "height", "I"));
	connect_info.video_profile.max_fps = (unsigned int)E->GetIntField(env, connect_video_profile_obj, E->GetFieldID(env, connect_video_profile_class, "maxFPS", "I"));
	connect_info.video_profile.bitrate = (unsigned int)E->GetIntField(env, connect_video_profile_obj, E->GetFieldID(env, connect_video_profile_class, "bitrate", "I"));

	jobject codec_obj = E->GetObjectField(env, connect_video_profile_obj, E->GetFieldID(env, connect_video_profile_class, "codec", "L"BASE_PACKAGE"/Codec;"));
	jclass codec_class = E->GetObjectClass(env, codec_obj);
	jint target_value = E->GetIntField(env, codec_obj, E->GetFieldID(env, codec_class, "value", "I"));
	connect_info.video_profile.codec = (ChiakiCodec)target_value;

	connect_info.video_profile_auto_downgrade = true;
	// the fork left it at 0: the console was always told "no losses" (see congestioncontrol.c)
	connect_info.packet_loss_max = E->GetDoubleField(env, connect_info_obj, E->GetFieldID(env, connect_info_class, "packetLossMax", "D"));
	connect_info.resolutions_mask = (unsigned int)E->GetIntField(env, connect_info_obj, E->GetFieldID(env, connect_info_class, "resolutionsMask", "I"));
	CHIAKI_LOGI(log, "Report losses up to %.0f %%, other resolutions mask: %#x",
			connect_info.packet_loss_max * 100.0, connect_info.resolutions_mask);
	// a DualSense instead of a DualShock 4 (the fork's): the PS5 then sends its games' haptics (2.6.0-rc4)
	connect_info.enable_dualsense = ps5 && E->GetBooleanField(env, connect_info_obj, E->GetFieldID(env, connect_info_class, "enableDualsense", "Z"));
	CHIAKI_LOGI(log, "Controller: %s", connect_info.enable_dualsense ? "DualSense (haptics as rumble)" : "DualShock 4");

	session = CHIAKI_NEW(AndroidChiakiSession);
	if(!session)
	{
		err = CHIAKI_ERR_MEMORY;
		goto beach;
	}
	memset(session, 0, sizeof(AndroidChiakiSession));
	session->log = log;
	err = android_chiaki_video_decoder_init(&session->video_decoder, log, connect_info.video_profile.width, connect_info.video_profile.height,
			connect_info.ps5 ? connect_info.video_profile.codec : CHIAKI_CODEC_H264);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		free(session);
		session = NULL;
		goto beach;
	}

	err = android_chiaki_audio_decoder_init(&session->audio_decoder, log);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		android_chiaki_video_decoder_fini(&session->video_decoder);
		free(session);
		session = NULL;
		goto beach;
	}

	session->audio_output = android_chiaki_audio_output_new(log);

	android_chiaki_audio_decoder_set_cb(&session->audio_decoder, android_chiaki_audio_output_settings, android_chiaki_audio_output_frame, session->audio_output);

	CHIAKI_LOGI(log, "Crypto: %s", chiaki_gkcrypt_hw_enabled() ? "AES/GMAC by hardware" : "AES/GMAC by software");
	err = chiaki_session_init(&session->session, &connect_info, log);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		CHIAKI_LOGE(log, "JNI ChiakiSession failed to init");
		android_chiaki_video_decoder_fini(&session->video_decoder);
		android_chiaki_audio_decoder_fini(&session->audio_decoder);
		android_chiaki_audio_output_free(session->audio_output);
		free(session);
		session = NULL;
		goto beach;
	}

	session->java_session = E->NewGlobalRef(env, java_session);
	session->java_session_class = E->NewGlobalRef(env, E->GetObjectClass(env, session->java_session));
	session->java_session_event_connected_meth = E->GetMethodID(env, session->java_session_class, "eventConnected", "()V");
	session->java_session_event_login_pin_request_meth = E->GetMethodID(env, session->java_session_class, "eventLoginPinRequest", "(Z)V");
	session->java_session_event_quit_meth = E->GetMethodID(env, session->java_session_class, "eventQuit", "(ILjava/lang/String;)V");
	session->java_session_event_rumble_meth = E->GetMethodID(env, session->java_session_class, "eventRumble", "(II)V");

	jclass controller_state_class = E->FindClass(env, BASE_PACKAGE"/ControllerState");
	session->java_controller_state_buttons = E->GetFieldID(env, controller_state_class, "buttons", "I");
	session->java_controller_state_l2_state = E->GetFieldID(env, controller_state_class, "l2State", "B");
	session->java_controller_state_r2_state = E->GetFieldID(env, controller_state_class, "r2State", "B");
	session->java_controller_state_left_x = E->GetFieldID(env, controller_state_class, "leftX", "S");
	session->java_controller_state_left_y = E->GetFieldID(env, controller_state_class, "leftY", "S");
	session->java_controller_state_right_x = E->GetFieldID(env, controller_state_class, "rightX", "S");
	session->java_controller_state_right_y = E->GetFieldID(env, controller_state_class, "rightY", "S");
	session->java_controller_state_touches = E->GetFieldID(env, controller_state_class, "touches", "[L"BASE_PACKAGE"/ControllerTouch;");
	session->java_controller_state_gyro_x = E->GetFieldID(env, controller_state_class, "gyroX", "F");
	session->java_controller_state_gyro_y = E->GetFieldID(env, controller_state_class, "gyroY", "F");
	session->java_controller_state_gyro_z = E->GetFieldID(env, controller_state_class, "gyroZ", "F");
	session->java_controller_state_accel_x = E->GetFieldID(env, controller_state_class, "accelX", "F");
	session->java_controller_state_accel_y = E->GetFieldID(env, controller_state_class, "accelY", "F");
	session->java_controller_state_accel_z = E->GetFieldID(env, controller_state_class, "accelZ", "F");
	session->java_controller_state_orient_x = E->GetFieldID(env, controller_state_class, "orientX", "F");
	session->java_controller_state_orient_y = E->GetFieldID(env, controller_state_class, "orientY", "F");
	session->java_controller_state_orient_z = E->GetFieldID(env, controller_state_class, "orientZ", "F");
	session->java_controller_state_orient_w = E->GetFieldID(env, controller_state_class, "orientW", "F");

	jclass controller_touch_class = E->FindClass(env, BASE_PACKAGE"/ControllerTouch");
	session->java_controller_touch_x = E->GetFieldID(env, controller_touch_class, "x", "S");
	session->java_controller_touch_y = E->GetFieldID(env, controller_touch_class, "y", "S");
	session->java_controller_touch_id = E->GetFieldID(env, controller_touch_class, "id", "B");

	chiaki_session_set_event_cb(&session->session, android_chiaki_event_cb, session);
	chiaki_session_set_video_sample_cb(&session->session, android_chiaki_video_decoder_video_sample, &session->video_decoder);

	ChiakiAudioSink audio_sink;
	android_chiaki_audio_decoder_get_sink(&session->audio_decoder, &audio_sink);
	chiaki_session_set_audio_sink(&session->session, &audio_sink);

	if(connect_info.enable_dualsense)
	{
		ChiakiAudioSink haptics_sink = { 0 };
		haptics_sink.user = session;
		haptics_sink.frame_cb = android_chiaki_haptics_frame;
		chiaki_session_set_haptics_sink(&session->session, &haptics_sink);
	}

beach:
	if(!session && log)
	{
		android_chiaki_file_log_fini(log);
		free(log);
	}

	free(host_str);
	E->SetIntField(env, result, E->GetFieldID(env, result_class, "errorCode", "I"), (jint)err);
	E->SetLongField(env, result, E->GetFieldID(env, result_class, "ptr", "J"), (jlong)session);
}

JNIEXPORT void JNICALL JNI_FCN(sessionFree)(JNIEnv *env, jobject obj, jlong ptr)
{
	AndroidChiakiSession *session = (AndroidChiakiSession *)ptr;
	if(!session)
		return;
	CHIAKI_LOGI(session->log, "Shutting down JNI Session");
	chiaki_session_fini(&session->session);
	android_chiaki_video_decoder_fini(&session->video_decoder);
	android_chiaki_audio_decoder_fini(&session->audio_decoder);
	android_chiaki_audio_output_free(session->audio_output);
	E->DeleteGlobalRef(env, session->java_session);
	E->DeleteGlobalRef(env, session->java_session_class);
	CHIAKI_LOGI(session->log, "JNI Session has quit");
	android_chiaki_file_log_fini(session->log);
	free(session->log);
	free(session);
}

JNIEXPORT jint JNICALL JNI_FCN(sessionStart)(JNIEnv *env, jobject obj, jlong ptr)
{
	AndroidChiakiSession *session = (AndroidChiakiSession *)ptr;
	CHIAKI_LOGI(session->log, "Start JNI Session");
	return chiaki_session_start(&session->session);
}

JNIEXPORT jint JNICALL JNI_FCN(sessionStop)(JNIEnv *env, jobject obj, jlong ptr)
{
	AndroidChiakiSession *session = (AndroidChiakiSession *)ptr;
	CHIAKI_LOGI(session->log, "Stop JNI Session");
	return chiaki_session_stop(&session->session);
}

// Diagnostic build: a line from the app (stats once a second) into the session log
JNIEXPORT void JNICALL JNI_FCN(sessionLogLine)(JNIEnv *env, jobject obj, jlong ptr, jstring text)
{
	AndroidChiakiSession *session = (AndroidChiakiSession *)ptr;
	if(!session || !text)
		return;
	const char *chars = (*env)->GetStringUTFChars(env, text, NULL);
	if(!chars)
		return;
	CHIAKI_LOGI(session->log, "%s", chars);
	(*env)->ReleaseStringUTFChars(env, text, chars);
}

/**
 * Running totals for the stats overlay, into out (at least 14 longs; the order is read by
 * SessionStats.kt): frames, bytes, frames lost, frames recovered, decoder width, height,
 * packets received, packets lost, packets the system dropped (receive queue full), the console's
 * target bitrate, its round trip time x1000, how many quality messages it sent, the most frames
 * waiting for the decoder at once since the last call (reset here) and the frames that found
 * that queue full.
 */
JNIEXPORT void JNICALL JNI_FCN(sessionGetStats)(JNIEnv *env, jobject obj, jlong ptr, jlongArray out)
{
	AndroidChiakiSession *session = (AndroidChiakiSession *)ptr;
	if(!session || !out || (*env)->GetArrayLength(env, out) < 14)
		return;
	AndroidChiakiVideoDecoder *d = &session->video_decoder;
	ChiakiStreamConnection *sc = &session->session.stream_connection;
	jlong v[14];
	v[0] = (jlong)d->stat_frames;
	v[1] = (jlong)d->stat_bytes;
	v[2] = (jlong)d->stat_lost;
	v[3] = (jlong)d->stat_recovered;
	v[4] = d->out_width;
	v[5] = d->out_height;
	v[6] = (jlong)sc->congestion_control.received_total;
	v[7] = (jlong)sc->congestion_control.lost_total;
	v[8] = (jlong)sc->takion.rxq_drops;
	v[9] = (jlong)(uint32_t)sc->quality_target_bitrate;
	v[10] = (jlong)(sc->quality_rtt * 1000.0);
	v[11] = (jlong)sc->quality_count;
	chiaki_mutex_lock(&d->queue_mutex);
	v[12] = (jlong)d->stat_queue_max;
	d->stat_queue_max = (uint32_t)d->queue_count;
	v[13] = (jlong)d->stat_queue_full;
	chiaki_mutex_unlock(&d->queue_mutex);
	(*env)->SetLongArrayRegion(env, out, 0, 14, v);
}

// Asks the console to go to rest mode; only works while the session is still connected
JNIEXPORT void JNICALL JNI_FCN(sessionSetAudioMuted)(JNIEnv *env, jobject obj, jlong ptr, jboolean muted)
{
	AndroidChiakiSession *session = (AndroidChiakiSession *)ptr;
	android_chiaki_audio_output_set_muted(session->audio_output, muted);
}

JNIEXPORT void JNICALL JNI_FCN(sessionSetAudioVolume)(JNIEnv *env, jobject obj, jlong ptr, jfloat volume)
{
	AndroidChiakiSession *session = (AndroidChiakiSession *)ptr;
	android_chiaki_audio_output_set_volume(session->audio_output, volume);
}

JNIEXPORT jint JNICALL JNI_FCN(sessionGotoBed)(JNIEnv *env, jobject obj, jlong ptr)
{
	AndroidChiakiSession *session = (AndroidChiakiSession *)ptr;
	CHIAKI_LOGI(session->log, "Sending console to rest mode");
	return chiaki_session_goto_bed(&session->session);
}

JNIEXPORT jint JNICALL JNI_FCN(sessionJoin)(JNIEnv *env, jobject obj, jlong ptr)
{
	AndroidChiakiSession *session = (AndroidChiakiSession *)ptr;
	CHIAKI_LOGI(session->log, "Join JNI Session");
	return chiaki_session_join(&session->session);
}

JNIEXPORT void JNICALL JNI_FCN(sessionSetSurface)(JNIEnv *env, jobject obj, jlong ptr, jobject surface)
{
	AndroidChiakiSession *session = (AndroidChiakiSession *)ptr;
	android_chiaki_video_decoder_set_surface(&session->video_decoder, env, surface);
}

JNIEXPORT void JNICALL JNI_FCN(sessionSetControllerState)(JNIEnv *env, jobject obj, jlong ptr, jobject controller_state_java)
{
	AndroidChiakiSession *session = (AndroidChiakiSession *)ptr;
	ChiakiControllerState controller_state;
	chiaki_controller_state_set_idle(&controller_state);
	controller_state.buttons = (uint32_t)E->GetIntField(env, controller_state_java, session->java_controller_state_buttons);
	controller_state.l2_state = (uint8_t)E->GetByteField(env, controller_state_java, session->java_controller_state_l2_state);
	controller_state.r2_state = (uint8_t)E->GetByteField(env, controller_state_java, session->java_controller_state_r2_state);
	controller_state.left_x = (int16_t)E->GetShortField(env, controller_state_java, session->java_controller_state_left_x);
	controller_state.left_y = (int16_t)E->GetShortField(env, controller_state_java, session->java_controller_state_left_y);
	controller_state.right_x = (int16_t)E->GetShortField(env, controller_state_java, session->java_controller_state_right_x);
	controller_state.right_y = (int16_t)E->GetShortField(env, controller_state_java, session->java_controller_state_right_y);
	jobjectArray touch_array = E->GetObjectField(env, controller_state_java, session->java_controller_state_touches);
	size_t touch_array_len = (size_t)E->GetArrayLength(env, touch_array);
	for(size_t i = 0; i < CHIAKI_CONTROLLER_TOUCHES_MAX; i++)
	{
		if(i < touch_array_len)
		{
			jobject touch = E->GetObjectArrayElement(env, touch_array, i);
			controller_state.touches[i].x = (uint16_t)E->GetShortField(env, touch, session->java_controller_touch_x);
			controller_state.touches[i].y = (uint16_t)E->GetShortField(env, touch, session->java_controller_touch_y);
			controller_state.touches[i].id = (int8_t)E->GetByteField(env, touch, session->java_controller_touch_id);
		}
		else
		{
			controller_state.touches[i].x = 0;
			controller_state.touches[i].y = 0;
			controller_state.touches[i].id = -1;
		}
	}
	controller_state.gyro_x = E->GetFloatField(env, controller_state_java, session->java_controller_state_gyro_x);
	controller_state.gyro_y = E->GetFloatField(env, controller_state_java, session->java_controller_state_gyro_y);
	controller_state.gyro_z = E->GetFloatField(env, controller_state_java, session->java_controller_state_gyro_z);
	controller_state.accel_x = E->GetFloatField(env, controller_state_java, session->java_controller_state_accel_x);
	controller_state.accel_y = E->GetFloatField(env, controller_state_java, session->java_controller_state_accel_y);
	controller_state.accel_z = E->GetFloatField(env, controller_state_java, session->java_controller_state_accel_z);
	controller_state.orient_x = E->GetFloatField(env, controller_state_java, session->java_controller_state_orient_x);
	controller_state.orient_y = E->GetFloatField(env, controller_state_java, session->java_controller_state_orient_y);
	controller_state.orient_z = E->GetFloatField(env, controller_state_java, session->java_controller_state_orient_z);
	controller_state.orient_w = E->GetFloatField(env, controller_state_java, session->java_controller_state_orient_w);
	chiaki_session_set_controller_state(&session->session, &controller_state);
}

JNIEXPORT void JNICALL JNI_FCN(sessionSetLoginPin)(JNIEnv *env, jobject obj, jlong ptr, jstring pin_java)
{
	AndroidChiakiSession *session = (AndroidChiakiSession *)ptr;
	const char *pin = E->GetStringUTFChars(env, pin_java, NULL);
	chiaki_session_set_login_pin(&session->session, (const uint8_t *)pin, strlen(pin));
	E->ReleaseStringUTFChars(env, pin_java, pin);
}

typedef struct android_discovery_service_t
{
	ChiakiDiscoveryService service;
	jobject java_service;
	jclass java_service_class;
	jmethodID java_service_hosts_updated_meth;

	jclass host_class;
	jmethodID host_ctor;
	jobject host_state_unknown;
	jobject host_state_ready;
	jobject host_state_standby;
} AndroidDiscoveryService;

static void android_discovery_service_cb(ChiakiDiscoveryHost *hosts, size_t hosts_count, void *user)
{
	AndroidDiscoveryService *service = user;

	CHIAKI_LOGI(&global_log, "JNI Discovery Callback got %llu hosts", (unsigned long long)hosts_count);

	JNIEnv *env = attach_thread_jni();
	if(!env)
		return;
	// the thread stays attached: free this call's local references at the end
	if(E->PushLocalFrame(env, 16 + 8 * (jint)hosts_count) != 0)
		return;

	jobjectArray r = E->NewObjectArray(env, hosts_count, service->host_class, NULL);

	for(size_t i=0; i<hosts_count; i++)
	{
		jobject state;
		ChiakiDiscoveryHost *host = hosts + i;
		switch(host->state)
		{
			case CHIAKI_DISCOVERY_HOST_STATE_STANDBY:
				state = service->host_state_standby;
				break;
			case CHIAKI_DISCOVERY_HOST_STATE_READY:
				state = service->host_state_ready;
				break;
			default:
				state = service->host_state_unknown;
				break;
		}

		jobject o = E->NewObject(env, service->host_class, service->host_ctor,
				state,
				host->host_request_port,
				jnistr_from_ascii(env, host->host_addr),
				jnistr_from_ascii(env, host->system_version),
				jnistr_from_ascii(env, host->device_discovery_protocol_version),
				jnistr_from_ascii(env, host->host_name),
				jnistr_from_ascii(env, host->host_type),
				jnistr_from_ascii(env, host->host_id),
				jnistr_from_ascii(env, host->running_app_titleid),
				jnistr_from_ascii(env, host->running_app_name));

		E->SetObjectArrayElement(env, r, i, o);
	}

	E->CallVoidMethod(env, service->java_service, service->java_service_hosts_updated_meth, r);

	E->PopLocalFrame(env, NULL);
}

static ChiakiErrorCode sockaddr_from_java(JNIEnv *env, jobject /*InetSocketAddress*/ sockaddr_obj, struct sockaddr **addr, size_t *addr_size)
{
	jclass sockaddr_class = E->GetObjectClass(env, sockaddr_obj);
	uint16_t port = (uint16_t)E->CallIntMethod(env, sockaddr_obj, E->GetMethodID(env, sockaddr_class, "getPort", "()I"));
	jobject addr_obj = E->CallObjectMethod(env, sockaddr_obj, E->GetMethodID(env, sockaddr_class, "getAddress", "()Ljava/net/InetAddress;"));
	jclass addr_class = E->GetObjectClass(env, addr_obj);
	jbyteArray addr_byte_array = E->CallObjectMethod(env, addr_obj, E->GetMethodID(env, addr_class, "getAddress", "()[B"));
	jsize addr_byte_array_len = E->GetArrayLength(env, addr_byte_array);

	if(addr_byte_array_len == 4)
	{
		struct sockaddr_in *inaddr = CHIAKI_NEW(struct sockaddr_in);
		if(!inaddr)
			return CHIAKI_ERR_MEMORY;
		memset(inaddr, 0, sizeof(*inaddr));
		inaddr->sin_family = AF_INET;
		jbyte *bytes = E->GetByteArrayElements(env, addr_byte_array, NULL);
		memcpy(&inaddr->sin_addr.s_addr, bytes, sizeof(inaddr->sin_addr.s_addr));
		E->ReleaseByteArrayElements(env, addr_byte_array, bytes, JNI_ABORT);
		inaddr->sin_port = htons(port);

		*addr = (struct sockaddr *)inaddr;
		*addr_size = sizeof(*inaddr);
	}
	else if(addr_byte_array_len == 0x10)
	{
		struct sockaddr_in6 *inaddr6 = CHIAKI_NEW(struct sockaddr_in6);
		if(!inaddr6)
			return CHIAKI_ERR_MEMORY;
		memset(inaddr6, 0, sizeof(*inaddr6));
		inaddr6->sin6_family = AF_INET6;
		jbyte *bytes = E->GetByteArrayElements(env, addr_byte_array, NULL);
		memcpy(&inaddr6->sin6_addr.in6_u, bytes, sizeof(inaddr6->sin6_addr.in6_u));
		E->ReleaseByteArrayElements(env, addr_byte_array, bytes, JNI_ABORT);
		inaddr6->sin6_port = htons(port);

		*addr = (struct sockaddr *)inaddr6;
		*addr_size = sizeof(*inaddr6);
	}
	else
		return CHIAKI_ERR_INVALID_DATA;

	return CHIAKI_ERR_SUCCESS;
}

JNIEXPORT void JNICALL JNI_FCN(discoveryServiceCreate)(JNIEnv *env, jobject obj, jobject result, jobject options_obj, jobject java_service)
{
	jclass result_class = E->GetObjectClass(env, result);
	ChiakiErrorCode err = CHIAKI_ERR_SUCCESS;
	ChiakiDiscoveryServiceOptions options = { 0 };

	AndroidDiscoveryService *service = CHIAKI_NEW(AndroidDiscoveryService);
	if(!service)
	{
		err = CHIAKI_ERR_MEMORY;
		goto beach;
	}

	jclass options_class = E->GetObjectClass(env, options_obj);

	options.hosts_max = (size_t)E->GetLongField(env, options_obj, E->GetFieldID(env, options_class, "hostsMax", "J"));
	options.host_drop_pings = (uint64_t)E->GetLongField(env, options_obj, E->GetFieldID(env, options_class, "hostDropPings", "J"));
	options.ping_ms = (uint64_t)E->GetLongField(env, options_obj, E->GetFieldID(env, options_class, "pingMs", "J"));
	options.cb = android_discovery_service_cb;
	options.cb_user = service;

	err = sockaddr_from_java(env, E->GetObjectField(env, options_obj, E->GetFieldID(env, options_class, "sendAddr", "Ljava/net/InetSocketAddress;")), &options.send_addr, &options.send_addr_size);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		CHIAKI_LOGE(&global_log, "Failed to get sockaddr from InetSocketAddress");
		goto beach;
	}

	service->java_service = E->NewGlobalRef(env, java_service);
	service->java_service_class = E->GetObjectClass(env, service->java_service);
	service->java_service_hosts_updated_meth = E->GetMethodID(env, service->java_service_class, "hostsUpdated", "([L"BASE_PACKAGE"/DiscoveryHost;)V");

	service->host_class = E->NewGlobalRef(env, E->FindClass(env, BASE_PACKAGE"/DiscoveryHost"));
	service->host_ctor = E->GetMethodID(env, service->host_class, "<init>", "("
		"L"BASE_PACKAGE"/DiscoveryHost$State;"
		"S" // hostRequestPort: UShort
		"Ljava/lang/String;" // hostAddr: String?,
		"Ljava/lang/String;" // systemVersion: String?,
		"Ljava/lang/String;" // deviceDiscoveryProtocolVersion: String?,
		"Ljava/lang/String;" // hostName: String?,
		"Ljava/lang/String;" // hostType: String?,
		"Ljava/lang/String;" // hostId: String?,
		"Ljava/lang/String;" // runningAppTitleid: String?,
		"Ljava/lang/String;" // runningAppName: String?
		")V");

	jclass host_state_class = E->FindClass(env, BASE_PACKAGE"/DiscoveryHost$State");
	service->host_state_unknown = E->NewGlobalRef(env, E->GetStaticObjectField(env, host_state_class, E->GetStaticFieldID(env, host_state_class, "UNKNOWN", "L"BASE_PACKAGE"/DiscoveryHost$State;")));
	service->host_state_standby = E->NewGlobalRef(env, E->GetStaticObjectField(env, host_state_class, E->GetStaticFieldID(env, host_state_class, "STANDBY", "L"BASE_PACKAGE"/DiscoveryHost$State;")));
	service->host_state_ready = E->NewGlobalRef(env, E->GetStaticObjectField(env, host_state_class, E->GetStaticFieldID(env, host_state_class, "READY", "L"BASE_PACKAGE"/DiscoveryHost$State;")));


	err = chiaki_discovery_service_init(&service->service, &options, &global_log);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		CHIAKI_LOGE(&global_log, "Failed to create discovery service (JNI)");
		E->DeleteGlobalRef(env, service->java_service);
		E->DeleteGlobalRef(env, service->host_state_unknown);
		E->DeleteGlobalRef(env, service->host_state_standby);
		E->DeleteGlobalRef(env, service->host_state_ready);
		E->DeleteGlobalRef(env, service->host_class);
		free(service);
		goto beach;
	}

beach:
	free(options.send_addr);
	E->SetIntField(env, result, E->GetFieldID(env, result_class, "errorCode", "I"), (jint)err);
	E->SetLongField(env, result, E->GetFieldID(env, result_class, "ptr", "J"), (jlong)service);
}

JNIEXPORT void JNICALL JNI_FCN(discoveryServiceFree)(JNIEnv *env, jobject obj, jlong ptr)
{
	AndroidDiscoveryService *service = (AndroidDiscoveryService *)ptr;
	if(!service)
		return;
	chiaki_discovery_service_fini(&service->service);
	E->DeleteGlobalRef(env, service->java_service);
	E->DeleteGlobalRef(env, service->host_state_unknown);
	E->DeleteGlobalRef(env, service->host_state_standby);
	E->DeleteGlobalRef(env, service->host_state_ready);
	E->DeleteGlobalRef(env, service->host_class);
	free(service);
}

JNIEXPORT jint JNICALL JNI_FCN(discoveryServiceWakeup)(JNIEnv *env, jobject obj, jlong ptr, jstring host_string, jlong user_credential, jboolean ps5)
{
	AndroidDiscoveryService *service = (AndroidDiscoveryService *)ptr;
	const char *host = E->GetStringUTFChars(env, host_string, NULL);
	ChiakiErrorCode r = chiaki_discovery_wakeup(&global_log, service ? &service->service.discovery : NULL, host, (uint64_t)user_credential, ps5);
	E->ReleaseStringUTFChars(env, host_string, host);
	return r;
}


typedef struct android_chiaki_regist_t
{
	AndroidChiakiJNILog log;
	ChiakiRegist regist;

	jobject java_regist;
	jmethodID java_regist_event_meth;

	jclass java_target_class;

	jobject java_regist_event_canceled;
	jobject java_regist_event_failed;
	jclass java_regist_event_success_class;
	jmethodID java_regist_event_success_ctor;

	jclass java_regist_host_class;
	jmethodID java_regist_host_ctor;
} AndroidChiakiRegist;

static jobject create_jni_target(JNIEnv *env, jclass target_class, ChiakiTarget target)
{
	jmethodID meth = E->GetStaticMethodID(env, target_class, "fromValue", "(I)L"BASE_PACKAGE"/Target;");
	return E->CallStaticObjectMethod(env, target_class, meth, (jint)target);
}

static void android_chiaki_regist_cb(ChiakiRegistEvent *event, void *user)
{
	AndroidChiakiRegist *regist = user;

	JNIEnv *env = attach_thread_jni();
	if(!env)
		return;
	// the thread stays attached: free this call's local references at the end
	if(E->PushLocalFrame(env, 32) != 0)
		return;

	jobject java_event = NULL;
	switch(event->type)
	{
		case CHIAKI_REGIST_EVENT_TYPE_FINISHED_CANCELED:
			java_event = regist->java_regist_event_canceled;
			break;
		case CHIAKI_REGIST_EVENT_TYPE_FINISHED_FAILED:
			java_event = regist->java_regist_event_failed;
			break;
		case CHIAKI_REGIST_EVENT_TYPE_FINISHED_SUCCESS:
		{
			ChiakiRegisteredHost *host = event->registered_host;
			jobject java_host = E->NewObject(env, regist->java_regist_host_class, regist->java_regist_host_ctor,
					create_jni_target(env, regist->java_target_class, host->target),
					jnistr_from_ascii(env, host->ap_ssid),
					jnistr_from_ascii(env, host->ap_bssid),
					jnistr_from_ascii(env, host->ap_key),
					jnistr_from_ascii(env, host->ap_name),
					jnibytearray_create(env, host->server_mac, sizeof(host->server_mac)),
					jnistr_from_ascii(env, host->server_nickname),
					jnibytearray_create(env, (const uint8_t *)host->rp_regist_key, sizeof(host->rp_regist_key)),
					(jint)host->rp_key_type,
					jnibytearray_create(env, host->rp_key, sizeof(host->rp_key)));
			java_event = E->NewObject(env, regist->java_regist_event_success_class, regist->java_regist_event_success_ctor, java_host);
			break;
		}
	}

	if(java_event)
		E->CallVoidMethod(env, regist->java_regist, regist->java_regist_event_meth, java_event);

	E->PopLocalFrame(env, NULL);
}

static void android_chiaki_regist_fini_partial(JNIEnv *env, AndroidChiakiRegist *regist)
{
	android_chiaki_jni_log_fini(&regist->log, env);
	E->DeleteGlobalRef(env, regist->java_regist);
	E->DeleteGlobalRef(env, regist->java_target_class);
	E->DeleteGlobalRef(env, regist->java_regist_event_canceled);
	E->DeleteGlobalRef(env, regist->java_regist_event_failed);
	E->DeleteGlobalRef(env, regist->java_regist_event_success_class);
	E->DeleteGlobalRef(env, regist->java_regist_host_class);
}

JNIEXPORT void JNICALL JNI_FCN(registStart)(JNIEnv *env, jobject obj, jobject result, jobject regist_info_obj, jobject log_obj, jobject java_regist)
{
	jclass result_class = E->GetObjectClass(env, result);
	ChiakiErrorCode err = CHIAKI_ERR_SUCCESS;
	AndroidChiakiRegist *regist = CHIAKI_NEW(AndroidChiakiRegist);
	if(!regist)
	{
		err = CHIAKI_ERR_MEMORY;
		goto beach;
	}

	android_chiaki_jni_log_init(&regist->log, env, log_obj);

	regist->java_regist = E->NewGlobalRef(env, java_regist);
	regist->java_regist_event_meth = E->GetMethodID(env, E->GetObjectClass(env, regist->java_regist), "event", "(L"BASE_PACKAGE"/RegistEvent;)V");

	regist->java_target_class = E->NewGlobalRef(env, E->FindClass(env, BASE_PACKAGE"/Target"));

	regist->java_regist_event_canceled = E->NewGlobalRef(env, get_kotlin_global_object(env, BASE_PACKAGE"/RegistEventCanceled"));
	regist->java_regist_event_failed = E->NewGlobalRef(env, get_kotlin_global_object(env, BASE_PACKAGE"/RegistEventFailed"));
	regist->java_regist_event_success_class = E->NewGlobalRef(env, E->FindClass(env, BASE_PACKAGE"/RegistEventSuccess"));
	regist->java_regist_event_success_ctor = E->GetMethodID(env, regist->java_regist_event_success_class, "<init>", "(L"BASE_PACKAGE"/RegistHost;)V");

	regist->java_regist_host_class = E->NewGlobalRef(env, E->FindClass(env, BASE_PACKAGE"/RegistHost"));
	regist->java_regist_host_ctor = E->GetMethodID(env, regist->java_regist_host_class, "<init>", "("
			  "L"BASE_PACKAGE"/Target;" // target: Target
			  "Ljava/lang/String;" // apSsid: String
			  "Ljava/lang/String;" // apBssid: String
			  "Ljava/lang/String;" // apKey: String
			  "Ljava/lang/String;" // apName: String
			  "[B" // serverMac: ByteArray
			  "Ljava/lang/String;" // serverNickname: String
			  "[B" // rpRegistKey: ByteArray
			  "I" // rpKeyType: UInt
			  "[B" // rpKey: ByteArray
			  ")V");

	jclass regist_info_class = E->GetObjectClass(env, regist_info_obj);

	jobject target_obj = E->GetObjectField(env, regist_info_obj, E->GetFieldID(env, regist_info_class, "target", "L"BASE_PACKAGE"/Target;"));
	jclass target_class = E->GetObjectClass(env, target_obj);
	jint target_value = E->GetIntField(env, target_obj, E->GetFieldID(env, target_class, "value", "I"));

	jstring host_string = E->GetObjectField(env, regist_info_obj, E->GetFieldID(env, regist_info_class, "host", "Ljava/lang/String;"));
	jboolean broadcast = E->GetBooleanField(env, regist_info_obj, E->GetFieldID(env, regist_info_class, "broadcast", "Z"));
	jstring psn_online_id_string = E->GetObjectField(env, regist_info_obj, E->GetFieldID(env, regist_info_class, "psnOnlineId", "Ljava/lang/String;"));
	jbyteArray psn_account_id_array = E->GetObjectField(env, regist_info_obj, E->GetFieldID(env, regist_info_class, "psnAccountId", "[B"));
	jint pin = E->GetIntField(env, regist_info_obj, E->GetFieldID(env, regist_info_class, "pin", "I"));

	ChiakiRegistInfo regist_info = { 0 };
	regist_info.target = (ChiakiTarget)target_value;
	regist_info.host = E->GetStringUTFChars(env, host_string, NULL);
	regist_info.broadcast = broadcast;
	if(psn_online_id_string)
		regist_info.psn_online_id = E->GetStringUTFChars(env, psn_online_id_string, NULL);
	if(psn_account_id_array && E->GetArrayLength(env, psn_account_id_array) == sizeof(regist_info.psn_account_id))
		E->GetByteArrayRegion(env, psn_account_id_array, 0, sizeof(regist_info.psn_account_id), (jbyte *)regist_info.psn_account_id);
	regist_info.pin = (uint32_t)pin;

	err = chiaki_regist_start(&regist->regist, &regist->log.log, &regist_info, android_chiaki_regist_cb, regist);

	E->ReleaseStringUTFChars(env, host_string, regist_info.host);
	if(regist_info.psn_online_id)
		E->ReleaseStringUTFChars(env, psn_online_id_string, regist_info.psn_online_id);

	if(err != CHIAKI_ERR_SUCCESS)
	{
		android_chiaki_regist_fini_partial(env, regist);
		free(regist);
		regist = NULL;
	}

beach:
	E->SetIntField(env, result, E->GetFieldID(env, result_class, "errorCode", "I"), (jint)err);
	E->SetLongField(env, result, E->GetFieldID(env, result_class, "ptr", "J"), (jlong)regist);
}

JNIEXPORT void JNICALL JNI_FCN(registStop)(JNIEnv *env, jobject obj, jlong ptr)
{
	AndroidChiakiRegist *regist = (AndroidChiakiRegist *)ptr;
	chiaki_regist_stop(&regist->regist);
}

JNIEXPORT void JNICALL JNI_FCN(registFree)(JNIEnv *env, jobject obj, jlong ptr)
{
	AndroidChiakiRegist *regist = (AndroidChiakiRegist *)ptr;
	chiaki_regist_fini(&regist->regist);
	android_chiaki_regist_fini_partial(env, regist);
	free(regist);
}
