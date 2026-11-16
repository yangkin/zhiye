import { useEffect, useRef, useState } from 'react';

/** 打招呼（抬头、笑眼、挥手）播完再执行点击动作 */
const GREET_MS = 900;
type Pose = 'type' | 'peek' | 'blink' | 'tilt' | 'greet';

/**
 * 织页的小宠物「小织」：一只小机器人（素材在 public/pet/robot-*，原图在交接包「动图」目录）。
 * 平时在小电脑上打字（robot-type.webp 循环）；鼠标移上去停下来趴在输入框边缘，偶尔眨眼、歪头；
 * 点击时抬头笑眼挥手，再打开使用帮助。睡觉状态是闭眼帧 + zzz，悬停会睁眼。
 * 几个静帧（open / blink / smile）和动图同尺寸叠放，只切换显示哪一张，不会闪。「减少动态效果」时只显示静帧。
 */
export function Pet({ onClick, size = 120, bubble = '嗨～有问题就点我', sleeping = false }: { onClick?: () => void; size?: number; bubble?: string; sleeping?: boolean }) {
  const [hover, setHover] = useState(false);
  const [pose, setPose] = useState<Pose>('type');
  const greetTimer = useRef<number>(0);
  const hoverRef = useRef(false);

  // 悬停时：先趴好，之后每隔 1.6～3 秒随机眨一下眼或歪一下头
  useEffect(() => {
    if (!hover || pose === 'greet') return;
    if (pose === 'blink' || pose === 'tilt') {
      const t = window.setTimeout(() => setPose('peek'), pose === 'blink' ? 180 : 900);
      return () => clearTimeout(t);
    }
    const t = window.setTimeout(() => setPose(Math.random() < 0.55 ? 'blink' : 'tilt'), 1600 + Math.random() * 1400);
    return () => clearTimeout(t);
  }, [hover, pose]);
  useEffect(() => () => clearTimeout(greetTimer.current), []);

  const enter = () => {
    hoverRef.current = true;
    setHover(true);
    setPose((p) => (p === 'greet' ? p : 'peek'));
  };
  const leave = () => {
    hoverRef.current = false;
    setHover(false);
    setPose((p) => (p === 'greet' ? p : 'type'));
  };
  const greet = () => {
    clearTimeout(greetTimer.current);
    setPose('greet');
    greetTimer.current = window.setTimeout(() => {
      setPose(hoverRef.current ? 'peek' : 'type');
      onClick?.();
    }, onClick ? GREET_MS : GREET_MS + 300);
  };
  const shown = pose === 'greet' ? 'smile' : sleeping ? (hover ? 'open' : 'blink') : pose === 'blink' ? 'blink' : pose === 'type' ? 'type' : 'open';

  // 拖动：松手时只要落在中间的创造卡片区域（含边缘外扩）就吸回原位
  const HOME_MARGIN = 90;
  const [drag, setDrag] = useState({ dx: 0, dy: 0, active: false, snap: false });
  // 每次按下时记下指针起点和当前偏移，保证连续拖动是累加的
  const dragRef = useRef<{ x: number; y: number; bx: number; by: number; moved: boolean; id: number } | null>(null);
  const movedRef = useRef(false);

  const onPointerDown = (e: React.PointerEvent<HTMLButtonElement>) => {
    if (e.button !== 0) return;
    dragRef.current = { x: e.clientX, y: e.clientY, bx: drag.dx, by: drag.dy, moved: false, id: e.pointerId };
    movedRef.current = false;
    e.currentTarget.setPointerCapture(e.pointerId);
  };
  const onPointerMove = (e: React.PointerEvent<HTMLButtonElement>) => {
    const d = dragRef.current;
    if (!d) return;
    const dx = d.bx + (e.clientX - d.x);
    const dy = d.by + (e.clientY - d.y);
    if (!d.moved && Math.hypot(dx, dy) < 5) return;
    d.moved = true;
    movedRef.current = true;
    setDrag({ dx, dy, active: true, snap: false });
  };
  const endDrag = (e: React.PointerEvent<HTMLButtonElement>) => {
    const d = dragRef.current;
    dragRef.current = null;
    if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId);
    if (!d || !d.moved) return;
    // 松手时宠物中心落在创造卡片（含外扩 HOME_MARGIN）里 → 吸回原位
    const r = e.currentTarget.getBoundingClientRect();
    const cx = r.left + r.width / 2;
    const cy = r.top + r.height / 2;
    const zone = document.querySelector('.cp-card')?.getBoundingClientRect();
    const home =
      !zone ||
      (cx >= zone.left - HOME_MARGIN && cx <= zone.right + HOME_MARGIN && cy >= zone.top - HOME_MARGIN && cy <= zone.bottom + HOME_MARGIN);
    setDrag((cur) => (home ? { dx: 0, dy: 0, active: false, snap: true } : { ...cur, active: false }));
  };

  return (
    <button
      type="button"
      className={`pet pet-${pose} ${sleeping ? 'pet-sleeping' : ''} ${drag.active ? 'pet-dragging' : ''} ${drag.snap ? 'pet-snap' : ''}`}
      style={{ transform: `translate(${drag.dx}px, ${drag.dy}px)` }}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={endDrag}
      onPointerCancel={endDrag}
      onPointerEnter={(e) => e.pointerType === 'mouse' && enter()}
      onPointerLeave={(e) => e.pointerType === 'mouse' && leave()}
      onFocus={enter}
      onBlur={leave}
      onClick={(e) => {
        // 拖动结束时不要顺手打开帮助
        if (movedRef.current) {
          e.preventDefault();
          movedRef.current = false;
          return;
        }
        if (pose === 'greet') return;
        greet();
      }}
      aria-label={sleeping ? '小织在睡觉' : '小织：点我了解怎么使用织页（可以拖动）'}
      title="点我聊聊怎么用，也可以拖到别处"
    >
      <span className="pet-bubble" aria-hidden="true">{bubble}</span>
      <span className="pet-art" style={{ width: size }} data-show={shown}>
        <span className="pet-hand" aria-hidden="true" />
        <span className="pet-body">
          {sleeping ? null : <img className="pet-frame pet-f-type" src="/pet/robot-type.webp" alt="" draggable={false} />}
          <img className="pet-frame pet-f-open" src="/pet/robot-open.webp" alt="" draggable={false} />
          <img className="pet-frame pet-f-blink" src="/pet/robot-blink.webp" alt="" draggable={false} />
          <img className="pet-frame pet-f-smile" src="/pet/robot-smile.webp" alt="" draggable={false} />
        </span>
        {sleeping ? (
          <span className="pet-zzz" aria-hidden="true">
            <i>z</i>
            <i>z</i>
            <i>Z</i>
          </span>
        ) : (
          <span className="pet-spark" aria-hidden="true">✦</span>
        )}
      </span>
    </button>
  );
}
