package io.github.latencyscope.export

/**
 * Inline script for the HTML report.
 *
 * Every number, bar and row is rendered server-side, so the page is complete
 * and readable with scripting disabled. This adds only the interactive layer:
 * column sorting, filtering, hover tooltips and the theme toggle.
 *
 * Written without template literals on purpose — the file is embedded in a
 * Kotlin string, where a dollar sign would start an interpolation.
 */
internal const val REPORT_JS: String = """
(function () {
  "use strict";

  var tip = document.createElement("div");
  tip.className = "tooltip";
  tip.setAttribute("role", "status");
  document.body.appendChild(tip);

  function showTip(html, x, y) {
    tip.innerHTML = html;
    tip.classList.add("on");
    var box = tip.getBoundingClientRect();
    var left = Math.min(Math.max(8, x + 14), window.innerWidth - box.width - 8);
    var top = y - box.height - 14;
    if (top < 8) { top = y + 18; }
    tip.style.left = left + "px";
    tip.style.top = top + "px";
  }

  function hideTip() { tip.classList.remove("on"); }

  // Hit targets are the marks themselves; both pointer and keyboard reach them.
  document.addEventListener("mousemove", function (event) {
    var host = event.target.closest ? event.target.closest("[data-tip]") : null;
    if (!host) { hideTip(); return; }
    showTip(host.getAttribute("data-tip"), event.clientX, event.clientY);
  });
  document.addEventListener("mouseleave", hideTip);
  document.addEventListener("scroll", hideTip, true);
  document.addEventListener("focusin", function (event) {
    var host = event.target.closest ? event.target.closest("[data-tip]") : null;
    if (!host) { hideTip(); return; }
    var box = host.getBoundingClientRect();
    showTip(host.getAttribute("data-tip"), box.left + box.width / 2, box.top);
  });
  document.addEventListener("focusout", hideTip);

  // ---------------------------------------------------------------- sorting

  function cellValue(row, key) {
    var cell = row.querySelector("[data-key='" + key + "']");
    if (!cell) { return ""; }
    var raw = cell.getAttribute("data-value");
    if (raw === null) { return cell.textContent.trim().toLowerCase(); }
    var num = parseFloat(raw);
    return isNaN(num) ? raw.toLowerCase() : num;
  }

  function sortTable(table, key, descending) {
    var body = table.tBodies[0];
    var rows = Array.prototype.slice.call(body.rows);
    rows.sort(function (a, b) {
      var left = cellValue(a, key);
      var right = cellValue(b, key);
      if (left < right) { return descending ? 1 : -1; }
      if (left > right) { return descending ? -1 : 1; }
      return 0;
    });
    rows.forEach(function (row) { body.appendChild(row); });
  }

  Array.prototype.forEach.call(document.querySelectorAll("table[data-sortable]"), function (table) {
    Array.prototype.forEach.call(table.querySelectorAll("th[data-key]"), function (header) {
      header.setAttribute("tabindex", "0");
      header.setAttribute("role", "button");
      var activate = function () {
        var key = header.getAttribute("data-key");
        var wasDescending = header.classList.contains("sort-desc");
        var descending = !wasDescending;
        Array.prototype.forEach.call(table.querySelectorAll("th"), function (other) {
          other.classList.remove("sort-asc", "sort-desc");
          other.removeAttribute("aria-sort");
        });
        header.classList.add(descending ? "sort-desc" : "sort-asc");
        header.setAttribute("aria-sort", descending ? "descending" : "ascending");
        sortTable(table, key, descending);
      };
      header.addEventListener("click", activate);
      header.addEventListener("keydown", function (event) {
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          activate();
        }
      });
    });
  });

  // -------------------------------------------------------------- filtering

  function applyFilter(scope) {
    var box = document.querySelector("[data-filter-for='" + scope + "']");
    var errorsOnly = document.querySelector("[data-errors-only-for='" + scope + "']");
    var needle = box ? box.value.trim().toLowerCase() : "";
    var onlyErrors = errorsOnly ? errorsOnly.checked : false;
    var shown = 0;
    var rows = document.querySelectorAll("[data-scope='" + scope + "']");
    Array.prototype.forEach.call(rows, function (row) {
      var haystack = (row.getAttribute("data-search") || "").toLowerCase();
      var isError = row.getAttribute("data-error") === "1";
      var visible = (needle === "" || haystack.indexOf(needle) !== -1) && (!onlyErrors || isError);
      row.style.display = visible ? "" : "none";
      if (visible) { shown += 1; }
    });
    var counter = document.querySelector("[data-count-for='" + scope + "']");
    if (counter) {
      counter.textContent = shown + " of " + rows.length + " shown";
    }
  }

  Array.prototype.forEach.call(document.querySelectorAll("[data-filter-for]"), function (box) {
    var scope = box.getAttribute("data-filter-for");
    box.addEventListener("input", function () { applyFilter(scope); });
    applyFilter(scope);
  });
  Array.prototype.forEach.call(document.querySelectorAll("[data-errors-only-for]"), function (box) {
    var scope = box.getAttribute("data-errors-only-for");
    box.addEventListener("change", function () { applyFilter(scope); });
  });

  // ------------------------------------------------------------------ theme

  var toggle = document.getElementById("theme-toggle");
  if (toggle) {
    toggle.addEventListener("click", function () {
      var root = document.documentElement;
      var dark = root.getAttribute("data-theme") === "dark";
      if (dark) {
        root.setAttribute("data-theme", "light");
      } else {
        root.setAttribute("data-theme", "dark");
      }
    });
  }

  // ------------------------------------------------------------- expand all

  var expandAll = document.getElementById("expand-all");
  if (expandAll) {
    expandAll.addEventListener("click", function () {
      var open = expandAll.getAttribute("data-open") === "1";
      Array.prototype.forEach.call(document.querySelectorAll("details.call"), function (node) {
        node.open = !open;
      });
      expandAll.setAttribute("data-open", open ? "0" : "1");
      expandAll.textContent = open ? "Expand all" : "Collapse all";
    });
  }
})();
"""
