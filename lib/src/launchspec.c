// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include <chiaki/launchspec.h>
#include <chiaki/session.h>
#include <chiaki/base64.h>

#include <stdio.h>

static const char launchspec_fmt[] =
	"{"
		"\"sessionId\":\"sessionId4321\","
		"\"streamResolutions\":[%s]," // 0: built by format_resolutions()
		"\"network\":{"
			"\"bwKbpsSent\":%u," // 3
			"\"bwLoss\":0.001000,"
			"\"mtu\":%u," // 4
			"\"rtt\":%u," // 5
			"\"ports\":[53,2053]"
		"},"
		"\"slotId\":1,"
		"\"appSpecification\":{"
			"\"minFps\":30,"
			"\"minBandwidth\":0,"
			"\"extTitleId\":\"ps3\","
			"\"version\":1,"
			"\"timeLimit\":1,"
			"\"startTimeout\":100,"
			"\"afkTimeout\":100,"
			"\"afkTimeoutDisconnect\":100"
		"},"
		"\"konan\":{"
			"\"ps3AccessToken\":\"accessToken\","
			"\"ps3RefreshToken\":\"refreshToken\""
		"},\"requestGameSpecification\":{"
			"\"model\":\"bravia_tv\","
			"\"platform\":\"android\","
			"\"audioChannels\":\"5.1\","
			"\"language\":\"sp\","
			"\"acceptButton\":\"X\","
			"\"connectedControllers\":[\"xinput\",\"ds3\",\"ds4\"],"
			"\"yuvCoefficient\":\"bt601\","
			"\"videoEncoderProfile\":\"hw4.1\","
			"\"audioEncoderProfile\":\"audio1\"" // 6
			"%s"
		"},"
		"\"userProfile\":{"
			"\"onlineId\":\"psnId\","
			"\"npId\":\"npId\","
			"\"region\":\"US\","
			"\"languagesUsed\":[\"en\",\"jp\"]"
		"},"
		"%s" // 7
		"%s" // 8
		"\"handshakeKey\":\"%s\"" // 9
	"}";

static const char resolution_fmt[] =
	"{"
		"\"resolution\":"
		"{"
			"\"width\":%u,"
			"\"height\":%u"
		"},"
		"\"maxFps\":%u,"
		"\"score\":%u"
	"}";

/**
 * The resolutions offered to the console. Always the chosen one (width x height) first; then
 * every other 16:9 step in resolutions_mask (CHIAKI_LAUNCHSPEC_RES_*), highest first, with a
 * lower score each, so that the console may switch by itself when the network changes (it
 * marks which one each frame uses, see adaptive_stream_index).
 */
static int format_resolutions(char *buf, size_t buf_size, ChiakiLaunchSpec *launch_spec)
{
	static const unsigned int steps[] = { 1080, 720, 540, 360 };
	static const unsigned int bits[] = { CHIAKI_LAUNCHSPEC_RES_1080, CHIAKI_LAUNCHSPEC_RES_720,
		CHIAKI_LAUNCHSPEC_RES_540, CHIAKI_LAUNCHSPEC_RES_360 };
	int written = snprintf(buf, buf_size, resolution_fmt,
			launch_spec->width, launch_spec->height, launch_spec->max_fps, 10u);
	if(written < 0 || (size_t)written >= buf_size)
		return -1;
	unsigned int score = 9;
	for(size_t i=0; i<sizeof(steps)/sizeof(steps[0]); i++)
	{
		unsigned int h = steps[i];
		if(!(launch_spec->resolutions_mask & bits[i]) || h == launch_spec->height)
			continue;
		int n = snprintf(buf + written, buf_size - written, ",");
		if(n < 0 || (size_t)(written + n) >= buf_size)
			return -1;
		written += n;
		n = snprintf(buf + written, buf_size - written, resolution_fmt,
				h * 16 / 9, h, launch_spec->max_fps, score);
		if(n < 0 || (size_t)(written + n) >= buf_size)
			return -1;
		written += n;
		if(score > 1)
			score--;
	}
	return written;
}

CHIAKI_EXPORT int chiaki_launchspec_format(char *buf, size_t buf_size, ChiakiLaunchSpec *launch_spec)
{
	char resolutions[512];
	if(format_resolutions(resolutions, sizeof(resolutions), launch_spec) < 0)
		return -1;

	char handshake_key_b64[CHIAKI_HANDSHAKE_KEY_SIZE * 2];
	ChiakiErrorCode err = chiaki_base64_encode(launch_spec->handshake_key, CHIAKI_HANDSHAKE_KEY_SIZE, handshake_key_b64, sizeof(handshake_key_b64));
	if(err != CHIAKI_ERR_SUCCESS)
		return -1;

	char *extras[3];
	if(chiaki_target_is_ps5(launch_spec->target)) // TODO: probably also for ps4, but only 12
	{
		extras[0] = ",\"adaptiveStreamMode\": \"resize\"";
		extras[1] = chiaki_codec_is_h265(launch_spec->codec)
			? "\"videoCodec\":\"hevc\","
			: "\"videoCodec\":\"avc\",";
		extras[2] = chiaki_codec_is_hdr(launch_spec->codec)
			? "\"dynamicRange\":\"HDR\","
			: "\"dynamicRange\":\"SDR\",";
	}
	else
		extras[0] = extras[1] = extras[2] = "";

	int written = snprintf(buf, buf_size, launchspec_fmt,
			resolutions,
			launch_spec->bw_kbps_sent, launch_spec->mtu, launch_spec->rtt,
			extras[0], extras[1], extras[2], handshake_key_b64);
	if(written < 0 || written >= buf_size)
		return -1;
	return written;
}
