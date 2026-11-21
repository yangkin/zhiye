import { useCallback, useEffect, useMemo, useState, type CSSProperties } from 'react';

export type PreviewViewportMode = 'auto' | 'native' | 'tablet' | 'mobile';

const DESKTOP_MIN_WIDTH = 1280;
const TABLET_WIDTH = 768;
const MOBILE_WIDTH = 390;
const MOBILE_MAX_HEIGHT = 844;
const DEVICE_PADDING = 28;

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
  /** 是否处于居中设备画框模式（平板 / 手机） */
  isDeviceFrame: boolean;
  /** 外层视口容器样式（用于占据缩放后的视觉尺寸） */
  shellStyle?: CSSProperties;
  /** iframe 自身样式 */
  frameStyle?: CSSProperties;
}

/**
 * 预览视口自适应引擎：
 * - auto（默认）：当舞台宽度 < 1280px（如 1280/1440 笔记本分栏预览）时，
 *   以 1280px 标准桌面宽度渲染并等比缩放贴合舞台，彻底消除表格右截断与状态胶囊换行；
 *   当舞台宽度 >= 1280px（1920 / 2K / 4K / 全屏预览）时，1:1 铺满舞台原生渲染。
 * - native：始终按当前舞台物理尺寸 1:1 渲染，方便实测响应式断点。
 * - tablet：768px 居中平板画框（舞台偏窄时自动等比缩小防溢出）。
 * - mobile：390px 居中手机画框（舞台偏窄/偏矮时自动等比缩小防溢出）。
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
        isDeviceFrame: false,
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
          isDeviceFrame: false,
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
        isDeviceFrame: false,
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

    // 平板 / 手机居中设备画框
    const availW = Math.max(240, stageW - DEVICE_PADDING * 2);
    const availH = Math.max(320, stageH - DEVICE_PADDING * 2);
    const targetW = effectiveMode === 'tablet' ? TABLET_WIDTH : MOBILE_WIDTH;
    const scaleW = Math.min(1, availW / targetW);
    const targetH =
      effectiveMode === 'mobile'
        ? Math.min(MOBILE_MAX_HEIGHT, Math.max(667, Math.floor(availH / scaleW)))
        : Math.max(640, Math.floor(availH / scaleW));
    const scaleH = Math.min(1, availH / targetH);
    const scale = Math.min(scaleW, scaleH);
    const visualW = Math.round(targetW * scale);
    const visualH = Math.round(targetH * scale);
    const isScaled = scale < 0.995;

    return {
      stageRef,
      mode,
      setMode,
      scale,
      logicalWidth: targetW,
      isScaled,
      isDeviceFrame: true,
      shellStyle: {
        width: `${visualW}px`,
        height: `${visualH}px`,
      },
      frameStyle: isScaled
        ? {
            width: `${targetW}px`,
            height: `${targetH}px`,
            transform: `scale(${scale})`,
            transformOrigin: '0 0',
          }
        : {
            width: `${targetW}px`,
            height: `${targetH}px`,
          },
    };
  }, [stageRef, stageSize, mode, bypassOnNarrow]);
}
