// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

// AES-128 and GMAC with the ARMv8 Crypto Extensions (AESE/AESMC and PMULL). Internal to gkcrypt.c,
// which only uses them after chiaki_gkcrypt_hw_selftest() matched them against mbedtls.

#ifndef CHIAKI_AES_ARMV8_H
#define CHIAKI_AES_ARMV8_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define CHIAKI_AES128_ROUND_KEYS_SIZE (11 * 16)

/** true if this build has the code and the CPU has both AES and PMULL */
bool chiaki_aes_hw_supported(void);

void chiaki_aes128_hw_expand_key(const uint8_t key[16], uint8_t round_keys[CHIAKI_AES128_ROUND_KEYS_SIZE]);

/** AES-128-ECB encryption of whole 16 byte blocks, in place */
void chiaki_aes128_hw_encrypt_blocks(const uint8_t round_keys[CHIAKI_AES128_ROUND_KEYS_SIZE], uint8_t *buf, size_t blocks);

/** AES-128-GCM tag of aad alone (no plaintext) with a 16 byte iv: what mbedtls_gcm_crypt_and_tag gives */
void chiaki_gmac128_hw(const uint8_t key[16], const uint8_t iv[16], const uint8_t *aad, size_t aad_size, uint8_t tag[16]);

#endif // CHIAKI_AES_ARMV8_H
