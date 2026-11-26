import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, symlinkSync } from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { checkToolCall, checkBash, checkWritePath, checkReadPath } from '../src/guard.mjs'
import { createBoundedBashTool, resolveBashTimeoutSec } from '../src/safe-bash.mjs'

const ws = mkdtempSync(path.join(os.tmpdir(), 'guard-'))
mkdirSync(path.join(ws, 'src'))
mkdirSync(path.join(ws, 'node_modules', '.bin'), { recursive: true })
writeFileSync(path.join(ws, 'node_modules', '.bin', 'tsc'), '')
const outside = mkdtempSync(path.join(os.tmpdir(), 'outside-'))
symlinkSync(outside, path.join(ws, 'src', 'escape'))
const opts = { cwd: ws, projectKind: 'template' }

const blocked = (tool, input, o = opts) => assert.ok(checkToolCall(tool, input, o), `应拦截 ${tool} ${JSON.stringify(input)}`)
const allowed = (tool, input, o = opts) => assert.equal(checkToolCall(tool, input, o), null, `应放行 ${tool} ${JSON.stringify(input)}`)

test('写入路径', () => {
  allowed('write', { path: 'src/App.tsx', content: '' })
  allowed('edit', { path: path.join(ws, 'src/components/A.tsx'), edits: [] })
  allowed('write', { path: 'index.html', content: '' })
  allowed('write', { path: 'public/logo.svg', content: '' })
  allowed('write', { path: 'skill/SKILL.md', content: '' })
  allowed('write', { path: 'skill/scripts/run.py', content: '' })
  allowed('write', { path: 'docs/guide.md', content: '' })
  blocked('write', { path: 'vite.config.ts', content: '' })
  blocked('edit', { path: 'tsconfig.json', edits: [] })
  blocked('write', { path: 'README.md', content: '' })
  allowed('write', { path: 'README.md', content: '' }, { cwd: ws, projectKind: 'static' })
  blocked('write', { path: '../x.txt', content: '' })
  blocked('write', { path: '/etc/passwd', content: '' })
  blocked('write', { path: '~/x', content: '' })
  blocked('write', { path: 'node_modules/react/index.js', content: '' })
  blocked('write', { path: '.git/config', content: '' })
  blocked('write', { path: '.zhiye/dist-preview/index.html', content: '' })
  blocked('write', { path: '.pi/settings.json', content: '' })
  blocked('write', { path: '.env', content: '' })
  blocked('write', { path: '.env.local', content: '' })
  blocked('write', { path: 'package-lock.json', content: '' })
  blocked('edit', { path: 'pnpm-lock.yaml', edits: [] })
  blocked('write', { path: 'package.json', content: '' })
  allowed('write', { path: 'package.json', content: '' }, { cwd: ws, projectKind: 'vite' })
  blocked('write', { path: 'src/escape/evil.txt', content: '' }) // 符号链接逃逸
  blocked('write', { path: '', content: '' })
})

test('读取路径', () => {
  allowed('read', { path: 'src/App.tsx' })
  allowed('read', { path: 'node_modules/react/package.json' })
  blocked('read', { path: '/etc/hosts' })
  blocked('read', { path: '../../secret' })
  blocked('read', { path: '.env' })
  assert.ok(checkReadPath(ws, '~/.ssh/id_rsa'))
})

test('只开放四个工具', () => {
  blocked('grep', { pattern: 'x' })
  blocked('find', { pattern: 'x' })
  blocked('mcp__x', {})
})

test('bash 允许的命令', () => {
  for (const c of [
    'npx tsc --noEmit',
    'npx tsc --noEmit -p tsconfig.json 2>&1 | head -50',
    'ls -la src',
    'cat src/App.tsx',
    'grep -rn "useState" src',
    'find src -name "*.tsx"',
    'mkdir -p src/components',
    'rm src/old.tsx',
    'rm -rf src/components/Old',
    'git status && git diff',
    'npm run build',
    'npm ls react',
    'node -e "console.log(1)"',
    'echo hi > src/note.txt',
    'mkdir -p skill/scripts docs',
    'echo "# 文档" > docs/README.md',
    'cd src && ls',
    'wc -l src/*.tsx; echo done',
    'sed -n 1,20p src/App.tsx',
    "sed -i '' 's/a/b/' src/App.tsx",
  ]) assert.equal(checkBash(ws, c, { projectKind: 'template' }), null, c)
})

