/**
 * 最小限のxlsx書き出し（依存パッケージなし）
 *
 * xlsx = 以下のXMLをZIP（lib/zip.js）で固めたもの:
 *   [Content_Types].xml        … 各パートの種類の宣言
 *   _rels/.rels                … ルートからブックへの参照
 *   xl/workbook.xml            … シート一覧
 *   xl/_rels/workbook.xml.rels … ブックからシート/スタイルへの参照
 *   xl/styles.xml              … フォントや塗りつぶし（判定の色分けに使う）
 *   xl/worksheets/sheet1.xml   … セルの中身
 *
 * 文字列は sharedStrings.xml を使わず inlineStr で直接埋め込む。
 * 数百行程度ならサイズ差は無視できて、コードがずっと単純になる。
 */

import { createZip } from './zip.js';
import {
  EXPORT_COLUMNS,
  JUDGEMENTS,
  compareRecords,
  judge,
  toExportRow,
} from '../shared/commute.js';

const HEADER_FILL = 'FFDDEBF7';

/** XMLに入れられない制御文字（タブ・改行以外）を落とすためのパターン */
const CONTROL_CHARS = new RegExp('[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]', 'g');

const escapeXml = (value) =>
  String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;')
    .replace(CONTROL_CHARS, '');

/** 0 -> "A", 25 -> "Z", 26 -> "AA" */
export function columnLetter(index) {
  let n = index + 1;
  let letters = '';
  while (n > 0) {
    const rest = (n - 1) % 26;
    letters = String.fromCharCode(65 + rest) + letters;
    n = Math.floor((n - 1) / 26);
  }
  return letters;
}

/**
 * 汎用のxlsx生成。
 * @param {object} options
 * @param {string} options.sheetName
 * @param {{label:string,width?:number,type?:'string'|'number'}[]} options.columns
 * @param {{values:any[], fill?:string|null}[]} options.rows  fillはARGB文字列（例 'FFC6EFCE'）
 * @returns {Buffer}
 */
export function buildXlsx({ sheetName = 'Sheet1', columns, rows }) {
  // 使われている塗りつぶし色を集めてスタイル番号を割り当てる
  const fillColors = [HEADER_FILL];
  for (const row of rows) {
    if (row.fill && !fillColors.includes(row.fill)) fillColors.push(row.fill);
  }
  // cellXfs: 0=通常, 1=見出し, 2以降=各塗りつぶし
  const styleIndexOfFill = new Map(fillColors.map((color, i) => [color, i + 1]));

  const cols = columns
    .map(
      (col, i) =>
        `<col min="${i + 1}" max="${i + 1}" width="${col.width ?? 12}" customWidth="1"/>`,
    )
    .join('');

  const cell = (rowNumber, colIndex, value, type, styleIndex) => {
    const ref = `${columnLetter(colIndex)}${rowNumber}`;
    const style = styleIndex ? ` s="${styleIndex}"` : '';
    if (type === 'number' && value !== '' && value !== null && Number.isFinite(Number(value))) {
      return `<c r="${ref}"${style}><v>${Number(value)}</v></c>`;
    }
    if (value === '' || value === null || value === undefined) return `<c r="${ref}"${style}/>`;
    return `<c r="${ref}"${style} t="inlineStr"><is><t xml:space="preserve">${escapeXml(value)}</t></is></c>`;
  };

  const headerRow = `<row r="1">${columns
    .map((col, i) => cell(1, i, col.label, 'string', styleIndexOfFill.get(HEADER_FILL)))
    .join('')}</row>`;

  const bodyRows = rows
    .map((row, rowIndex) => {
      const rowNumber = rowIndex + 2;
      const styleIndex = row.fill ? styleIndexOfFill.get(row.fill) : 0;
      const cells = columns
        .map((col, i) => cell(rowNumber, i, row.values[i], col.type ?? 'string', styleIndex))
        .join('');
      return `<row r="${rowNumber}">${cells}</row>`;
    })
    .join('');

  const lastColumn = columnLetter(columns.length - 1);
  const lastRow = rows.length + 1;

  const sheet = `${XML_HEAD}
<worksheet xmlns="${NS_MAIN}" xmlns:r="${NS_REL}">
<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>
<sheetFormatPr defaultRowHeight="18"/>
<cols>${cols}</cols>
<sheetData>${headerRow}${bodyRows}</sheetData>
<autoFilter ref="A1:${lastColumn}${lastRow}"/>
</worksheet>`;

  return createZip([
    { name: '[Content_Types].xml', data: CONTENT_TYPES },
    { name: '_rels/.rels', data: ROOT_RELS },
    { name: 'xl/workbook.xml', data: workbookXml(sheetName) },
    { name: 'xl/_rels/workbook.xml.rels', data: WORKBOOK_RELS },
    { name: 'xl/styles.xml', data: stylesXml(fillColors) },
    { name: 'xl/worksheets/sheet1.xml', data: sheet },
  ]);
}

