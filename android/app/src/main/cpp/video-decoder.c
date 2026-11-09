// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "video-decoder.h"

#include <jni.h>

#include <android/native_window_jni.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>

#define INPUT_BUFFER_TIMEOUT_MS 10
/** how long the input thread keeps trying to give one frame to the codec before dropping it */
#define INPUT_FRAME_GIVE_UP_MS 100
#define SHUTDOWN_INPUT_TIMEOUT_MS 100

static void *android_chiaki_video_decoder_output_thread_func(void *user);
static void *android_chiaki_video_decoder_input_thread_func(void *user);

/**
 * The format for the decoder. With low_latency, the options that make it hand
 * out each picture as soon as it is decoded: without them, Qualcomm and Exynos
 * decoders (and others) hold back a few pictures first, which adds delay and
 * makes pictures come out in bursts. The fork set none of them. Keys a decoder
 * does not know are ignored; one that refuses them is retried without them.
 */
static AMediaFormat *new_format(AndroidChiakiVideoDecoder *decoder,
                                const char *mime, bool low_latency) {
  AMediaFormat *format = AMediaFormat_new();
  AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, mime);
  AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, decoder->target_width);
  AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT,
                        decoder->target_height);
  if (low_latency) {
    // Android 11+ standard key ("low-latency")
    AMediaFormat_setInt32(format, "low-latency", 1);
    // realtime priority (0), the key exists since Android 6
    AMediaFormat_setInt32(format, "priority", 0);
    // Qualcomm (Snapdragon)
    AMediaFormat_setInt32(format, "vendor.qti-ext-dec-picture-order.enable", 1);
    AMediaFormat_setInt32(format, "vendor.qti-ext-dec-low-latency.enable", 1);
    // Samsung Exynos
    AMediaFormat_setInt32(format, "vendor.rtc-ext-dec-low-latency.enable", 1);
  }
  return format;
}

/** Which decoder Android gave us, in the session log (the name exists from Android 9) */
static void log_codec_name(AndroidChiakiVideoDecoder *decoder) {
  void *lib = dlopen("libmediandk.so", RTLD_NOW);
  if (!lib)
    return;
  media_status_t (*get_name)(AMediaCodec *, char **) =
      dlsym(lib, "AMediaCodec_getName");
  void (*release_name)(AMediaCodec *, char *) =
      dlsym(lib, "AMediaCodec_releaseName");
  char *name = NULL;
  if (get_name && get_name(decoder->codec, &name) == AMEDIA_OK && name) {
    CHIAKI_LOGI(decoder->log, "Video decoder: %s", name);
    if (release_name)
      release_name(decoder->codec, name);
  }
  dlclose(lib);
}

ChiakiErrorCode
android_chiaki_video_decoder_init(AndroidChiakiVideoDecoder *decoder,
                                  ChiakiLog *log, int32_t target_width,
                                  int32_t target_height, ChiakiCodec codec) {
  decoder->log = log;
  decoder->codec = NULL;
  decoder->window = NULL;
  decoder->timestamp_cur = 0;
  decoder->target_width = target_width;
  decoder->target_height = target_height;
  decoder->target_codec = codec;
  decoder->shutdown_output = false;
  decoder->stat_frames = 0;
  decoder->stat_bytes = 0;
  decoder->stat_lost = 0;
  decoder->stat_recovered = 0;
  decoder->out_width = 0;
  decoder->out_height = 0;
  decoder->queue_start = 0;
  decoder->queue_count = 0;
  decoder->input_stop = false;
  decoder->input_failed = false;
  decoder->input_thread_running = false;
  decoder->stat_queue_max = 0;
  decoder->stat_queue_full = 0;
  ChiakiErrorCode err = chiaki_mutex_init(&decoder->codec_mutex, false);
  if (err != CHIAKI_ERR_SUCCESS)
    return err;
  err = chiaki_mutex_init(&decoder->queue_mutex, false);
  if (err != CHIAKI_ERR_SUCCESS)
    goto error_codec_mutex;
  err = chiaki_cond_init(&decoder->queue_cond);
  if (err != CHIAKI_ERR_SUCCESS)
    goto error_queue_mutex;
  err = chiaki_thread_create(&decoder->input_thread,
                             android_chiaki_video_decoder_input_thread_func,
                             decoder);
  if (err != CHIAKI_ERR_SUCCESS)
    goto error_cond;
  chiaki_thread_set_name(&decoder->input_thread, "Video Input");
  decoder->input_thread_running = true;
  return CHIAKI_ERR_SUCCESS;

error_cond:
  chiaki_cond_fini(&decoder->queue_cond);
error_queue_mutex:
  chiaki_mutex_fini(&decoder->queue_mutex);
error_codec_mutex:
  chiaki_mutex_fini(&decoder->codec_mutex);
  return err;
}

