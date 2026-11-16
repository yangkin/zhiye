---
name: app-ai
description: 在应用里调用 AI：摘要、翻译、分类打标、从文字或图片（合同、发票、铭牌照片）里抽取字段、生成回复草稿。需求提到「AI 自动」「智能识别」「总结」「抽取」「OCR」「看图」时使用。
---
# 应用内 AI

平台替应用调用模型，模型地址和密钥都在服务端。预览和发布后的页面都能用。

## 封装

```ts
// src/lib/ai.ts
type Msg = { role: 'user' | 'assistant'; content: string };
type ChatInput = { system?: string; messages: Msg[]; images?: string[]; json?: boolean; maxTokens?: number };

declare global {
  interface Window { zhiye?: { ai?: { chat: (input: ChatInput | string) => Promise<string> } } }
}

export const aiReady = () => !!window.zhiye?.ai;

export async function askAi(input: ChatInput | string): Promise<string> {
  if (!window.zhiye?.ai) throw new Error('AI 只在织页的预览和发布页面里可用');
  return window.zhiye.ai.chat(input);
}

/** 让模型只返回 JSON，并做兜底解析（模型偶尔会包一层 ```json） */
export async function askJson<T>(system: string, content: string, images?: string[]): Promise<T> {
  const text = await askAi({ system: system + '\n只输出 JSON，不要任何解释。', messages: [{ role: 'user', content }], images, json: true });
  const m = text.match(/\{[\s\S]*\}|\[[\s\S]*\]/);
  if (!m) throw new Error('AI 没有返回可识别的结果，请重试');
  return JSON.parse(m[0]) as T;
}

/** 图片文件 → dataURL（先缩到最长边 1600px，控制在 4 MB 以内） */
export async function fileToDataUrl(file: File, max = 1600): Promise<string> {
  const bmp = await createImageBitmap(file);
  const scale = Math.min(1, max / Math.max(bmp.width, bmp.height));
  const canvas = document.createElement('canvas');
  canvas.width = Math.round(bmp.width * scale);
  canvas.height = Math.round(bmp.height * scale);
  canvas.getContext('2d')!.drawImage(bmp, 0, 0, canvas.width, canvas.height);
  return canvas.toDataURL('image/jpeg', 0.85);
}
```

## 写法要点

- **字段抽取**：system 里列出字段名、类型和含义，要求缺失时填 null；拿到结果后放进可编辑的表单，让用户确认后再保存（AI 会出错，不能直接入库）。
- **摘要 / 翻译**：长文先截取（单次最多约 6 万字），结果显示在可复制的区域。
- **分类打标**：在 system 里给出固定的候选标签，并要求只能从中选择；页面上校验返回值是否在候选里。
- **状态**：调用要几秒到几十秒——按钮显示「AI 处理中…」并禁用，失败时显示 `error.message`（中文）并允许重试。
- **频率**：每个应用每分钟最多 60 次；不要在输入框每次按键时调用，用按钮触发或防抖。
- 不要在代码里写模型名、接口地址或任何 key；不要用 fetch 直连外部模型。
- 需要保存 AI 结果时用云服务数据库（`window.zhiye.cloud`）。
