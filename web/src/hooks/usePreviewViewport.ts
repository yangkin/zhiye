import { useCallback, useEffect, useMemo, useState, type CSSProperties } from 'react';
import type { SelectOption } from '../components/Select';

export type PreviewViewportMode = 'auto' | '1280' | '1440' | '1920' | '2560' | 'native';

const DESKTOP_MIN_WIDTH = 1280;

const PRESET_WIDTHS: Partial<Record<PreviewViewportMode, number>> = {
  '1280': 1280,
  '1440': 1440,
  '1920': 1920,
  '2560': 2560,
};

export const PREVIEW_RESOLUTION_OPTIONS: SelectOption<PreviewViewportMode>[] = [
  { value: 'auto', label: '自适应', hint: '窄窗按 1280px 等比适配，宽屏 1:1 铺满' },
  { value: '1280', label: '1280px', hint: '小屏笔记本（1280 宽）' },
  { value: '1440', label: '1440px', hint: '主流笔记本（1440 宽）' },
  { value: '1920', label: '1920px', hint: '标准办公屏（1920×1080）' },
  { value: '2560', label: '2560px', hint: '2K 宽屏显示器（检查大屏留白）' },
  { value: 'native', label: '1:1 原生', hint: '不缩放，按当前预览窗实际宽度显示' },
];

export interface PreviewViewportState {
  /** 回调 ref：无论组件在 loading 后挂载还是切页签重新挂载，都能准确监听尺寸 */
  stageRef: (node: HTMLDivElement | null) => void;
  mode: PreviewViewportMode;
  setMode: (mode: PreviewViewportMode) => void;
  /** 实际缩放比例（1 表示 1:1 原生高清渲染） */
  scale: number;
  /** iframe 的逻辑视口宽度（px） */
  logicalWidth: number;
  /** 是否处于等比缩放状态 */
  isScaled: boolean;
  /** 是否在大屏上以 1:1 居中模拟更小的桌面分辨率 */
  isConstrained: boolean;
  /** 外层视口容器样式（用于占据缩放后的视觉尺寸） */
  shellStyle?: CSSProperties;
  /** iframe 自身样式 */
  frameStyle?: CSSProperties;
}

/**
 * 电脑多分辨率预览视口引擎：
 * - auto（默认）：当预览舞台 < 1280px（如 1280/1440 笔记本分栏预览）时，
 *   以 1280px 标准桌面宽度渲染并等比缩放贴合舞台，避免表格右截断与状态胶囊换行；
 *   当舞台 >= 1280px（1920 / 2K / 4K / 全屏预览）时，1:1 铺满舞台原生高清渲染。
 * - 1280 / 1440 / 1920 / 2560：模拟对应电脑显示器宽度；
 *   若当前舞台比目标更窄则等比缩放完整呈现，若更宽则 1:1 居中模拟该电脑宽度。
 * - native：始终按当前舞台真实宽度 1:1 不缩放渲染。
 */
export function usePreviewViewport(initial: PreviewViewportMode = 'auto', bypassOnNarrow = false): PreviewViewportState {
  const [stageEl, setStageEl] = useState<HTMLDivElement | null>(null);
  const stageRef = useCallback((node: HTMLDivElement | null) => {
    setStageEl(node);
  }, []);
  const [mode, setMode] = useState<PreviewViewportMode>(initial);
  const [stageSize, setStageSize] = useState({ width: 0, height: 0 });

  useEffect(() => {
    if (!stageEl) return;
    const update = () => {
      const cs = getComputedStyle(stageEl);
      const padX = (parseFloat(cs.paddingLeft) || 0) + (parseFloat(cs.paddingRight) || 0);
      const padY = (parseFloat(cs.paddingTop) || 0) + (parseFloat(cs.paddingBottom) || 0);
      const w = Math.max(0, Math.floor(stageEl.clientWidth - padX));
      const h = Math.max(0, Math.floor(stageEl.clientHeight - padY));
      setStageSize((prev) => (prev.width === w && prev.height === h ? prev : { width: w, height: h }));
    };
    update();
    if (typeof ResizeObserver === 'undefined') {
      window.addEventListener('resize', update);
      return () => window.removeEventListener('resize', update);
    }
    const ro = new ResizeObserver(update);
    ro.observe(stageEl);
    return () => ro.disconnect();
  }, [stageEl]);

  return useMemo(() => {
    const { width: stageW, height: stageH } = stageSize;
    const effectiveMode: PreviewViewportMode = bypassOnNarrow ? 'native' : mode;

    // 1:1 原生铺满
    if (effectiveMode === 'native' || stageW <= 0 || stageH <= 0) {
      return {
        stageRef,
        mode,
        setMode,
        scale: 1,
        logicalWidth: stageW || DESKTOP_MIN_WIDTH,
        isScaled: false,
        isConstrained: false,
      };
    }

    // 自适应桌面视口
    if (effectiveMode === 'auto') {
      if (stageW >= DESKTOP_MIN_WIDTH) {
        return {
          stageRef,
          mode,
          setMode,
          scale: 1,
          logicalWidth: stageW,
          isScaled: false,
          isConstrained: false,
        };
      }
      const scale = stageW / DESKTOP_MIN_WIDTH;
      const logicalHeight = Math.max(480, Math.ceil(stageH / scale));
      return {
        stageRef,
        mode,
        setMode,
        scale,
        logicalWidth: DESKTOP_MIN_WIDTH,
        isScaled: true,
        isConstrained: false,
        shellStyle: {
          width: `${stageW}px`,
          height: `${stageH}px`,
        },
        frameStyle: {
          width: `${DESKTOP_MIN_WIDTH}px`,
          height: `${logicalHeight}px`,
          transform: `scale(${scale})`,
          transformOrigin: '0 0',
        },
      };
    }

    // 指定电脑分辨率模拟（1280 / 1440 / 1920 / 2560）
    const targetW = PRESET_WIDTHS[effectiveMode] ?? DESKTOP_MIN_WIDTH;
    if (stageW < targetW) {
      const scale = stageW / targetW;
      const logicalHeight = Math.max(480, Math.ceil(stageH / scale));
      return {
        stageRef,
        mode,
        setMode,
        scale,
        logicalWidth: targetW,
        isScaled: true,
        isConstrained: false,
        shellStyle: {
          width: `${stageW}px`,
          height: `${stageH}px`,
        },
        frameStyle: {
          width: `${targetW}px`,
          height: `${logicalHeight}px`,
          transform: `scale(${scale})`,
          transformOrigin: '0 0',
        },
      };
    }

    // 舞台比目标电脑分辨率更宽（例如在 4K 屏上模拟 1280 / 1440 / 1920 笔记本）：1:1 居中展示
    return {
      stageRef,
      mode,
      setMode,
      scale: 1,
      logicalWidth: targetW,
      isScaled: false,
      isConstrained: true,
      shellStyle: {
        width: `${targetW}px`,
        height: `${stageH}px`,
      },
      frameStyle: {
        width: `${targetW}px`,
        height: `${stageH}px`,
      },
    };
  }, [stageRef, stageSize, mode, bypassOnNarrow]);
}
