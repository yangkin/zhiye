import { test } from 'node:test'
import assert from 'node:assert/strict'
import { classifyError } from '../src/util.mjs'

test('TPM/RPM 每分钟限流单独归类，不误报额度用完', () => {
  assert.equal(classifyError('429: {"type":"TPM Ratelimit","message":"TPM (Tokens Per Minute) limit is exceeded for model: qwen3.8-flash"}'), 'PROVIDER_RATE_LIMIT')
  assert.equal(classifyError('429 RPM limit exceeded'), 'PROVIDER_RATE_LIMIT')
  assert.equal(classifyError('429: too many requests per minute'), 'PROVIDER_RATE_LIMIT')
})

test('额度类错误仍归 PROVIDER_QUOTA', () => {
  assert.equal(classifyError('429 usage limit exceeded'), 'PROVIDER_QUOTA')
  assert.equal(classifyError('402 Insufficient Balance'), 'PROVIDER_QUOTA')
  assert.equal(classifyError('exceeded_current_quota_error'), 'PROVIDER_QUOTA')
  assert.equal(classifyError('insufficient_quota: token budget'), 'BUDGET_EXCEEDED')
})

test('其他错误归 MODEL_ERROR', () => {
  assert.equal(classifyError('500 internal error'), 'MODEL_ERROR')
  assert.equal(classifyError('400: DeepSeek-V4-Flash-0731 is not a multimodal model'), 'MODEL_ERROR')
})
