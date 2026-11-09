// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include <chiaki/feedbacksender.h>
#include <chiaki/time.h>

#define FEEDBACK_STATE_TIMEOUT_MIN_MS 8 // minimum time to wait between sending 2 packets
#define FEEDBACK_STATE_TIMEOUT_MAX_MS 200 // maximum time to wait between sending 2 packets

#define FEEDBACK_HISTORY_BUFFER_SIZE 0x10

// History packets go once over UDP, with no acknowledgement. Each event takes the next number
// and each packet carries all the recent events; the console remembers the last number it
// applied and takes as many of a packet's newest events as the number moved, so a lost packet
// is made up by the next one. But if the packet with the last change (typically a button
// release) is lost and nothing else happens, the button stays held. So after the newest packet,
// while nothing new comes, it is sent again with the same number at these times (ms after it):
// the console ignores a repeat it already has. From chiaki-ng (streetpea/chiaki-ng bc397a72).
static const uint64_t feedback_history_tail_ms[] = { 20, 50, 100, 200 };
#define FEEDBACK_HISTORY_TAIL_COUNT (sizeof(feedback_history_tail_ms) / sizeof(feedback_history_tail_ms[0]))

static void *feedback_sender_thread_func(void *user);

CHIAKI_EXPORT ChiakiErrorCode chiaki_feedback_sender_init(ChiakiFeedbackSender *feedback_sender, ChiakiTakion *takion)
{
	feedback_sender->log = takion->log;
	feedback_sender->takion = takion;

	chiaki_controller_state_set_idle(&feedback_sender->controller_state_prev);
	chiaki_controller_state_set_idle(&feedback_sender->controller_state);

	feedback_sender->state_seq_num = 0;

	feedback_sender->history_seq_num = 0;
	feedback_sender->history_last_size = 0;
	feedback_sender->history_last_event_count = 0;
	feedback_sender->history_last_seq_num = 0;
	feedback_sender->history_last_sent_ms = 0;
	feedback_sender->history_tail_resends = FEEDBACK_HISTORY_TAIL_COUNT;
	ChiakiErrorCode err = chiaki_feedback_history_buffer_init(&feedback_sender->history_buf, FEEDBACK_HISTORY_BUFFER_SIZE);
	if(err != CHIAKI_ERR_SUCCESS)
		return err;

	err = chiaki_mutex_init(&feedback_sender->state_mutex, false);
	if(err != CHIAKI_ERR_SUCCESS)
		goto error_history_buffer;

	err = chiaki_cond_init(&feedback_sender->state_cond);
	if(err != CHIAKI_ERR_SUCCESS)
		goto error_mutex;

	err = chiaki_thread_create(&feedback_sender->thread, feedback_sender_thread_func, feedback_sender);
	if(err != CHIAKI_ERR_SUCCESS)
		goto error_cond;

	chiaki_thread_set_name(&feedback_sender->thread, "Chiaki Feedback Sender");

	return CHIAKI_ERR_SUCCESS;
error_cond:
	chiaki_cond_fini(&feedback_sender->state_cond);
error_mutex:
	chiaki_mutex_fini(&feedback_sender->state_mutex);
error_history_buffer:
	chiaki_feedback_history_buffer_fini(&feedback_sender->history_buf);
	return err;
}

CHIAKI_EXPORT void chiaki_feedback_sender_fini(ChiakiFeedbackSender *feedback_sender)
{
	chiaki_mutex_lock(&feedback_sender->state_mutex);
	feedback_sender->should_stop = true;
	chiaki_mutex_unlock(&feedback_sender->state_mutex);
	chiaki_cond_signal(&feedback_sender->state_cond);
	chiaki_thread_join(&feedback_sender->thread, NULL);
	chiaki_cond_fini(&feedback_sender->state_cond);
	chiaki_mutex_fini(&feedback_sender->state_mutex);
	chiaki_feedback_history_buffer_fini(&feedback_sender->history_buf);
}