/** throws away the frames waiting (queue_mutex held) */
static void clear_queue_locked(AndroidChiakiVideoDecoder *decoder) {
  while (decoder->queue_count > 0) {
    free(decoder->queue[decoder->queue_start].buf);
    decoder->queue[decoder->queue_start].buf = NULL;
    decoder->queue_start =
        (decoder->queue_start + 1) % ANDROID_CHIAKI_VIDEO_QUEUE_SIZE;
    decoder->queue_count--;
  }
}

/**
 * Ends the codec and its output thread. Stopping the codec wakes the output thread with an
 * error and shutdown_output makes it leave. The fork gave the end-of-stream buffer only 1 ms
 * and, when it did not get one (easy at 60 fps), deleted the codec without waiting for the
 * output thread, which went on using it: a crash, or a thread spinning until the app died.
 */
static void kill_decoder(AndroidChiakiVideoDecoder *decoder) {
  chiaki_mutex_lock(&decoder->codec_mutex);
  decoder->shutdown_output = true;
  ssize_t codec_buf_index = AMediaCodec_dequeueInputBuffer(
      decoder->codec, SHUTDOWN_INPUT_TIMEOUT_MS * 1000);
  if (codec_buf_index >= 0) {
    CHIAKI_LOGI(decoder->log, "Video Decoder sending EOS buffer");
    AMediaCodec_queueInputBuffer(decoder->codec, (size_t)codec_buf_index, 0, 0,
                                 decoder->timestamp_cur++,
                                 AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
  } else {
    CHIAKI_LOGE(decoder->log,
                "Failed to get input buffer for shutting down Video Decoder!");
  }
  AMediaCodec_stop(decoder->codec);
  chiaki_mutex_unlock(&decoder->codec_mutex);
  // frames waiting for this codec are worth nothing to the next one (it starts from a key frame)
  chiaki_mutex_lock(&decoder->queue_mutex);
  clear_queue_locked(decoder);
  chiaki_mutex_unlock(&decoder->queue_mutex);
  chiaki_thread_join(&decoder->output_thread, NULL);
  AMediaCodec_delete(decoder->codec);
  decoder->codec = NULL;
  decoder->shutdown_output = false;
  // the codec held on to it; the fork never let it go
  if (decoder->window) {
    ANativeWindow_release(decoder->window);
    decoder->window = NULL;
  }
}

void android_chiaki_video_decoder_fini(AndroidChiakiVideoDecoder *decoder) {
  if (decoder->input_thread_running) {
    chiaki_mutex_lock(&decoder->queue_mutex);
    decoder->input_stop = true;
    chiaki_cond_broadcast(&decoder->queue_cond);
    chiaki_mutex_unlock(&decoder->queue_mutex);
    chiaki_thread_join(&decoder->input_thread, NULL);
    decoder->input_thread_running = false;
  }
  if (decoder->codec)
    kill_decoder(decoder);
  chiaki_mutex_lock(&decoder->queue_mutex);
  clear_queue_locked(decoder);
  chiaki_mutex_unlock(&decoder->queue_mutex);
  chiaki_cond_fini(&decoder->queue_cond);
  chiaki_mutex_fini(&decoder->queue_mutex);
  chiaki_mutex_fini(&decoder->codec_mutex);
}

void android_chiaki_video_decoder_set_surface(
    AndroidChiakiVideoDecoder *decoder, JNIEnv *env, jobject surface) {
  chiaki_mutex_lock(&decoder->codec_mutex);

  if (!surface) {
    // kill_decoder() locks codec_mutex itself: the fork called it with the mutex already
    // held (and returned without unlocking), which froze on the spot
    chiaki_mutex_unlock(&decoder->codec_mutex);
    if (decoder->codec) {
      kill_decoder(decoder);
      CHIAKI_LOGI(decoder->log, "Decoder shut down after surface was removed");
    }
    return;
  }

  if (decoder->codec) {
#if __ANDROID_API__ >= 23
    CHIAKI_LOGI(decoder->log,
                "Video decoder already initialized, swapping surface");
    ANativeWindow *new_window =
        surface ? ANativeWindow_fromSurface(env, surface) : NULL;
    AMediaCodec_setOutputSurface(decoder->codec, new_window);
    ANativeWindow_release(decoder->window);
    decoder->window = new_window;
#else
    CHIAKI_LOGE(decoder->log, "Video Decoder already initialized");
#endif
    goto beach;
  }

  decoder->window = ANativeWindow_fromSurface(env, surface);

  const char *mime =
      chiaki_codec_is_h265(decoder->target_codec) ? "video/hevc" : "video/avc";
  CHIAKI_LOGI(decoder->log, "Initializing decoder with mime %s", mime);

  decoder->codec = AMediaCodec_createDecoderByType(mime);
  if (!decoder->codec) {
    CHIAKI_LOGE(decoder->log, "Failed to create AMediaCodec for mime type %s",
                mime);
    goto error_surface;
  }

  log_codec_name(decoder);

  // First with the low latency options; a decoder that refuses them gets a
  // second try without them (a fresh codec: a failed configure can leave it
  // unusable)
  AMediaFormat *format = new_format(decoder, mime, true);
  media_status_t r =
      AMediaCodec_configure(decoder->codec, format, decoder->window, NULL, 0);
  if (r != AMEDIA_OK) {
    CHIAKI_LOGW(decoder->log,
                "AMediaCodec_configure() with low latency options failed: %d, "
                "trying without them",
                (int)r);
    AMediaFormat_delete(format);
    AMediaCodec_delete(decoder->codec);
    decoder->codec = AMediaCodec_createDecoderByType(mime);
    if (!decoder->codec) {
      CHIAKI_LOGE(decoder->log, "Failed to create AMediaCodec for mime type %s",
                  mime);
      goto error_surface;
    }
    format = new_format(decoder, mime, false);
    r = AMediaCodec_configure(decoder->codec, format, decoder->window, NULL, 0);
  } else
    CHIAKI_LOGI(decoder->log, "Video decoder configured in low latency mode");
  if (r != AMEDIA_OK) {
    CHIAKI_LOGE(decoder->log, "AMediaCodec_configure() failed: %d", (int)r);
    AMediaFormat_delete(format);
    goto error_codec;
  }

  r = AMediaCodec_start(decoder->codec);
  AMediaFormat_delete(format);
  if (r != AMEDIA_OK) {
    CHIAKI_LOGE(decoder->log, "AMediaCodec_start() failed: %d", (int)r);
    goto error_codec;
  }

  ChiakiErrorCode err = chiaki_thread_create(
      &decoder->output_thread, android_chiaki_video_decoder_output_thread_func,
      decoder);
  if (err != CHIAKI_ERR_SUCCESS) {
    CHIAKI_LOGE(decoder->log, "Failed to create output thread for AMediaCodec");
    goto error_codec;
  }
  chiaki_thread_set_name(&decoder->output_thread, "Video Output");

  goto beach;

error_codec:
  AMediaCodec_delete(decoder->codec);
  decoder->codec = NULL;

error_surface:
  ANativeWindow_release(decoder->window);
  decoder->window = NULL;

beach:
  chiaki_mutex_unlock(&decoder->codec_mutex);
}

/**
 * Called on the network thread with each whole frame: a copy goes into the queue and the call
 * returns at once (the "Video Input" thread gives it to the codec). false = the frame was not
 * taken (queue full, no codec, or the previous one could not go into the codec): the video
 * receiver then does not use it as a reference, and a picture is asked for again, as before.
 */
bool android_chiaki_video_decoder_video_sample(uint8_t *buf, size_t buf_size,
                                               int32_t frames_lost,
                                               bool frame_recovered,
                                               void *user) {
  AndroidChiakiVideoDecoder *decoder = user;
  decoder->stat_frames++;
  decoder->stat_bytes += buf_size;
  if (frames_lost > 0)
    decoder->stat_lost += (uint64_t)frames_lost;
  if (frame_recovered)
    decoder->stat_recovered++;

  // read without codec_mutex: the input thread may hold it while it waits for the codec
  if (!decoder->codec) {
    CHIAKI_LOGE(decoder->log,
                "Received video data, but decoder is not initialized!");
    return true;
  }

  chiaki_mutex_lock(&decoder->queue_mutex);
  bool r = true;
  if (decoder->input_failed) {
    decoder->input_failed = false;
    r = false;
  } else if (decoder->queue_count >= ANDROID_CHIAKI_VIDEO_QUEUE_SIZE) {
    decoder->stat_queue_full++;
    CHIAKI_LOGE(decoder->log, "Video decoder queue full, frame not taken");
    r = false;
  } else {
    uint8_t *copy = malloc(buf_size);
    if (!copy)
      r = false;
    else {
      memcpy(copy, buf, buf_size);
      size_t i = (decoder->queue_start + decoder->queue_count) %
                 ANDROID_CHIAKI_VIDEO_QUEUE_SIZE;
      decoder->queue[i].buf = copy;
      decoder->queue[i].size = buf_size;
      decoder->queue_count++;
      if (decoder->queue_count > decoder->stat_queue_max)
        decoder->stat_queue_max = (uint32_t)decoder->queue_count;
      chiaki_cond_signal(&decoder->queue_cond);
    }
  }
  chiaki_mutex_unlock(&decoder->queue_mutex);
  return r;
}

/** Gives one frame to the codec; false if it could not (no codec, or no input buffer in time) */
static bool feed_codec(AndroidChiakiVideoDecoder *decoder, uint8_t *buf,
                       size_t buf_size) {
  int waited_ms = 0;
  while (buf_size > 0) {
    // the codec lock only for one try at a time: changing the surface or closing never waits long
    chiaki_mutex_lock(&decoder->codec_mutex);
    if (!decoder->codec || decoder->shutdown_output) {
      chiaki_mutex_unlock(&decoder->codec_mutex);
      return false;
    }
    ssize_t codec_buf_index = AMediaCodec_dequeueInputBuffer(
        decoder->codec, INPUT_BUFFER_TIMEOUT_MS * 1000);
    if (codec_buf_index < 0) {
      chiaki_mutex_unlock(&decoder->codec_mutex);
      waited_ms += INPUT_BUFFER_TIMEOUT_MS;
      if (waited_ms >= INPUT_FRAME_GIVE_UP_MS) {
        CHIAKI_LOGE(decoder->log, "Failed to get input buffer");
        return false;
      }
      continue;
    }

    size_t codec_buf_size;
    uint8_t *codec_buf = AMediaCodec_getInputBuffer(
        decoder->codec, (size_t)codec_buf_index, &codec_buf_size);
    size_t codec_sample_size = buf_size;
    if (codec_sample_size > codec_buf_size)
      codec_sample_size = codec_buf_size;
    memcpy(codec_buf, buf, codec_sample_size);
    media_status_t r = AMediaCodec_queueInputBuffer(
        decoder->codec, (size_t)codec_buf_index, 0, codec_sample_size,
        decoder->timestamp_cur++,
        0); // timestamp just raised by 1 for maximum realtime
    chiaki_mutex_unlock(&decoder->codec_mutex);
    if (r != AMEDIA_OK)
      CHIAKI_LOGE(decoder->log, "AMediaCodec_queueInputBuffer() failed: %d",
                  (int)r);
    buf += codec_sample_size;
    buf_size -= codec_sample_size;
  }
  return true;
}

static void *android_chiaki_video_decoder_input_thread_func(void *user) {
  AndroidChiakiVideoDecoder *decoder = user;
  chiaki_mutex_lock(&decoder->queue_mutex);
  while (!decoder->input_stop) {
    if (decoder->queue_count == 0) {
      chiaki_cond_wait(&decoder->queue_cond, &decoder->queue_mutex);
      continue;
    }
    AndroidChiakiVideoFrame frame = decoder->queue[decoder->queue_start];
    decoder->queue[decoder->queue_start].buf = NULL;
    decoder->queue_start =
        (decoder->queue_start + 1) % ANDROID_CHIAKI_VIDEO_QUEUE_SIZE;
    decoder->queue_count--;
    chiaki_mutex_unlock(&decoder->queue_mutex);

    bool ok = feed_codec(decoder, frame.buf, frame.size);
    free(frame.buf);

    chiaki_mutex_lock(&decoder->queue_mutex);
    if (!ok && decoder->codec)
      decoder->input_failed = true;
  }
  chiaki_mutex_unlock(&decoder->queue_mutex);
  return NULL;
}

static void *android_chiaki_video_decoder_output_thread_func(void *user) {
  AndroidChiakiVideoDecoder *decoder = user;

  while (1) {
    AMediaCodecBufferInfo info;
    ssize_t status = AMediaCodec_dequeueOutputBuffer(decoder->codec, &info, -1);
    if (status >= 0) {
      AMediaCodec_releaseOutputBuffer(decoder->codec, (size_t)status,
                                      info.size != 0);
      if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {
        CHIAKI_LOGI(decoder->log, "AMediaCodec reported EOS");
        break;
      }
    } else if (status == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
      // the first picture, or the console switched resolution in the middle of the game
      AMediaFormat *format = AMediaCodec_getOutputFormat(decoder->codec);
      if (format) {
        int32_t w = 0, h = 0;
        AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_WIDTH, &w);
        AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_HEIGHT, &h);
        // the crop is the real picture (the coded size can be a little larger)
        int32_t l = 0, t = 0, rr = 0, b = 0;
        if (AMediaFormat_getInt32(format, "crop-left", &l) &&
            AMediaFormat_getInt32(format, "crop-top", &t) &&
            AMediaFormat_getInt32(format, "crop-right", &rr) &&
            AMediaFormat_getInt32(format, "crop-bottom", &b)) {
          w = rr - l + 1;
          h = b - t + 1;
        }
        decoder->out_width = w;
        decoder->out_height = h;
        CHIAKI_LOGI(decoder->log, "Video decoder output format: %dx%d", (int)w,
                    (int)h);
        AMediaFormat_delete(format);
      }
    } else {
      chiaki_mutex_lock(&decoder->codec_mutex);
      bool shutdown = decoder->shutdown_output;
      chiaki_mutex_unlock(&decoder->codec_mutex);
      if (shutdown) {
        CHIAKI_LOGI(decoder->log, "Video Decoder Output Thread detected "
                                  "shutdown after reported error");
        break;
      }
    }
  }

  CHIAKI_LOGI(decoder->log, "Video Decoder Output Thread exiting");

  return NULL;
}