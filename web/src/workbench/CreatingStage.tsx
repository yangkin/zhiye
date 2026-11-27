import { useEffect, useState } from 'react';

const TIPS: { t: string; only?: 'skill' | 'web' }[] = [
  { t: '生成中可以随时在左下角输入框补充要求，会一起处理。' },
  { t: '点开左边的步骤，能看到每个文件写了什么、命令输出了什么。' },
  { t: '「代码」页会实时显示正在写的文件，不用等生成完。' },
  { t: '每次生成或修改都会存成一个版本，随时可以回到任意一版。' },
  { t: '「手动微调」完全手工改样式，不经过 AI，改完点「应用」就会保存。', only: 'web' },
  { t: '想只看页面？预览右上角的「全屏」可以把右侧撑满。', only: 'web' },
  { t: '技能包生成完可以下载，也可以直接发布到 SkillHub。', only: 'skill' },
];

/**
 * 生成中的占位：小机器人对着电脑敲代码（public/pet/robot-code.webp，原图在交接包「动图」目录的「萌趣机器人编程敲击动画」），
 * 下面轮流滚动手册式的小提示（对照 meoo 的「正在启动工作区…」动效）。
 */
export function CreatingStage({ skill = false, cloning = false }: { label?: string; skill?: boolean; cloning?: boolean }) {
  const [i, setI] = useState(0);
  const tips = TIPS.filter((x) => !x.only || x.only === (skill ? 'skill' : 'web')).map((x) => x.t);
  useEffect(() => {
    const t = setInterval(() => setI((n) => (n + 1) % tips.length), 4600);
    return () => clearInterval(t);
  }, [tips.length]);

  return (
    <div className="creating-stage" aria-live="polite">
      <div className="cs-scene" aria-hidden="true">
        <picture className="cs-pet">
          <source srcSet="/pet/robot-code-still.webp" media="(prefers-reduced-motion: reduce)" />
          <img src="/pet/robot-code.webp" alt="" width={200} height={156} draggable={false} />
        </picture>
        <span className="cs-pet-spark cs-s1">✦</span>
        <span className="cs-pet-spark cs-s2">✦</span>
      </div>
      <h3>
        {cloning ? '正在复刻模板' : skill ? '正在编写技能包' : '正在创作中'}
        <span className="dots">…</span>
      </h3>
      <ul className="cs-tips" aria-label="使用小贴士">
        {tips.map((t, n) => (
          <li key={t} className={n === i ? 'on' : ''} aria-hidden={n === i ? undefined : true}>
            {t}
          </li>
        ))}
      </ul>
    </div>
  );
}