CHIAKI_EXPORT ChiakiErrorCode chiaki_feedback_sender_set_controller_state(ChiakiFeedbackSender *feedback_sender, ChiakiControllerState *state)
{
	ChiakiErrorCode err = chiaki_mutex_lock(&feedback_sender->state_mutex);
	if(err != CHIAKI_ERR_SUCCESS)
		return err;

	if(chiaki_controller_state_equals(&feedback_sender->controller_state, state))
	{
		chiaki_mutex_unlock(&feedback_sender->state_mutex);
		return CHIAKI_ERR_SUCCESS;
	}

	feedback_sender->controller_state = *state;
	feedback_sender->controller_state_changed = true;

	chiaki_mutex_unlock(&feedback_sender->state_mutex);
	chiaki_cond_signal(&feedback_sender->state_cond);

	return CHIAKI_ERR_SUCCESS;
}

static bool controller_state_equals_for_feedback_state(ChiakiControllerState *a, ChiakiControllerState *b)
{
	if(!(a->left_x == b->left_x
		&& a->left_y == b->left_y
		&& a->right_x == b->right_x
		&& a->right_y == b->right_y))
		return false;
#define CHECKF(n) if(a->n < b->n - 0.0000001f || a->n > b->n + 0.0000001f) return false
	CHECKF(gyro_x);
	CHECKF(gyro_y);
	CHECKF(gyro_z);
	CHECKF(accel_x);
	CHECKF(accel_y);
	CHECKF(accel_z);
	CHECKF(orient_x);
	CHECKF(orient_y);
	CHECKF(orient_z);
	CHECKF(orient_w);
#undef CHECKF
	return true;
}

static void feedback_sender_send_state(ChiakiFeedbackSender *feedback_sender)
{
	ChiakiFeedbackState state;
	state.left_x = feedback_sender->controller_state.left_x;
	state.left_y = feedback_sender->controller_state.left_y;
	state.right_x = feedback_sender->controller_state.right_x;
	state.right_y = feedback_sender->controller_state.right_y;
	state.gyro_x = feedback_sender->controller_state.gyro_x;
	state.gyro_y = feedback_sender->controller_state.gyro_y;
	state.gyro_z = feedback_sender->controller_state.gyro_z;
	state.accel_x = feedback_sender->controller_state.accel_x;
	state.accel_y = feedback_sender->controller_state.accel_y;
	state.accel_z = feedback_sender->controller_state.accel_z;

	state.orient_x = feedback_sender->controller_state.orient_x;
	state.orient_y = feedback_sender->controller_state.orient_y;
	state.orient_z = feedback_sender->controller_state.orient_z;
	state.orient_w = feedback_sender->controller_state.orient_w;

	ChiakiErrorCode err = chiaki_takion_send_feedback_state(feedback_sender->takion, feedback_sender->state_seq_num++, &state);
	if(err != CHIAKI_ERR_SUCCESS)
		CHIAKI_LOGE(feedback_sender->log, "FeedbackSender failed to send Feedback State");
}

static bool controller_state_equals_for_feedback_history(ChiakiControllerState *a, ChiakiControllerState *b)
{
	if(!(a->buttons == b->buttons
		&& a->l2_state == b->l2_state
		&& a->r2_state == b->r2_state))
		return false;

	for(size_t i=0; i<CHIAKI_CONTROLLER_TOUCHES_MAX; i++)
	{
		if(a->touches[i].id != b->touches[i].id)
			return false;
		if(a->touches[i].id >= 0 && (a->touches[i].x != b->touches[i].x || a->touches[i].y != b->touches[i].y))
			return false;
	}
	return true;
}

static void feedback_sender_send_history_packet(ChiakiFeedbackSender *feedback_sender)
{
	uint8_t *buf = feedback_sender->history_last_packet;
	size_t buf_size = sizeof(feedback_sender->history_last_packet);
	ChiakiErrorCode err = chiaki_feedback_history_buffer_format(&feedback_sender->history_buf, buf, &buf_size);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		CHIAKI_LOGE(feedback_sender->log, "Feedback Sender failed to format history buffer");
		feedback_sender->history_last_size = 0;
		return;
	}

	//CHIAKI_LOGD(feedback_sender->log, "Feedback History:");
	//chiaki_log_hexdump(feedback_sender->log, CHIAKI_LOG_DEBUG, buf, buf_size);
	// remembered for the repeats; a new packet starts their schedule again
	feedback_sender->history_last_size = buf_size;
	feedback_sender->history_last_event_count = (uint8_t)feedback_sender->history_buf.len;
	feedback_sender->history_last_seq_num = feedback_sender->history_seq_num++;
	feedback_sender->history_last_sent_ms = chiaki_time_now_monotonic_ms();
	feedback_sender->history_tail_resends = 0;
	chiaki_takion_send_feedback_history(feedback_sender->takion, feedback_sender->history_last_seq_num,
		feedback_sender->history_last_event_count, buf, buf_size);
}

