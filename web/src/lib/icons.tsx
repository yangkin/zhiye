import type { ReactElement } from 'react';

const paths = {
  spark: 'M12 3l2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5L12 3z',
  agent: 'M12 3v3M8 6h8a3 3 0 0 1 3 3v6a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3V9a3 3 0 0 1 3-3zM9.5 11h.01M14.5 11h.01M9.5 15h5M2 11v3M22 11v3',
  skill: 'M10 3h4v3a2 2 0 1 0 4 0V3h3v7h-3a2 2 0 1 0 0 4h3v7h-7v-3a2 2 0 1 0-4 0v3H3v-7h3a2 2 0 1 0 0-4H3V3h7z',
  grid: 'M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z',
  history: 'M3 11a9 9 0 1 1 2.5 7M3 4v7h7M12 7v5l3 2',
  plus: 'M12 5v14M5 12h14',
  arrow: 'M7 17L17 7M7 7h10v10',
  send: 'M12 19V5M5 12l7-7 7 7',
  chevron: 'M9 5l7 7-7 7',
  down: 'M6 9l6 6 6-6',
  search: 'M21 21l-5-5M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0',
  close: 'M6 6l12 12M6 18L18 6',
  star: 'M12 3l2.8 5.7 6.2.9-4.5 4.4 1.1 6.2-5.6-3-5.6 3 1.1-6.2L3 9.6l6.2-.9L12 3',
  web: 'M3 4h18v14H3zM8 21h8M12 18v3M3 8h18',
  phone: 'M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2M10 18h4',
  mini: 'M8 5a5 5 0 0 1 8 4c0 4-8 3-8 7a4 4 0 0 0 8 0M7 12a5 5 0 0 1 0-7',
  mic: 'M9 5a3 3 0 0 1 6 0v6a3 3 0 0 1-6 0V5M5 10v1a7 7 0 0 0 14 0v-1M12 18v4M8 22h8',
  clip: 'M8 13l7-7a3 3 0 0 1 4 4l-9 9a5 5 0 0 1-7-7l10-10',
  check: 'M5 12l4 4L19 6',
  more: 'M5 12h.01M12 12h.01M19 12h.01',
  refresh: 'M20 7v5h-5M4 17v-5h5M5.5 7a8 8 0 0 1 13-2L20 7M4 17l1.5 2a8 8 0 0 0 13-2',
  back: 'M19 12H5M11 6l-6 6 6 6',
  eye: 'M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0',
  folder: 'M3 6h7l2 3h9v11H3V6z',
  file: 'M5 2h9l5 5v15H5zM14 2v6h5M8 12h8M8 16h6',
  settings: 'M12 8a4 4 0 1 1 0 8 4 4 0 0 1 0-8M9 3h6l1 3 3 1 2 5-2 5-3 1-1 3H9l-1-3-3-1-2-5 2-5 3-1 1-3z',
  menu: 'M4 6h16M4 12h16M4 18h16',
  play: 'M8 4l12 8-12 8V4z',
  stop: 'M6 6h12v12H6z',
  cloud: 'M6 17a5 5 0 0 1-1-10 7 7 0 0 1 13-1 5 5 0 0 1 0 11H6',
  copy: 'M9 9h12v12H9zM3 15V3h12',
  edit: 'M15 3l6 6-11 11H4v-6L15 3',
  'collapse-left': 'M3 3h18v18H3zM10 3v18M17 9l-3 3 3 3',
  diff: 'M6 3v18M18 3v18M6 8h5M6 16h5M13 6l5 3-5 3M13 14l5 3-5 3',
  like: 'M7 21V10l4-7 1 1v6h6a2 2 0 0 1 2 2.3l-1.3 7A2 2 0 0 1 16.7 21H7zM7 21H3V10h4',
  dislike: 'M17 3v11l-4 7-1-1v-6H6a2 2 0 0 1-2-2.3l1.3-7A2 2 0 0 1 7.3 3H17zM17 3h4v11h-4',
  book: 'M5 3h14v19l-7-4-7 4V3z',
  image: 'M3 3h18v18H3zM3 16l6-6 5 5 3-3 4 4M15 7h.01',
  help: 'M9 9a3 3 0 1 1 5 2c-2 1-2 2-2 3M12 18h.01M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0',
  lock: 'M5 10h14v12H5zM8 10V6a4 4 0 0 1 8 0v4',
  undo: 'M3 9h10a7 7 0 0 1 0 14M3 9l6-6M3 9l6 6',
  redo: 'M21 9H11a7 7 0 0 0 0 14M21 9l-6-6M21 9l-6 6',
  alert: 'M12 3l10 18H2L12 3zM12 10v5M12 18h.01',
  // 工作台
  code: 'M8 7l-5 5 5 5M16 7l5 5-5 5M14 4l-4 16',
  preview: 'M3 4h18v16H3zM3 8h18M6 6h.01M9 6h.01',
  globe: 'M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0M2 12h20M12 2c5 5 5 15 0 20M12 2c-5 5-5 15 0 20',
  download: 'M12 3v12M7 10l5 5 5-5M4 14v7h16v-7',
  panel: 'M3 4h18v16H3zM9 4v16M15 9l-3 3 3 3',
  expand: 'M8 3H3v5M16 3h5v5M21 16v5h-5M3 16v5h5',
  contract: 'M3 8h5V3M16 3v5h5M21 16h-5v5M8 21v-5H3',
  external: 'M14 3h7v7M21 3L11 13M10 3H3v18h18v-7',
  cursor: 'M4 3l5 17 3-7 7-3L4 3M15 15l5 5',
  trash: 'M4 7h16M10 11v6M14 11v6M9 7V4h6v3M6 7l1 13h10l1-13',
  heart: 'M12 20s-7.5-4.6-7.5-10.1A4.4 4.4 0 0 1 12 7.3a4.4 4.4 0 0 1 7.5 2.6C19.5 15.4 12 20 12 20z',
  fork: 'M6 4.5a1.5 1.5 0 1 0 0 .01M18 4.5a1.5 1.5 0 1 0 0 .01M12 19.5a1.5 1.5 0 1 0 0 .01M6 6v2a4 4 0 0 0 4 4h4a4 4 0 0 0 4-4V6M12 12v6',
  share: 'M8.6 13.5l6.8 4M15.4 6.5l-6.8 4M18 3.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5zM6 9.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5zM18 15.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5z',
  community: 'M4 4h6.5v6.5H4zM13.5 4H20v6.5h-6.5zM4 13.5h6.5V20H4zM16.75 13.5a3.25 3.25 0 1 0 0 6.5 3.25 3.25 0 0 0 0-6.5z',
  database: 'M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3zM4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3',
} as const;

export type IconName = keyof typeof paths;

export function Icon({ name, className = '' }: { name: IconName; className?: string }): ReactElement {
  return (
    <svg
      className={`icon ${className}`.trim()}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.6}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d={paths[name]} />
    </svg>
  );
}

export const kindIcon = { 日常开发: 'code', 开发网站: 'web', 开发skill: 'skill', 技术文档: 'book' } as const;
