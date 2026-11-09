// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include <chiaki/congestioncontrol.h>

#define CONGESTION_CONTROL_INTERVAL_MS 200

/*
 * Every 200 ms the console is told how many packets arrived and how many were lost, and it adapts:
 * small losses make it add repair pieces (FEC), bigger ones make it cut the bitrate, which it then
 * gives back very slowly (a few kbps a second). packet_loss_max is the most loss ever told: the
 * fork used 0 (always "nothing lost": no repair at all), the network tests of 2.5.7 found 2 % the
 * best (almost all the repair the console gives, and the bitrate stays).
 */
static void *congestion_control_thread_func(void *user)
{
	ChiakiCongestionControl *control = user;

	ChiakiErrorCode err = chiaki_bool_pred_cond_lock(&control->stop_cond);
	if(err != CHIAKI_ERR_SUCCESS)
		return NULL;

	while(true)
	{
		err = chiaki_bool_pred_cond_timedwait(&control->stop_cond, CONGESTION_CONTROL_INTERVAL_MS);
		if(err != CHIAKI_ERR_TIMEOUT)
			break;

		uint64_t received;
		uint64_t lost;
		chiaki_packet_stats_get(control->stats, true, &received, &lost);
		ChiakiTakionCongestionPacket packet = { 0 };
		uint64_t total = received + lost;
		control->received_total += received;
		control->lost_total += lost;
		control->packet_loss = total > 0 ? (double)lost / total : 0;
		if(control->packet_loss > control->packet_loss_max)
		{
			CHIAKI_LOGV(control->takion->log, "Increasing received packets to reduce hit on stream quality");
			lost = (uint64_t)(total * control->packet_loss_max);
			received = total - lost;
		}
		packet.received = (uint16_t)(received > UINT16_MAX ? UINT16_MAX : received);
		packet.lost = (uint16_t)(lost > UINT16_MAX ? UINT16_MAX : lost);
		CHIAKI_LOGV(control->takion->log, "Sending Congestion Control Packet, received: %u, lost: %u",
			(unsigned int)packet.received, (unsigned int)packet.lost);
		chiaki_takion_send_congestion(control->takion, &packet);
	}

	chiaki_bool_pred_cond_unlock(&control->stop_cond);
	return NULL;
}

CHIAKI_EXPORT ChiakiErrorCode chiaki_congestion_control_start(ChiakiCongestionControl *control, ChiakiTakion *takion, ChiakiPacketStats *stats, double packet_loss_max)
{
	control->takion = takion;
	control->stats = stats;
	control->packet_loss_max = packet_loss_max;
	control->packet_loss = 0;
	control->received_total = 0;
	control->lost_total = 0;

	ChiakiErrorCode err = chiaki_bool_pred_cond_init(&control->stop_cond);
	if(err != CHIAKI_ERR_SUCCESS)
		return err;

	err = chiaki_thread_create(&control->thread, congestion_control_thread_func, control);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		chiaki_bool_pred_cond_fini(&control->stop_cond);
		return err;
	}

	chiaki_thread_set_name(&control->thread, "Chiaki Congestion Control");

	return CHIAKI_ERR_SUCCESS;
}

CHIAKI_EXPORT ChiakiErrorCode chiaki_congestion_control_stop(ChiakiCongestionControl *control)
{
	ChiakiErrorCode err = chiaki_bool_pred_cond_signal(&control->stop_cond);
	if(err != CHIAKI_ERR_SUCCESS)
		return err;

	err = chiaki_thread_join(&control->thread, NULL);
	if(err != CHIAKI_ERR_SUCCESS)
		return err;
	control->thread.thread = 0;

	return chiaki_bool_pred_cond_fini(&control->stop_cond);
}
