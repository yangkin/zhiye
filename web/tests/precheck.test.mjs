import test from 'node:test';
import assert from 'node:assert/strict';
import { buildUpload, looksLikeProjectFile, precheck, shouldSkipPath } from '../src/import/precheck.ts';

function fakeFile(path, content = 'x', useWebkitPath = true) {
  const name = path.split('/').pop() || 'file';
  const f = new File([content], name, { type: 'text/plain' });
  if (useWebkitPath && path.includes('/')) {
    Object.defineProperty(f, 'webkitRelativePath', { value: path });
  }
  return f;
}

test('shouldSkipPath skips nested caches/VCS/OS files without skipping chosen root folder name', () => {
  assert.equal(shouldSkipPath('my-app/node_modules/pkg/index.js'), true);
  assert.equal(shouldSkipPath('my-app/.venv/lib/site.py'), true);
  assert.equal(shouldSkipPath('my-app/__pycache__/a.pyc'), true);
  assert.equal(shouldSkipPath('my-app/.next/cache/chunk.js'), true);
  assert.equal(shouldSkipPath('my-app/.gradle/caches/a.jar'), true);
  assert.equal(shouldSkipPath('my-app/.DS_Store'), true);
  assert.equal(shouldSkipPath('my-app/Thumbs.db'), true);
  assert.equal(shouldSkipPath('my-app/._index.html'), true);
  // Prebuilt dist/build outputs must NOT be skipped
  assert.equal(shouldSkipPath('my-app/dist/index.html'), false);
  assert.equal(shouldSkipPath('my-app/build/app.js'), false);
  // Root folder literally named venv must NOT wipe out its own source files
  assert.equal(shouldSkipPath('venv/app.py'), false);
  assert.equal(shouldSkipPath('venv/.venv/lib/x.py'), true);
});

test('looksLikeProjectFile recognizes Dockerfile, Gradle/Kotlin, pyproject, Rust, PHP, C#, C++, Svelte, etc.', () => {
  for (const p of [
    'svc/Dockerfile',
    'svc/Dockerfile.prod',
    'svc/docker-compose.yml',
    'svc/start.sh',
    'kt-app/build.gradle.kts',
    'kt-app/src/main/kotlin/Main.kt',
    'py-app/pyproject.toml',
    'rust-app/Cargo.toml',
    'rust-app/src/main.rs',
    'php-app/composer.json',
    'php-app/public/index.php',
    'dotnet-app/App.csproj',
    'dotnet-app/Program.cs',
    'cpp-app/CMakeLists.txt',
    'cpp-app/src/main.cpp',
    'svelte-app/src/App.svelte',
    'ts-app/vite.config.mts',
    'skill-pkg/SKILL.md',
    'app/zhiye.yaml',
  ]) {
    assert.equal(looksLikeProjectFile(p), true, `expected ${p} to be recognized`);
  }
  assert.equal(looksLikeProjectFile('photos/vacation.png'), false);
  assert.equal(looksLikeProjectFile('docs/report.pdf'), false);
});

test('precheck accepts Dockerfile-only, Kotlin Gradle, Rust, and noisy Python folders', async () => {
  const dockerRes = await precheck([
    fakeFile('my-docker/Dockerfile', 'FROM alpine:3.20\nEXPOSE 8080\n'),
    fakeFile('my-docker/run.sh', '#!/bin/sh\necho hi\n'),
  ]);
  assert.equal(dockerRes.format, 'folder');
  assert.equal(dockerRes.folderName, 'my-docker');
  assert.equal(dockerRes.files.length, 2);

  const kotlinRes = await precheck([
    fakeFile('kt-svc/build.gradle.kts', 'plugins { kotlin("jvm") }'),
    fakeFile('kt-svc/src/main/kotlin/App.kt', 'fun main() {}'),
    fakeFile('kt-svc/.gradle/cache.bin', 'junk'),
    fakeFile('kt-svc/.idea/workspace.xml', '<xml/>'),
  ]);
  assert.equal(kotlinRes.format, 'folder');
  assert.equal(kotlinRes.files.length, 2);

  const rustRes = await precheck([
    fakeFile('rs-api/Cargo.toml', '[package]\nname="api"'),
    fakeFile('rs-api/src/main.rs', 'fn main() {}'),
  ]);
  assert.equal(rustRes.format, 'folder');

  const pyRes = await precheck([
    fakeFile('py-proj/pyproject.toml', '[project]\nname="demo"'),
    fakeFile('py-proj/.venv/lib/site-packages/a.py', 'pass'),
    fakeFile('py-proj/__pycache__/m.pyc', 'bin'),
    fakeFile('py-proj/.DS_Store', ''),
  ]);
  assert.equal(pyRes.format, 'folder');
  assert.equal(pyRes.files.length, 1);

  const upload = await buildUpload(dockerRes);
  assert.equal(upload.fileName, 'my-docker.zip');
  assert.ok(upload.blob.size > 0);
});

test('precheck supports fromFolderPicker even if webkitRelativePath is omitted', async () => {
  const res = await precheck(
    [fakeFile('index.html', '<h1>ok</h1>', false), fakeFile('app.css', 'h1{}', false)],
    undefined,
    { fromFolderPicker: true },
  );
  assert.equal(res.format, 'folder');
  assert.equal(res.files.length, 2);
  const upload = await buildUpload(res);
  assert.equal(upload.fileName, 'project.zip');
});

test('precheck gives clear actionable messages for empty, cache-only, zip-in-folder, and media-only folders', async () => {
  await assert.rejects(
    () => precheck([], undefined, { fromFolderPicker: true }),
    /所选文件夹为空/,
  );
  await assert.rejects(
    () => precheck([fakeFile('empty/.DS_Store', ''), fakeFile('empty/.git/HEAD', 'ref')], undefined, { fromFolderPicker: true }),
    /仅包含缓存或系统隐藏文件/,
  );
  await assert.rejects(
    () => precheck([fakeFile('dl/project.zip', 'PK\x03\x04')]),
    /ZIP 压缩包.*选择文件/,
  );
  await assert.rejects(
    () => precheck([fakeFile('pics/a.png', 'png'), fakeFile('pics/b.pdf', 'pdf')]),
    /共 2 个文件，检测到 \.png、\.pdf/,
  );
});
