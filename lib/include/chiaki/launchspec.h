// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_LAUNCHSPEC_H
#define CHIAKI_LAUNCHSPEC_H

#include "common.h"

#include <stdint.h>
#include <stdlib.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct chiaki_launch_spec_t
{
	ChiakiTarget target;
	unsigned int mtu;
	unsigned int rtt;
	uint8_t *handshake_key;
	unsigned int width;
	unsigned int height;
	unsigned int max_fps;
	ChiakiCodec codec;
	unsigned int bw_kbps_sent;
	unsigned int resolutions_mask; // other 16:9 resolutions offered besides width x height
} ChiakiLaunchSpec;

#define CHIAKI_LAUNCHSPEC_RES_360  (1u << 0)
#define CHIAKI_LAUNCHSPEC_RES_540  (1u << 1)
#define CHIAKI_LAUNCHSPEC_RES_720  (1u << 2)
#define CHIAKI_LAUNCHSPEC_RES_1080 (1u << 3)

CHIAKI_EXPORT int chiaki_launchspec_format(char *buf, size_t buf_size, ChiakiLaunchSpec *launch_spec);

#ifdef __cplusplus
}
#endif

#endif // CHIAKI_LAUNCHSPEC_H