/**
 * 記録の配列から通勤記録シートを作る（アプリ本体が呼ぶのはこちら）。
 * 列の定義と値の作り方は shared/commute.js に置いてあるので、
 * 「画面に出ている値」と「xlsxの値」が必ず一致する。
 *
 * 注意: GPSの座標そのものは出力しない（自宅の位置が分かるファイルを
 * うっかり共有してしまわないため）。点数と距離だけを載せる。
 */
export function buildRecordsXlsx(records) {
  const sorted = [...records].sort(compareRecords);
  const rows = sorted.map((record) => {
    const row = toExportRow(record);
    const result = judge(record);
    return {
      values: EXPORT_COLUMNS.map((col) => row[col.key]),
      fill: result ? result.argb : null,
    };
  });
  return buildXlsx({ sheetName: '通勤記録', columns: EXPORT_COLUMNS, rows });
}

/** 判定の色をxlsxでも使うことを明示しておく（テストから参照） */
export const JUDGEMENT_FILLS = JUDGEMENTS.map((j) => j.argb);

// ---------------------------------------------------------------------------
// 固定のXMLパーツ
// ---------------------------------------------------------------------------

const XML_HEAD = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>';
const NS_MAIN = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main';
const NS_REL = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships';

const CONTENT_TYPES = `${XML_HEAD}
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>`;

const ROOT_RELS = `${XML_HEAD}
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="${NS_REL}/officeDocument" Target="xl/workbook.xml"/>
</Relationships>`;

const WORKBOOK_RELS = `${XML_HEAD}
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="${NS_REL}/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="${NS_REL}/styles" Target="styles.xml"/>
</Relationships>`;

const workbookXml = (sheetName) => `${XML_HEAD}
<workbook xmlns="${NS_MAIN}" xmlns:r="${NS_REL}">
<sheets><sheet name="${escapeXml(sheetName).slice(0, 31)}" sheetId="1" r:id="rId1"/></sheets>
</workbook>`;

/**
 * fills の 0番と1番は仕様上「none」「gray125」で予約されているので、
 * 実際に使う色は2番以降に並べる。cellXfs のスタイル番号もそれに合わせる。
 */
const stylesXml = (fillColors) => `${XML_HEAD}
<styleSheet xmlns="${NS_MAIN}">
<fonts count="2">
<font><sz val="11"/><color theme="1"/><name val="Calibri"/></font>
<font><b/><sz val="11"/><color theme="1"/><name val="Calibri"/></font>
</fonts>
<fills count="${fillColors.length + 2}">
<fill><patternFill patternType="none"/></fill>
<fill><patternFill patternType="gray125"/></fill>
${fillColors
  .map(
    (argb) =>
      `<fill><patternFill patternType="solid"><fgColor rgb="${argb}"/><bgColor indexed="64"/></patternFill></fill>`,
  )
  .join('\n')}
</fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="${fillColors.length + 1}">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
${fillColors
  .map(
    (argb, i) =>
      `<xf numFmtId="0" fontId="${i === 0 ? 1 : 0}" fillId="${i + 2}" borderId="0" xfId="0" applyFont="1" applyFill="1"/>`,
  )
  .join('\n')}
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>`;
