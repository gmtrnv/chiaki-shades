// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

// AES-128 and GMAC with the ARMv8 Crypto Extensions. Only this file is built with +crypto (see
// CMakeLists.txt), and nothing here runs unless chiaki_aes_hw_supported() says the CPU has them.

#include "aes_armv8.h"

#if defined(__aarch64__) && defined(__linux__) && defined(__ARM_FEATURE_CRYPTO)

#include <arm_neon.h>
#include <asm/hwcap.h>
#include <string.h>
#include <sys/auxv.h>

bool chiaki_aes_hw_supported(void)
{
	unsigned long hwcap = getauxval(AT_HWCAP);
	return (hwcap & HWCAP_AES) && (hwcap & HWCAP_PMULL);
}

/**
 * SubBytes of each byte of w. AESE is AddRoundKey (zero here), ShiftRows and SubBytes; with the
 * same word in all four columns, ShiftRows changes nothing.
 */
static inline uint32_t sub_word(uint32_t w)
{
	uint8x16_t v = vaeseq_u8(vreinterpretq_u8_u32(vdupq_n_u32(w)), vdupq_n_u8(0));
	return vgetq_lane_u32(vreinterpretq_u32_u8(v), 0);
}

static inline uint32_t ror32(uint32_t w, int s)
{
	return (w >> s) | (w << (32 - s));
}

void chiaki_aes128_hw_expand_key(const uint8_t key[16], uint8_t round_keys[CHIAKI_AES128_ROUND_KEYS_SIZE])
{
	static const uint8_t rcon[10] = { 0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36 };
	// little-endian words: byte 0 of each word is its low byte, so RotWord is a right rotation by 8
	uint32_t w[44];
	memcpy(w, key, 16);
	for(int i = 0; i < 10; i++)
	{
		const uint32_t *p = w + 4 * i;
		uint32_t *n = w + 4 * (i + 1);
		n[0] = p[0] ^ ror32(sub_word(p[3]), 8) ^ rcon[i];
		n[1] = p[1] ^ n[0];
		n[2] = p[2] ^ n[1];
		n[3] = p[3] ^ n[2];
	}
	memcpy(round_keys, w, sizeof(w));
}

static inline void load_round_keys(const uint8_t *round_keys, uint8x16_t rk[11])
{
	for(int i = 0; i < 11; i++)
		rk[i] = vld1q_u8(round_keys + 16 * i);
}

static inline uint8x16_t aes_block(const uint8x16_t rk[11], uint8x16_t b)
{
	for(int i = 0; i < 9; i++)
		b = vaesmcq_u8(vaeseq_u8(b, rk[i]));
	return veorq_u8(vaeseq_u8(b, rk[9]), rk[10]);
}

void chiaki_aes128_hw_encrypt_blocks(const uint8_t round_keys[CHIAKI_AES128_ROUND_KEYS_SIZE], uint8_t *buf, size_t blocks)
{
	uint8x16_t rk[11];
	load_round_keys(round_keys, rk);
	size_t i = 0;
	// four blocks at a time: the AES instructions are pipelined, one block alone waits on each round
	for(; i + 4 <= blocks; i += 4)
	{
		uint8_t *p = buf + 16 * i;
		uint8x16_t b0 = vld1q_u8(p), b1 = vld1q_u8(p + 16), b2 = vld1q_u8(p + 32), b3 = vld1q_u8(p + 48);
		for(int r = 0; r < 9; r++)
		{
			b0 = vaesmcq_u8(vaeseq_u8(b0, rk[r]));
			b1 = vaesmcq_u8(vaeseq_u8(b1, rk[r]));
			b2 = vaesmcq_u8(vaeseq_u8(b2, rk[r]));
			b3 = vaesmcq_u8(vaeseq_u8(b3, rk[r]));
		}
		vst1q_u8(p, veorq_u8(vaeseq_u8(b0, rk[9]), rk[10]));
		vst1q_u8(p + 16, veorq_u8(vaeseq_u8(b1, rk[9]), rk[10]));
		vst1q_u8(p + 32, veorq_u8(vaeseq_u8(b2, rk[9]), rk[10]));
		vst1q_u8(p + 48, veorq_u8(vaeseq_u8(b3, rk[9]), rk[10]));
	}
	for(; i < blocks; i++)
		vst1q_u8(buf + 16 * i, aes_block(rk, vld1q_u8(buf + 16 * i)));
}

static inline uint8x16_t pmull_lo(uint8x16_t a, uint8x16_t b)
{
	return vreinterpretq_u8_p128(vmull_p64(
			(poly64_t)vgetq_lane_u64(vreinterpretq_u64_u8(a), 0),
			(poly64_t)vgetq_lane_u64(vreinterpretq_u64_u8(b), 0)));
}

