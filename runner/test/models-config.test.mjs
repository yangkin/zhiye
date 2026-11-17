#!/usr/bin/env node
// models.json 覆写测试。不需要真实模型和 API key。
//
//   writeModelsJson       → 只写 baseUrl 时不动已有内容；传 model 时整条覆盖
//   contextWindowOverride → 只改 contextWindow，reasoning / maxTokens / thinkingLevelMap 必须保留
//
// 为什么在意：models.json 里的自定义模型条目会**整体替换**内置定义。如果只写
// {id, contextWindow}，模型会退回默认值（maxTokens 16384、reasoning=false），
// 推理和思考档位一起丢掉，而且不会有任何报错。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { writeModelsJson, contextWindowOverride, modelDefFromEnv } from '../src/agent.mjs'

/** 与内置 deepseek-flash 同形状的最小定义 */
const builtin = {
  id: 'deepseek-flash',
  name: 'DeepSeek V4.1 Flash',
  provider: 'deepseek',
  api: 'openai-completions',
  baseUrl: 'https://api.deepseek.com',
  reasoning: true,
  thinkingLevelMap: { minimal: null, low: 'low', medium: null, high: 'high', max: 'max' },
  input: ['text', 'image'],
  cost: { input: 0.3, output: 1.2, cacheRead: 0.006, cacheWrite: 0 },
  contextWindow: 1000000,
  maxTokens: 384000,
}

function tempDir() {
  const dir = mkdtempSync(path.join(tmpdir(), 'zhiye-models-'))
  return { dir, done: () => rmSync(dir, { recursive: true, force: true }) }
}

test('只写 baseUrl：保留已有内容，不加 models', () => {
  const { dir, done } = tempDir()
  try {
    writeFileSync(path.join(dir, 'models.json'), JSON.stringify({ providers: { deepseek: { note: 'keep' } } }))
    writeModelsJson(dir, 'deepseek', 'http://gw/llm/v1')
    const cfg = JSON.parse(readFileSync(path.join(dir, 'models.json'), 'utf8'))
    assert.equal(cfg.providers.deepseek.baseUrl, 'http://gw/llm/v1')
    assert.equal(cfg.providers.deepseek.note, 'keep')
    assert.equal(cfg.providers.deepseek.models, undefined)
  } finally { done() }
})

test('传 model：整条写进 providers[provider].models', () => {
  const { dir, done } = tempDir()
  try {
    const def = contextWindowOverride(builtin, 256000)
    writeModelsJson(dir, 'deepseek', 'http://gw/llm/v1', def)
    const cfg = JSON.parse(readFileSync(path.join(dir, 'models.json'), 'utf8'))
    assert.equal(cfg.providers.deepseek.baseUrl, 'http://gw/llm/v1')
    assert.equal(cfg.providers.deepseek.models.length, 1)
    assert.equal(cfg.providers.deepseek.models[0].contextWindow, 256000)
  } finally { done() }
})

test('contextWindowOverride 只改窗口，其余字段原样保留', () => {
  const def = contextWindowOverride(builtin, 256000)
  assert.equal(def.contextWindow, 256000)
  assert.equal(def.maxTokens, 384000)
  assert.equal(def.reasoning, true)
  assert.deepEqual(def.thinkingLevelMap, builtin.thinkingLevelMap)
  assert.deepEqual(def.cost, builtin.cost)
  // provider / baseUrl 属于运行时字段，写进 models.json 会互相打架
  assert.equal(def.provider, undefined)
  assert.equal(def.baseUrl, undefined)
})

test('contextWindowOverride 不改动入参', () => {
  contextWindowOverride(builtin, 1)
  assert.equal(builtin.contextWindow, 1000000)
})

test('modelDefFromEnv：未配置返回 null', () => {
  assert.equal(modelDefFromEnv(''), null)
  assert.equal(modelDefFromEnv(undefined), null)
  assert.equal(modelDefFromEnv('   '), null)
})

test('modelDefFromEnv：解析整条定义并剥掉运行时字段', () => {
  const def = modelDefFromEnv(JSON.stringify({ ...builtin, baseUrl: 'http://gw/llm/v1' }))
  assert.equal(def.id, 'deepseek-flash')
  assert.equal(def.api, 'openai-completions')
  assert.equal(def.contextWindow, 1000000)
  assert.equal(def.baseUrl, undefined)
  assert.equal(def.provider, undefined)
})

test('modelDefFromEnv：坏 JSON / 缺字段直接抛错', () => {
  assert.throws(() => modelDefFromEnv('{not json'), /不是合法 JSON/)
  assert.throws(() => modelDefFromEnv('{"id":"x"}'), /id \/ api/)
})

test('modelDefFromEnv 的产出可以直接写进 models.json', () => {
  const { dir, done } = tempDir()
  try {
    writeModelsJson(dir, 'deepseek', 'http://gw/llm/v1', modelDefFromEnv(JSON.stringify(builtin)))
    const cfg = JSON.parse(readFileSync(path.join(dir, 'models.json'), 'utf8'))
    assert.equal(cfg.providers.deepseek.baseUrl, 'http://gw/llm/v1')
    assert.equal(cfg.providers.deepseek.models.length, 1)
    assert.equal(cfg.providers.deepseek.models[0].id, 'deepseek-flash')
    assert.equal(cfg.providers.deepseek.models[0].provider, undefined)
  } finally { done() }
})
