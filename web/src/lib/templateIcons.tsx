/**
 * 场景模板图标：24×24 线性图标，与 icons.tsx 风格一致（描边 1.6，圆角端点）。
 * 每个模板一个，键为模板 id。
 */
export const TEMPLATE_ICONS: Record<string, string> = {
  // 日常开发
  'yield-daily': 'M4 20h16M7 17v-4M12 17V9M17 17v-6M5 9.5l4-3 4 3 6-5M16 4.5h3v3',
  'equipment-check': 'M9 3h6v3H9zM7 4.5H5V21h14V4.5h-2M8.5 13.5l2.2 2.2 4.8-4.8',
  'shift-hours': 'M12 21a9 9 0 1 1 0-18 9 9 0 0 1 0 18zM12 7v5l3 2M17.5 3.5a3 3 0 0 0 3 3',
  'material-code': 'M3 5v14M6 5v14M9.5 5v14M12 5v14M15.5 5v14M18 5v14M21 5v14',
  'spc-chart': 'M3 6h18M3 18h18M3 12h2l2-3 3 5 3-6 3 4 2-2h3',
  'esd-check': 'M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6zM13 7l-4 6h3l-1 4 4-6h-3z',
  'spare-parts': 'M12 2.5l8.5 4.8v9.4L12 21.5l-8.5-4.8V7.3zM3.5 7.3L12 12l8.5-4.7M12 12v9.5',
  'oee-calc': 'M4 18a8 8 0 1 1 16 0M12 18l4.5-5.5M2.5 18h19M6.5 13.5l1 .6M17.5 9l-.8.8M12 9.5V10.6',
  'meeting-room': 'M3 5h14v14H3zM3 9h14M7 3v4M13 3v4M18.5 14.5a3 3 0 1 1 0 6 3 3 0 0 1 0-6zM18.5 16v1.6l1.1.7',
  'expense-calc': 'M6 3h12v18l-3-2-3 2-3-2-3 2zM9 7.5l3 3 3-3M12 10.5V16M9.5 12.5h5M9.5 15h5',
  // 日常开发：数据看板
  'dash-cockpit': 'M4 17a8 8 0 1 1 16 0M12 17l3.5-4.5M3 21h18M7 13l.8.5M17 13l-.8.5M12 9v1',
  'dash-yield-loss': 'M3 4v16h18M6 6h3v3H6zM9 9h3v3H9zM12 12h3v3h-3zM15 15h3v2h-3z',
  'dash-equipment-wall': 'M3 4h18v12H3zM8 20h8M12 16v4M6 7h3v3H6zM10.5 7h3v3h-3zM15 7h3v3h-3zM6 11.5h3v1.5H6zM10.5 11.5h3v1.5h-3z',
  'dash-delivery': 'M3 7h11v9H3zM14 10h4l3 3v3h-7zM7 19a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM17 19a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM6 11.5h3',
  'dash-inventory': 'M3 21V8l9-5 9 5v13M7 21v-4h4v4M13 21v-7h4v7M7 13h4v4',
  'dash-energy': 'M13 2.5L5 13.5h6l-1 8 8-11h-6zM19 4.5c1.5 1 2.5 2.6 2.5 4.5',
  'dash-rd-portfolio': 'M3 20h18M3 20V4M8 15a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM15 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7zM16.5 18a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3z',
  'dash-workforce': 'M8 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM2.5 20c0-3 2.5-5.5 5.5-5.5s5.5 2.5 5.5 5.5M15 20v-4M18 20v-7M21 20V9',
  'dash-personal': 'M4 3h10l4 4v5M14 3v4h4M4 3v18h7M7 11h6M7 15h3M14 21v-3M17 21v-6M20 21v-4',
  // 开发网站：内部业务系统
  'repair-order': 'M14.5 6.5a4 4 0 0 0-5.4 5.1L3.5 17.2l3.3 3.3 5.6-5.6a4 4 0 0 0 5.1-5.4l-2.4 2.4-2.6-.6-.6-2.6z',
  'quality-8d': 'M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6zM8.5 12l2.5 2.5 4.5-4.5',
  'ecn-approval': 'M6 3h9l4 4v14H6zM15 3v4h4M9 12h7M9 16h4M14.5 17.5l1.5 1.5 3-3',
  'iqc-inspection': 'M3 7l9-4 9 4v10l-9 4-9-4zM3 7l9 4 9-4M12 11v10M15.5 14.5l1.5 1.5 2.5-2.5',
  'spare-inventory': 'M3 21V9l9-5 9 5v12M7 21v-7h10v7M7 17.5h10',
  'ehs-hazard': 'M12 3.5l9 15.5H3zM12 10v4M12 16.8v.2',
  'project-tracker': 'M4 4h4v16H4zM10 4h4v10h-4zM16 4h4v6h-4z',
  'line-downtime': 'M12 21a9 9 0 1 1 0-18 9 9 0 0 1 0 18zM12 7v5l3 2M5 5l14 14',
  'training-exam': 'M2 9l10-5 10 5-10 5zM6 11v5c3 2 9 2 12 0v-5M9 9.5l2 2 3.5-3.5',
  'it-asset': 'M3 5h18v11H3zM8 20h8M12 16v4M7 9h4M7 12h7',
  // 开发skill
  'skill-8d': 'M9 3h6v3H9zM7 4.5H5V21h14V4.5h-2M8 11h3M8 15.5h3M13.5 11l1.4 1.4 2.6-2.6M13.5 15.5h3.5',
  'skill-sql': 'M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3zM4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3',
  'skill-weekly': 'M4 5h16v16H4zM4 9h16M9 3v4M15 3v4M8 13h8M8 17h5',
  'skill-code-review': 'M8 8l-4 4 4 4M16 8l4 4-4 4M10 12.5l1.5 1.5 3-3',
  'skill-fmea': 'M12 3.5l9 15.5H3zM12 10v4M12 16.8v.2',
  'skill-ecn-doc': 'M4 7h13l-3-3M20 17H7l3 3M4 7v4M20 17v-4',
  'skill-interview': 'M3 5h12v8H8.5L5 16v-3H3zM9 16v1h6.5l3.5 3v-3h2V9h-4',
  'skill-meeting': 'M9 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM3 20c0-3.3 2.7-6 6-6s6 2.7 6 6M16.5 10.5a2.5 2.5 0 1 0 0-5M18 14c2 .5 3.5 2.5 3.5 5',
  'skill-log-analysis': 'M3 5h10M3 9h7M3 13h5M15.5 19a4.5 4.5 0 1 0 0-9 4.5 4.5 0 0 0 0 9zM19 18.5l2.5 2.5',
  'skill-ppt-outline': 'M3 4h18v12H3zM12 16v4M8 20h8M7.5 12.5V10M11 12.5V7.5M14.5 12.5V9',
  // 技术文档
  'doc-mes-api': 'M9 2.5v4.5M15 2.5v4.5M7 7h10v4a5 5 0 0 1-10 0zM12 16v5.5',
  'doc-onboarding': 'M5 5h14v10H5zM2 19h20M10 9a2 2 0 1 0 4 0 2 2 0 0 0-4 0M8.5 14c.5-1.4 1.8-2.2 3.5-2.2s3 .8 3.5 2.2',
  'doc-eap': 'M8 8h8v8H8zM10 3v3M14 3v3M10 18v3M14 18v3M3 10h3M3 14h3M18 10h3M18 14h3M10.5 10.5h3v3h-3z',
  'doc-readme': 'M3 4h18v16H3zM7 9l3 3-3 3M12 15h5',
  'doc-process': 'M12 3l9 5-9 5-9-5zM3 12.5l9 5 9-5M3 16.5l9 5 9-5',
  'doc-cleanroom': 'M3 8h11a3 3 0 1 0-3-3M3 12h15a3 3 0 1 1-3 3M3 16h7',
  'doc-api-gateway': 'M10 5a2 2 0 1 0 4 0 2 2 0 0 0-4 0M3 19a2 2 0 1 0 4 0 2 2 0 0 0-4 0M17 19a2 2 0 1 0 4 0 2 2 0 0 0-4 0M12 7v5M12 12l-6.3 5.6M12 12l6.3 5.6',
  'doc-hr-handbook': 'M5 3h12a2 2 0 0 1 2 2v16H7a2 2 0 0 1-2-2zM5 19a2 2 0 0 1 2-2h12M12 11a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5zM8.5 15c.6-1.4 1.9-2.2 3.5-2.2s2.9.8 3.5 2.2',
  'doc-incident': 'M7 18v-6a5 5 0 0 1 10 0v6M4 18h16v3H4zM12 3v2M4.5 6.5L6 8M19.5 6.5L18 8M12 11v3',
  'doc-equipment-manual': 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M5.3 18.7l2.1-2.1M16.6 7.4l2.1-2.1',
  // 全栈应用
  'fs-yield-8d': 'M3 4h18v16H3zM3 9h18M7 16l3-3 3 2 4-4',
  'fs-equip-spare': 'M4 5h16v6H4zM4 13h7v6H4zM13 13h7v6h-7zM7.5 8h4',
  'fs-rd-tracker': 'M6 4v16M6 6h12v4H6zM6 13h8v4H6zM16.5 15l1.8 1.8 3.2-3.2',
  'fs-customer-complaint': 'M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6zM12 8v4.5M12 15.5v.5',
  'fs-doc-report-hub': 'M3 5h18l-7 7v6l-4 2v-8z',
  'fs-meeting-action': 'M4 6.5l1.5 1.5L8.5 5M12 6.5h8M4 12.5l1.5 1.5L8.5 11M12 12.5h8M4 18.5h4.5M12 18.5h8',
  // 企业生态联动
  'eco-agw-feishu-alert': 'M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9M10.3 21a2 2 0 0 0 3.4 0',
  'eco-feishu-bitable-inspect': 'M3 5h18v14H3zM3 10h18M9 10v9M13 13.5h5l-1.5-1.5M18 16.5h-5l1.5 1.5',
  'eco-feishu-approval-hub': 'M20 12a8 8 0 1 1-4.5-7.2M9 11.5l2.5 2.5L21 4.5',
  'eco-meeting-feishu-ai': 'M4 5h16v11H9l-5 4zM12 7.5l1 2.2 2.2 1-2.2 1-1 2.2-1-2.2-2.2-1 2.2-1z',
  'eco-weekly-report-bot': 'M4 10v4h3l7 4V6l-7 4zM7 14l1 5h2.5l-.8-5M17.5 9a3.5 3.5 0 0 1 0 6',
  'eco-spare-feishu-dispatch': 'M3 7l9-4 9 4v6M3 7l9 4 9-4M3 7v10l9 4 2-.9M12 11v10M18 14v3M18 19.5v.5',
};

export function TemplateIcon({ id, size = 18 }: { id: string; size?: number }) {
  const d = TEMPLATE_ICONS[id] ?? 'M12 3l2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5L12 3z';
  return (
    <svg className="icon" width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={d} />
    </svg>
  );
}