/** the last history packet again, with the same number, if one is due (see feedback_history_tail_ms) */
static void feedback_sender_send_history_tail(ChiakiFeedbackSender *feedback_sender, uint64_t now_ms)
{
	if(feedback_sender->history_tail_resends >= FEEDBACK_HISTORY_TAIL_COUNT || feedback_sender->history_last_size == 0)
		return;
	if(now_ms < feedback_sender->history_last_sent_ms + feedback_history_tail_ms[feedback_sender->history_tail_resends])
		return;
	feedback_sender->history_tail_resends++;
	chiaki_takion_send_feedback_history(feedback_sender->takion, feedback_sender->history_last_seq_num,
		feedback_sender->history_last_event_count, feedback_sender->history_last_packet, feedback_sender->history_last_size);
}

/** ms until the next repeat of the last history packet; UINT64_MAX = none left */
static uint64_t feedback_sender_history_tail_wait(ChiakiFeedbackSender *feedback_sender, uint64_t now_ms)
{
	if(feedback_sender->history_tail_resends >= FEEDBACK_HISTORY_TAIL_COUNT || feedback_sender->history_last_size == 0)
		return UINT64_MAX;
	uint64_t due = feedback_sender->history_last_sent_ms + feedback_history_tail_ms[feedback_sender->history_tail_resends];
	return due > now_ms ? due - now_ms : 1;
}

static void feedback_sender_send_history(ChiakiFeedbackSender *feedback_sender)
{
	ChiakiControllerState *state_prev = &feedback_sender->controller_state_prev;
	ChiakiControllerState *state_now = &feedback_sender->controller_state;
	uint64_t buttons_prev = state_prev->buttons;
	uint64_t buttons_now = state_now->buttons;
	for(uint8_t i=0; i<CHIAKI_CONTROLLER_BUTTONS_COUNT; i++)
	{
		uint64_t button_id = 1 << i;
		bool prev = buttons_prev & button_id;
		bool now = buttons_now & button_id;
		if(prev != now)
		{
			ChiakiFeedbackHistoryEvent event;
			ChiakiErrorCode err = chiaki_feedback_history_event_set_button(&event, button_id, now ? 0xff : 0);
			if(err != CHIAKI_ERR_SUCCESS)
			{
				CHIAKI_LOGE(feedback_sender->log, "Feedback Sender failed to format button history event for button id %llu", (unsigned long long)button_id);
				continue;
			}
			chiaki_feedback_history_buffer_push(&feedback_sender->history_buf, &event);
			feedback_sender_send_history_packet(feedback_sender);
		}
	}

	if(state_prev->l2_state != state_now->l2_state)
	{
		ChiakiFeedbackHistoryEvent event;
		ChiakiErrorCode err = chiaki_feedback_history_event_set_button(&event, CHIAKI_CONTROLLER_ANALOG_BUTTON_L2, state_now->l2_state);
		if(err == CHIAKI_ERR_SUCCESS)
		{
			chiaki_feedback_history_buffer_push(&feedback_sender->history_buf, &event);
			feedback_sender_send_history_packet(feedback_sender);
		}
		else
			CHIAKI_LOGE(feedback_sender->log, "Feedback Sender failed to format button history event for L2");
	}

	if(state_prev->r2_state != state_now->r2_state)
	{
		ChiakiFeedbackHistoryEvent event;
		ChiakiErrorCode err = chiaki_feedback_history_event_set_button(&event, CHIAKI_CONTROLLER_ANALOG_BUTTON_R2, state_now->r2_state);
		if(err == CHIAKI_ERR_SUCCESS)
		{
			chiaki_feedback_history_buffer_push(&feedback_sender->history_buf, &event);
			feedback_sender_send_history_packet(feedback_sender);
		}
		else
			CHIAKI_LOGE(feedback_sender->log, "Feedback Sender failed to format button history event for R2");
	}

	for(size_t i=0; i<CHIAKI_CONTROLLER_TOUCHES_MAX; i++)
	{
		if(state_prev->touches[i].id != state_now->touches[i].id && state_prev->touches[i].id >= 0)
		{
			ChiakiFeedbackHistoryEvent event;
			chiaki_feedback_history_event_set_touchpad(&event, false, (uint8_t)state_prev->touches[i].id,
					state_prev->touches[i].x, state_prev->touches[i].y);
			chiaki_feedback_history_buffer_push(&feedback_sender->history_buf, &event);
			feedback_sender_send_history_packet(feedback_sender);
		}
		else if(state_now->touches[i].id >= 0
				&& (state_prev->touches[i].id != state_now->touches[i].id
					|| state_prev->touches[i].x != state_now->touches[i].x
					|| state_prev->touches[i].y != state_now->touches[i].y))
		{
			ChiakiFeedbackHistoryEvent event;
			chiaki_feedback_history_event_set_touchpad(&event, true, (uint8_t)state_now->touches[i].id,
					state_now->touches[i].x, state_now->touches[i].y);
			chiaki_feedback_history_buffer_push(&feedback_sender->history_buf, &event);
			feedback_sender_send_history_packet(feedback_sender);
		}
	}
}

