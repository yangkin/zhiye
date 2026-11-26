import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { rewriteNpmTarballUrl, rewriteNpmTarballs } from '../src/npm-tarball-mirror.mjs'

const cdn = 'https://cdn.npmmirror.com/packages/'

test('公共 npm 普通包和 scoped 包转 CDN，私有源和 Git 不变', () => {
  assert.equal(
    rewriteNpmTarballUrl('https://registry.npmjs.org/yallist/-/yallist-3.1.1.tgz', cdn),
    'https://cdn.npmmirror.com/packages/yallist/3.1.1/yallist-3.1.1.tgz',
  )
  assert.equal(
    rewriteNpmTarballUrl('https://registry.npmmirror.com/@types/babel__template/-/babel__template-7.4.4.tgz', cdn),
    'https://cdn.npmmirror.com/packages/@types/babel__template/7.4.4/babel__template-7.4.4.tgz',
  )
  assert.equal(
    rewriteNpmTarballUrl('https://npm.csot.internal/private-lib/-/private-lib-1.0.0.tgz', cdn),
    'https://npm.csot.internal/private-lib/-/private-lib-1.0.0.tgz',
  )
  assert.equal(rewriteNpmTarballUrl('git+https://github.com/example/lib.git#abc', cdn), 'git+https://github.com/example/lib.git#abc')
})

test('锁文件转换保留 integrity', () => {
  const cwd = mkdtempSync(path.join(os.tmpdir(), 'zhiye-npm-cdn-'))
  const file = path.join(cwd, 'package-lock.json')
  writeFileSync(file, JSON.stringify({ packages: { 'node_modules/yallist': {
    resolved: 'https://registry.npmjs.org/yallist/-/yallist-3.1.1.tgz', integrity: 'sha512-original',
  } } }))
  assert.equal(rewriteNpmTarballs(cwd, cdn), 1)
  const lock = JSON.parse(readFileSync(file, 'utf8'))
  assert.equal(lock.packages['node_modules/yallist'].resolved, 'https://cdn.npmmirror.com/packages/yallist/3.1.1/yallist-3.1.1.tgz')
  assert.equal(lock.packages['node_modules/yallist'].integrity, 'sha512-original')
})
