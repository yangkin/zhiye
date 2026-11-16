import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, symlinkSync } from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { checkToolCall, checkBash, checkWritePath, checkReadPath } from '../src/guard.mjs'

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