static inline uint8x16_t pmull_hi(uint8x16_t a, uint8x16_t b)
{
	return vreinterpretq_u8_p128(vmull_high_p64(vreinterpretq_p64_u8(a), vreinterpretq_p64_u8(b)));
}

/**
 * a * b in GCM's GF(2^128). Both come bit-reversed in each byte (vrbitq_u8): then GCM's bit order
 * (bit 0 = top bit of byte 0 = x^0) is the plain little-endian 128 bit number, so this is a plain
 * carry-less multiplication reduced modulo x^128 + x^7 + x^2 + x + 1 (x^128 = 0x87).
 * Same layout as mbedtls 3's aesce.c: h = a1*b1 at bit 128, m = a1*b0 + a0*b1 at bit 64, l = a0*b0.
 */
static inline uint8x16_t gf128_mult(uint8x16_t a, uint8x16_t b)
{
	const uint8x16_t zero = vdupq_n_u8(0);
	const uint8x16_t mod = vreinterpretq_u8_u64(vdupq_n_u64(0x87));
	uint8x16_t h = pmull_hi(a, b);
	uint8x16_t l = pmull_lo(a, b);
	uint8x16_t b_swapped = vextq_u8(b, b, 8);
	uint8x16_t m = veorq_u8(pmull_hi(a, b_swapped), pmull_lo(a, b_swapped));
	uint8x16_t c = pmull_hi(h, mod); // bits 192..255 folded down, lands at bit 64
	uint8x16_t d = pmull_lo(h, mod); // bits 128..191 folded down, lands at bit 0
	uint8x16_t e = veorq_u8(c, m); // at bit 64
	uint8x16_t f = pmull_hi(e, mod); // its part above bit 128 folded down, at bit 0
	uint8x16_t g = vextq_u8(zero, e, 8); // its part below bit 128
	return veorq_u8(veorq_u8(d, l), veorq_u8(f, g));
}

/** one GHASH step, x and h bit-reversed as gf128_mult wants them */
static inline uint8x16_t ghash_step(uint8x16_t x, uint8x16_t h, uint8x16_t block)
{
	return gf128_mult(veorq_u8(x, vrbitq_u8(block)), h);
}

static inline uint8x16_t length_block(uint64_t bits_hi, uint64_t bits_lo)
{
	uint8_t b[16];
	for(int k = 0; k < 8; k++)
	{
		b[7 - k] = (uint8_t)(bits_hi >> (8 * k));
		b[15 - k] = (uint8_t)(bits_lo >> (8 * k));
	}
	return vld1q_u8(b);
}

void chiaki_gmac128_hw(const uint8_t key[16], const uint8_t iv[16], const uint8_t *aad, size_t aad_size, uint8_t tag[16])
{
	uint8_t round_keys[CHIAKI_AES128_ROUND_KEYS_SIZE];
	chiaki_aes128_hw_expand_key(key, round_keys);
	uint8x16_t rk[11];
	load_round_keys(round_keys, rk);

	const uint8x16_t zero = vdupq_n_u8(0);
	uint8x16_t h = vrbitq_u8(aes_block(rk, zero));

	// the iv is not 12 bytes: J0 = GHASH(iv || 0^64 || [128]_64)
	uint8x16_t x = ghash_step(zero, h, vld1q_u8(iv));
	x = ghash_step(x, h, length_block(0, 128));
	uint8x16_t ej0 = aes_block(rk, vrbitq_u8(x));

	// S = GHASH(aad zero padded || [len(aad)]_64 || [len(text) = 0]_64)
	x = zero;
	size_t full = aad_size / 16;
	for(size_t i = 0; i < full; i++)
		x = ghash_step(x, h, vld1q_u8(aad + 16 * i));
	size_t rest = aad_size % 16;
	if(rest)
	{
		uint8_t last[16] = { 0 };
		memcpy(last, aad + 16 * full, rest);
		x = ghash_step(x, h, vld1q_u8(last));
	}
	x = ghash_step(x, h, length_block((uint64_t)aad_size * 8, 0));

	vst1q_u8(tag, veorq_u8(ej0, vrbitq_u8(x)));
}

#else // no ARMv8 Crypto Extensions in this build

bool chiaki_aes_hw_supported(void) { return false; }
void chiaki_aes128_hw_expand_key(const uint8_t key[16], uint8_t round_keys[CHIAKI_AES128_ROUND_KEYS_SIZE]) { (void)key; (void)round_keys; }
void chiaki_aes128_hw_encrypt_blocks(const uint8_t round_keys[CHIAKI_AES128_ROUND_KEYS_SIZE], uint8_t *buf, size_t blocks) { (void)round_keys; (void)buf; (void)blocks; }
void chiaki_gmac128_hw(const uint8_t key[16], const uint8_t iv[16], const uint8_t *aad, size_t aad_size, uint8_t tag[16]) { (void)key; (void)iv; (void)aad; (void)aad_size; (void)tag; }

#endif
