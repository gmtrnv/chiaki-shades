// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_JNI_VIDEO_DECODER_H
#define CHIAKI_JNI_VIDEO_DECODER_H

#include <jni.h>

#include <chiaki/log.h>
#include <chiaki/thread.h>

typedef struct AMediaCodec AMediaCodec;
typedef struct ANativeWindow ANativeWindow;

/** a frame waiting to go into the codec (its own copy of the bytes) */
typedef struct {
  uint8_t *buf;
  size_t size;
} AndroidChiakiVideoFrame;

#define ANDROID_CHIAKI_VIDEO_QUEUE_SIZE 8

typedef struct android_chiaki_video_decoder_t {
  ChiakiLog *log;
  /**
   * Frames from the network thread wait here and the "Video Input" thread feeds them to the
   * codec: the network thread never waits for the codec (it waited up to 10 ms for a free input
   * buffer, meanwhile not draining the socket). Guarded by queue_mutex.
   */
  ChiakiMutex queue_mutex;
  ChiakiCond queue_cond;
  AndroidChiakiVideoFrame queue[ANDROID_CHIAKI_VIDEO_QUEUE_SIZE];
  size_t queue_start;
  size_t queue_count;
  bool input_stop;
  /** a frame could not go into the codec: the next one says so, so a picture is asked for again */
  bool input_failed;
  ChiakiThread input_thread;
  bool input_thread_running;
  uint32_t stat_queue_max;  // most frames waiting at once since last read (STATS dq=)
  uint64_t stat_queue_full; // frames that found the queue full (STATS dqfull=)
  ChiakiMutex codec_mutex;
  AMediaCodec *codec;
  ANativeWindow *window;
  uint64_t timestamp_cur;
  ChiakiThread output_thread;
  bool shutdown_output;
  int32_t target_width;
  int32_t target_height;
  ChiakiCodec target_codec;
  // running totals for the stats overlay (read from another thread without the mutex: a
  // number that is a moment late is fine there)
  uint64_t stat_frames;    // frames handed to the decoder
  uint64_t stat_bytes;     // their bytes
  uint64_t stat_lost;      // frames that never arrived whole
  uint64_t stat_recovered; // frames repaired with the error correction data
  int32_t out_width;       // size the decoder says it is putting out (0 = not yet)
  int32_t out_height;
} AndroidChiakiVideoDecoder;

ChiakiErrorCode
android_chiaki_video_decoder_init(AndroidChiakiVideoDecoder *decoder,
                                  ChiakiLog *log, int32_t target_width,
                                  int32_t target_height, ChiakiCodec codec);
void android_chiaki_video_decoder_fini(AndroidChiakiVideoDecoder *decoder);
void android_chiaki_video_decoder_set_surface(
    AndroidChiakiVideoDecoder *decoder, JNIEnv *env, jobject surface);
bool android_chiaki_video_decoder_video_sample(uint8_t *buf, size_t buf_size,
                                               int32_t frames_lost,
                                               bool frame_recovered,
                                               void *user);

#endif