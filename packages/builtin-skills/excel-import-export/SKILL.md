---
name: excel-import-export
description: 在网页里上传 Excel / CSV 解析成表格数据、校验，并导出为 Excel。需求里出现 Excel、表格导入、批量录入、导出报表、模板下载时使用。
---
# Excel / CSV 导入导出

## 读取（浏览器内解析，不上传服务器）
```tsx
import { readSheet } from 'read-excel-file/browser'
import Papa from 'papaparse'

async function parseFile(file: File): Promise<Record<string, unknown>[]> {
  if (/\.csv$/i.test(file.name)) {
    const text = await file.text()
    return Papa.parse<Record<string, string>>(text, { header: true, skipEmptyLines: true }).data
  }
  const rows = await readSheet(file)          // 二维数组：第一行是表头
  const [header, ...body] = rows
  return body.map((r) => Object.fromEntries(header.map((h, i) => [String(h).trim(), r[i] ?? null])))
}
```
- 只接受 `.xlsx` / `.csv`（`<input type="file" accept=".xlsx,.csv">`），`.xls` 老格式提示用户另存为 xlsx。
- 支持拖拽上传；解析时显示「正在解析…」，失败给出中文原因。
- 表头做一次映射：允许「设备编号 / 编号 / 设备号」等同义列名对应到同一字段，找不到必需列时列出缺少哪些列。

## 校验与预览
- 逐行校验（必填、数字、日期、枚举），错误行标红并在单元格旁显示原因；顶部汇总「共 N 行，M 行有问题」。
- 先预览前 50 行，用户确认后再「导入」；大文件（>5000 行）只预览不卡顿：分页显示。
- Excel 日期可能是 Date 对象或数字，统一用 dayjs 转成 `YYYY-MM-DD`。

## 导出
```tsx
import writeXlsxFile from 'write-excel-file/browser'

await writeXlsxFile(
  [[{ value: '编号', fontWeight: 'bold' }, { value: '名称', fontWeight: 'bold' }], ...rows.map((r) => [{ value: r.code }, { value: r.name }])],
).toFile(`设备清单-${dayjs().format('YYYYMMDD')}.xlsx`)
```
- 导出当前筛选结果，文件名带日期；提供「下载导入模板」按钮（只有表头和一行示例）。