static bool state_cond_check(void *user)
{
	ChiakiFeedbackSender *feedback_sender = user;
	return feedback_sender->should_stop || feedback_sender->controller_state_changed;
}

static void *feedback_sender_thread_func(void *user)
{
	ChiakiFeedbackSender *feedback_sender = user;

	ChiakiErrorCode err = chiaki_mutex_lock(&feedback_sender->state_mutex);
	if(err != CHIAKI_ERR_SUCCESS)
		return NULL;

	// Sticks and motion sensors go out at most once every FEEDBACK_STATE_TIMEOUT_MIN_MS (the latest
	// values); buttons and touches still go out at once, with the state. Upstream left this as a TODO
	// and sent a packet for every change: with a phone's motion sensors, hundreds per second.
	uint64_t next_timeout = FEEDBACK_STATE_TIMEOUT_MAX_MS;
	uint64_t last_state_ms = 0;
	bool state_pending = false;
	while(true)
	{
		err = chiaki_cond_timedwait_pred(&feedback_sender->state_cond, &feedback_sender->state_mutex, next_timeout, state_cond_check, feedback_sender);
		if(err != CHIAKI_ERR_SUCCESS && err != CHIAKI_ERR_TIMEOUT)
			break;

		if(feedback_sender->should_stop)
			break;

		bool send_feedback_history = false;
		uint64_t now = chiaki_time_now_monotonic_ms();

		if(feedback_sender->controller_state_changed)
		{
			feedback_sender->controller_state_changed = false;

			// don't need to send feedback state if nothing relevant changed
			if(!controller_state_equals_for_feedback_state(&feedback_sender->controller_state, &feedback_sender->controller_state_prev))
				state_pending = true;

			send_feedback_history = !controller_state_equals_for_feedback_history(&feedback_sender->controller_state, &feedback_sender->controller_state_prev);
		}
		// timeout: a state that waited, or the regular keepalive (not a wake-up only for a history repeat)
		else if(now - last_state_ms + 2 >= FEEDBACK_STATE_TIMEOUT_MAX_MS)
			state_pending = true;

		next_timeout = FEEDBACK_STATE_TIMEOUT_MAX_MS - (now - last_state_ms < FEEDBACK_STATE_TIMEOUT_MAX_MS ? now - last_state_ms : FEEDBACK_STATE_TIMEOUT_MAX_MS - 1);
		if(state_pending)
		{
			uint64_t since = now - last_state_ms;
			if(send_feedback_history || since >= FEEDBACK_STATE_TIMEOUT_MIN_MS)
			{
				feedback_sender_send_state(feedback_sender);
				last_state_ms = now;
				state_pending = false;
				next_timeout = FEEDBACK_STATE_TIMEOUT_MAX_MS;
			}
			else
				next_timeout = FEEDBACK_STATE_TIMEOUT_MIN_MS - since;
		}

		if(send_feedback_history)
			feedback_sender_send_history(feedback_sender);
		else
			feedback_sender_send_history_tail(feedback_sender, now);

		// wake up for the next repeat of the last history packet, if one is left
		uint64_t tail_wait = feedback_sender_history_tail_wait(feedback_sender, chiaki_time_now_monotonic_ms());
		if(tail_wait < next_timeout)
			next_timeout = tail_wait;

		feedback_sender->controller_state_prev = feedback_sender->controller_state;
	}

	chiaki_mutex_unlock(&feedback_sender->state_mutex);

	return NULL;
}
