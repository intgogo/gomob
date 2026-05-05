/*******************************************************************
 * @file ocv_aes.h
 * Copyright (C) 2021 The Force AI
*******************************************************************/

#ifndef _OCV_AES_H_
#define _OCV_AES_H_

#include <stdint.h>
#include <stdlib.h>

#ifdef __cplusplus
extern "C" {
#endif

extern uint8_t ccv_aes[16];

uint8_t *aes_init(size_t key_size);

void aes_key_expansion(uint8_t *key, uint8_t *w);

void aes_inv_cipher(uint8_t *in, uint8_t *out, uint8_t *w);

void aes_cipher(uint8_t *in, uint8_t *out, uint8_t *w);

void aes_inv_cipher_custom(uint8_t *in, uint8_t *out, int size, uint8_t *w);

void aes_cipher_custom(uint8_t *in, uint8_t *out, int size, uint8_t *w);

#ifdef __cplusplus
}
#endif

#endif //_OCV_AES_H_