test('bash 拦截的命令', () => {
  for (const c of [
    'curl https://example.com',
    'wget http://x/y.js -O src/y.js',
    'ls && curl -s http://x',
    'echo $(curl http://x)',
    'npm install lodash',
    'npm i lodash',
    'npm ci',
    'npm add react-router',
    'pnpm add x',
    'yarn',
    'npx create-react-app foo',
    'npx -y cowsay hi',
    'npx some-pkg@1.0.0',
    'git clone https://github.com/x/y',
    'git push',
    'git pull',
    'git fetch',
    'git commit -m x',
    'git reset --hard',
    'sudo rm -rf /',
    'rm -rf /',
    'rm -rf ~',
    'rm -rf ..',
    'rm -rf ../other',
    'rm -rf .',
    'rm -rf node_modules',
    'rm -rf .git',
    'rm -rf /tmp/x',
    'vite',
    'npx vite',
    'npx vite preview',
    'npm run dev',
    'npm start',
    'npm run preview',
    'node node_modules/vite/bin/vite.js',
    'python3 -m http.server',
    'sleep 100 &',
    'nohup npm run dev',
    'npx tsc --watch',
    'echo x > /etc/hosts',
    'echo x > ../evil',
    'cat x > package.json',
    'cp /etc/passwd src/ && cp src/a ../b',
    'mv src/a.tsx ../a.tsx',
    'ln -s /etc src/etc2',
    'touch .env',
    'cd .. && ls',
    'cd /',
    'env FOO=1 curl http://x',
    'timeout 10 wget x',
    'ssh host',
    'pip install x',
  ]) assert.ok(checkBash(ws, c, { projectKind: 'template' }), `应拦截：${c}`)
})

test('拦截原因是中文', () => {
  const r = checkBash(ws, 'curl http://x', {})
  assert.match(r, /不允许/)
  assert.match(checkWritePath(ws, '../a', {}), /工作区以外/)
})

test('云服务规则与云函数：允许 cloud/policies.json、functions/*.ts，不允许借 functions/ 放别的东西', () => {
  const cwd = '/tmp/ws'
  assert.equal(checkWritePath(cwd, 'cloud/policies.json'), null)
  assert.equal(checkWritePath(cwd, 'functions/approve.ts'), null)
  assert.equal(checkWritePath(cwd, 'functions/zhiye-fn.d.ts'), null)
  assert.ok(checkWritePath(cwd, 'functions/sub/x.ts'))
  assert.ok(checkWritePath(cwd, 'functions/run.sh'))
  assert.ok(checkWritePath(cwd, 'cloud/server.js'))
})

test('bash 拦截全盘/越界搜索与遍历，放行工作区内合法搜索', () => {
  for (const c of [
    'grep -rln "TEMPLATE_NODE_MODULES" /',
    'grep -rn "foo" /usr',
    'grep -e "bar" /etc/hosts',
    'grep -f /etc/passwd src/App.tsx',
    'rg TEMPLATE /',
    'rg -n "foo" ..',
    'find / -name "*.jar"',
    'find .. -type f',
    'fd main /opt',
    'ls /',
    'ls -la ../other',
    'cat /etc/passwd',
    'head -n 20 /etc/hosts',
    'du -sh /',
    'tree /',
    'locate pom.xml',
  ]) {
    const r = checkBash(ws, c, { projectKind: 'service' })
    assert.ok(r, `应拦截全盘或越界命令：${c}`)
    assert.match(r, /不允许/)
  }

  for (const c of [
    'grep -rn "http://example.com/a/b" src',
    'grep -rln "TEMPLATE_NODE_MODULES" .',
    'grep -e "foo/bar" src/App.tsx',
    'cat src/App.tsx | grep "a/b"',
    'find src -maxdepth 2 -name "*.ts"',
    'ls -la src',
    'du -sh src',
  ]) {
    assert.equal(checkBash(ws, c, { projectKind: 'service' }), null, `应放行工作区内命令：${c}`)
  }
})

test('bounded bash 强制默认超时并杀死整个子进程树', async () => {
  assert.equal(resolveBashTimeoutSec(undefined), 120)
  assert.equal(resolveBashTimeoutSec(null), 120)
  assert.equal(resolveBashTimeoutSec(0), 120)
  assert.equal(resolveBashTimeoutSec(15), 15)
  assert.equal(resolveBashTimeoutSec(9999), 300)

  const tool = createBoundedBashTool(ws, { defaultTimeoutSec: 1, maxTimeoutSec: 5 })
  const pidFile = path.join(ws, 'grandchild.pid')
  const started = Date.now()
  await assert.rejects(
    () => tool.execute('call-1', { command: `sleep 30 & echo $! > "${pidFile}"; wait` }),
    /timed out after 1 seconds/i,
  )
  const elapsedMs = Date.now() - started
  assert.ok(elapsedMs < 6000, `命令应在超时后立即结束，实际耗时 ${elapsedMs}ms`)

  // 确认后台子进程（sleep 30）已随进程组被彻底终止，无残留孤儿进程
  await new Promise((r) => setTimeout(r, 150))
  const fs = await import('node:fs')
  if (fs.existsSync(pidFile)) {
    const childPid = Number(fs.readFileSync(pidFile, 'utf8').trim())
    if (Number.isInteger(childPid) && childPid > 0) {
      let alive = true
      try { process.kill(childPid, 0) } catch { alive = false }
      assert.equal(alive, false, `超时后子进程 pid=${childPid} 不应残留存活`)
    }
  }
})
