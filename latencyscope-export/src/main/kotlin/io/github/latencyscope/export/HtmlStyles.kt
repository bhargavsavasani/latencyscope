package io.github.latencyscope.export

/**
 * Inline stylesheet for the HTML report.
 *
 * Colours are declared once as custom properties and referenced by role, so
 * the dark steps swap in one place. The dark values are *selected* for the
 * dark surface, not an automatic inversion, and are declared under both the
 * media query and the `data-theme` scope so the in-page toggle wins either
 * way.
 *
 * The five phase hues are the first five categorical slots, in fixed order,
 * validated for colour-vision separation against both surfaces.
 */
internal const val REPORT_CSS: String = """
:root {
  color-scheme: light;
  --surface-0: #f4f4f1;
  --surface-1: #fcfcfb;
  --surface-2: #efeeea;
  --border: #dcdbd5;
  --border-strong: #c3c2b7;
  --text-primary: #0b0b0b;
  --text-secondary: #52514e;
  --text-muted: #77756e;
  --series-1: #2a78d6;
  --series-2: #eb6834;
  --series-3: #1baf7a;
  --series-4: #eda100;
  --series-5: #e87ba4;
  --status-good: #0ca30c;
  --status-warning: #fab219;
  --status-critical: #d03b3b;
  --status-good-text: #006300;
  --status-warning-text: #8a5a00;
  --status-critical-text: #a52222;
  --bar-track: #e6e5e0;
  --accent: #2a78d6;
}

@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) {
    color-scheme: dark;
    --surface-0: #121211;
    --surface-1: #1a1a19;
    --surface-2: #232321;
    --border: #383835;
    --border-strong: #52514e;
    --text-primary: #ffffff;
    --text-secondary: #c3c2b7;
    --text-muted: #96948b;
    --series-1: #3987e5;
    --series-2: #d95926;
    --series-3: #199e70;
    --series-4: #c98500;
    --series-5: #d55181;
    --status-good-text: #0ca30c;
    --status-warning-text: #fab219;
    --status-critical-text: #e66767;
    --bar-track: #2c2c2a;
    --accent: #3987e5;
  }
}

:root[data-theme="dark"] {
  color-scheme: dark;
  --surface-0: #121211;
  --surface-1: #1a1a19;
  --surface-2: #232321;
  --border: #383835;
  --border-strong: #52514e;
  --text-primary: #ffffff;
  --text-secondary: #c3c2b7;
  --text-muted: #96948b;
  --series-1: #3987e5;
  --series-2: #d95926;
  --series-3: #199e70;
  --series-4: #c98500;
  --series-5: #d55181;
  --status-good-text: #0ca30c;
  --status-warning-text: #fab219;
  --status-critical-text: #e66767;
  --bar-track: #2c2c2a;
  --accent: #3987e5;
}

* { box-sizing: border-box; }

body {
  margin: 0;
  padding: 0 16px 64px;
  background: var(--surface-0);
  color: var(--text-primary);
  font: 15px/1.55 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
  -webkit-text-size-adjust: 100%;
}

.wrap { max-width: 1180px; margin: 0 auto; }

header.page { padding: 32px 0 20px; }
h1 { font-size: 24px; margin: 0 0 4px; letter-spacing: -0.01em; }
.subtitle { color: var(--text-secondary); margin: 0; font-size: 14px; }

.meta-grid {
  display: grid; gap: 4px 24px; margin-top: 16px;
  grid-template-columns: repeat(auto-fit, minmax(230px, 1fr));
  font-size: 13px; color: var(--text-secondary);
}
.meta-grid b { color: var(--text-primary); font-weight: 600; }

section { margin-top: 36px; }
h2 { font-size: 17px; margin: 0 0 4px; letter-spacing: -0.01em; }
.section-note { margin: 0 0 14px; color: var(--text-muted); font-size: 13px; }

.tiles { display: grid; gap: 12px; grid-template-columns: repeat(auto-fit, minmax(168px, 1fr)); }
.tile {
  background: var(--surface-1); border: 1px solid var(--border);
  border-radius: 10px; padding: 14px 16px;
}
.tile .label { font-size: 12px; color: var(--text-secondary); text-transform: uppercase; letter-spacing: 0.04em; }
.tile .value { font-size: 26px; font-weight: 650; margin-top: 6px; letter-spacing: -0.02em; font-variant-numeric: tabular-nums; }
.tile .hint { font-size: 12px; color: var(--text-muted); margin-top: 2px; }

.banner {
  border: 1px solid var(--status-warning); border-left-width: 4px;
  background: var(--surface-1); border-radius: 8px;
  padding: 10px 14px; margin-top: 16px; font-size: 13px;
}

.controls { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; margin-bottom: 12px; }
.controls input[type="search"] {
  flex: 1 1 220px; min-width: 180px; padding: 8px 10px; font: inherit; font-size: 14px;
  background: var(--surface-1); color: var(--text-primary);
  border: 1px solid var(--border-strong); border-radius: 8px;
}
.controls label { font-size: 13px; color: var(--text-secondary); display: inline-flex; gap: 6px; align-items: center; }
button {
  font: inherit; font-size: 13px; padding: 7px 12px; cursor: pointer;
  background: var(--surface-1); color: var(--text-primary);
  border: 1px solid var(--border-strong); border-radius: 8px;
}
button:hover { border-color: var(--accent); }

.table-scroll { overflow-x: auto; border: 1px solid var(--border); border-radius: 10px; background: var(--surface-1); }
table { border-collapse: collapse; width: 100%; font-size: 13px; }
th, td { padding: 9px 12px; text-align: right; white-space: nowrap; border-bottom: 1px solid var(--border); }
th:first-child, td:first-child, th.left, td.left { text-align: left; }
tbody tr:last-child td { border-bottom: none; }
thead th {
  position: sticky; top: 0; background: var(--surface-2); color: var(--text-secondary);
  font-weight: 600; font-size: 12px; text-transform: uppercase; letter-spacing: 0.03em;
  cursor: pointer; user-select: none;
}
thead th[data-key]::after { content: ""; opacity: 0.45; margin-left: 5px; }
thead th.sort-asc::after { content: "\2191"; opacity: 1; }
thead th.sort-desc::after { content: "\2193"; opacity: 1; }
td.num { font-variant-numeric: tabular-nums; }
.route-cell { max-width: 320px; overflow: hidden; text-overflow: ellipsis; }

.pill {
  display: inline-block; padding: 1px 7px; border-radius: 999px;
  font-size: 11px; font-weight: 600; border: 1px solid var(--border-strong); color: var(--text-secondary);
}
.method { min-width: 46px; text-align: center; }

.lat { font-variant-numeric: tabular-nums; }
.lat-good { color: var(--status-good-text); }
.lat-warn { color: var(--status-warning-text); }
.lat-slow { color: var(--status-critical-text); font-weight: 650; }
.lat-slow::before { content: "\26A0\FE0E "; }
.err-rate-bad { color: var(--status-critical-text); font-weight: 650; }

.legend { display: flex; flex-wrap: wrap; gap: 14px; margin: 0 0 14px; padding: 0; list-style: none; font-size: 13px; }
.legend li { display: inline-flex; align-items: center; gap: 6px; color: var(--text-secondary); }
.swatch { width: 12px; height: 12px; border-radius: 3px; display: inline-block; }

.phase-row { padding: 12px 14px; border-bottom: 1px solid var(--border); }
.phase-row:last-child { border-bottom: none; }
.phase-head { display: flex; justify-content: space-between; gap: 12px; font-size: 13px; margin-bottom: 7px; }
.phase-head .name { font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.phase-head .total { color: var(--text-secondary); font-variant-numeric: tabular-nums; white-space: nowrap; }
.stack { display: flex; height: 16px; background: var(--bar-track); border-radius: 4px; overflow: hidden; gap: 2px; }
.stack span { display: block; height: 100%; }
.stack span:first-child { border-radius: 4px 0 0 4px; }
.stack span:last-child { border-radius: 0 4px 4px 0; }
.phase-values { margin-top: 6px; font-size: 12px; color: var(--text-secondary); display: flex; flex-wrap: wrap; gap: 12px; }
.phase-values span { font-variant-numeric: tabular-nums; }
.phase-values i { width: 9px; height: 9px; border-radius: 2px; display: inline-block; margin-right: 4px; }

.waterfall { border: 1px solid var(--border); border-radius: 10px; background: var(--surface-1); padding: 14px; }
.axis { position: relative; height: 18px; font-size: 11px; color: var(--text-muted); margin-bottom: 4px; }
.axis span { position: absolute; transform: translateX(-50%); font-variant-numeric: tabular-nums; }
.axis span:first-child { transform: none; }
.axis span:last-child { transform: translateX(-100%); }
.wf-track { position: relative; }
.wf-row { position: relative; height: 15px; margin-bottom: 3px; }
.wf-bar {
  position: absolute; top: 2px; height: 11px; min-width: 2px; border-radius: 3px;
  background: var(--series-1); box-shadow: 0 0 0 2px var(--surface-1);
}
.wf-bar.err { background: var(--status-critical); }
.wf-marker { position: absolute; top: 0; bottom: 0; width: 1px; background: var(--border-strong); }
.wf-marker b {
  position: absolute; top: -16px; left: 2px; font-size: 10px; font-weight: 600;
  color: var(--text-secondary); white-space: nowrap;
}
.wf-legend { margin-top: 10px; font-size: 12px; color: var(--text-muted); }

.hist { display: flex; align-items: flex-end; gap: 2px; height: 72px; margin: 8px 0 4px; }
.hist span { flex: 1; background: var(--series-1); border-radius: 3px 3px 0 0; min-height: 1px; }
.hist-axis { display: flex; justify-content: space-between; font-size: 11px; color: var(--text-muted); font-variant-numeric: tabular-nums; }

details { border: 1px solid var(--border); border-radius: 10px; background: var(--surface-1); margin-bottom: 8px; }
details > summary {
  cursor: pointer; padding: 10px 14px; font-size: 13px; font-weight: 600;
  display: flex; justify-content: space-between; gap: 12px; align-items: center;
}
details > summary::-webkit-details-marker { display: none; }
details > summary .muted { font-weight: 400; color: var(--text-secondary); font-variant-numeric: tabular-nums; }
details > div { padding: 0 14px 14px; }

.kv { display: grid; grid-template-columns: minmax(130px, auto) 1fr; gap: 3px 16px; font-size: 13px; }
.kv dt { color: var(--text-secondary); }
.kv dd { margin: 0; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }

.tooltip {
  position: fixed; z-index: 10; pointer-events: none; opacity: 0;
  transition: opacity 90ms ease; background: var(--surface-1); color: var(--text-primary);
  border: 1px solid var(--border-strong); border-radius: 8px; padding: 8px 10px;
  font-size: 12px; line-height: 1.45; box-shadow: 0 6px 20px rgba(0, 0, 0, 0.18);
  max-width: 320px;
}
.tooltip.on { opacity: 1; }
.tooltip b { font-variant-numeric: tabular-nums; }

footer.page { margin-top: 44px; padding-top: 18px; border-top: 1px solid var(--border); font-size: 12px; color: var(--text-muted); }
footer.page h3 { font-size: 13px; color: var(--text-secondary); margin: 14px 0 4px; }
footer.page p { margin: 4px 0; }
.empty { color: var(--text-muted); font-size: 13px; padding: 14px; border: 1px dashed var(--border-strong); border-radius: 10px; }

@media (max-width: 640px) {
  header.page { padding-top: 22px; }
  .tile .value { font-size: 22px; }
  .route-cell { max-width: 190px; }
}

@media print {
  body { background: #ffffff; }
  .controls, button { display: none; }
  details { break-inside: avoid; }
}
"""
