// ====== Settings you can change ======
var DAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];
var START_HOUR = 7;    // calendar starts at 7:00 AM   (keep in sync with SignupServlet.java)
var END_HOUR = 20;     // calendar ends at 8:00 PM
var STEP = 30;         // start-time choices every 30 minutes
var API = "api";       // relative to the page, so it works under /Boostsignup/
var REFRESH_MS = 10000; // how often to pull everyone else's sign-ups
// =====================================

var HOUR_PX = 56; // must match --hour in style.css
var MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

var signups = [];
var currentWeek = "";   // Monday of this week, from the server (Central time)
var nextWeekOpen = false;
var lastSnapshot = "";
var editingId = null;
var activeDay = 0;
var activeWeek = "";

var weekEl = document.getElementById("week");
var dialog = document.getElementById("dialog");
var form = document.getElementById("form");
var nameEl = document.getElementById("name");
var startEl = document.getElementById("start");
var lengthEl = document.getElementById("length");
var sizeEl = document.getElementById("size");
var errorEl = document.getElementById("error");

var week2El = null;
var title1El = null;
var title2El = null;
var statusEl = null;

// ---------- Small helpers ----------
function pad(n) {
  return n < 10 ? "0" + n : "" + n;
}

function dateKey(d) {
  return d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate());
}

function parseKey(key) {
  var p = key.split("-");
  return new Date(parseInt(p[0], 10), parseInt(p[1], 10) - 1, parseInt(p[2], 10));
}

function addDays(d, n) {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);
}

function fmt(mins) {
  var h = Math.floor(mins / 60);
  var m = mins % 60;
  var ap = h >= 12 ? "PM" : "AM";
  h = h % 12;
  if (h === 0) { h = 12; }
  return h + ":" + pad(m) + " " + ap;
}

function lengthLabel(min) {
  var h = Math.floor(min / 60);
  var m = min % 60;
  var out = "";
  if (h) { out += h + " hr"; }
  if (h && m) { out += " "; }
  if (m) { out += m + " min"; }
  return out;
}

function shortDate(d) {
  return MONTHS[d.getMonth()] + " " + d.getDate();
}

// ---------- Talking to the server ----------
function request(method, path, params) {
  var opts = { method: method, headers: {}, cache: "no-store" };
  if (params) {
    var parts = [];
    for (var k in params) {
      if (params.hasOwnProperty(k)) {
        parts.push(encodeURIComponent(k) + "=" + encodeURIComponent(params[k]));
      }
    }
    opts.body = parts.join("&");
    opts.headers["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8";
  }
  return fetch(API + path, opts).then(function (res) {
    return res.json().then(function (data) {
      return { ok: res.ok, data: data };
    });
  });
}

function setStatus(msg) {
  statusEl.textContent = msg;
  statusEl.style.display = msg ? "block" : "none";
}

// Pull everyone's sign-ups and redraw if anything changed
function refresh(force) {
  return request("GET", "/signups", null).then(function (r) {
    if (!r.ok) { throw new Error("bad response"); }
    setStatus("");
    var snap = JSON.stringify(r.data);
    if (!force && snap === lastSnapshot) { return; }
    lastSnapshot = snap;
    signups = r.data.signups;
    currentWeek = r.data.currentWeek;
    nextWeekOpen = r.data.nextWeekOpen;
    build();
  }).catch(function () {
    setStatus("Can't reach the server right now. Sign-ups may be out of date.");
  });
}

// ---------- Build one week's calendar ----------
function buildCalendar(container, monday, locked) {
  var weekKey = dateKey(monday);
  container.innerHTML = "";
  var totalPx = (END_HOUR - START_HOUR) * HOUR_PX;

  var corner = document.createElement("div");
  corner.className = "corner";
  container.appendChild(corner);

  DAYS.forEach(function (d, i) {
    var b = document.createElement("button");
    b.className = "day-head";
    var date = addDays(monday, i);
    b.innerHTML = "<span></span><small></small>";
    b.firstChild.textContent = d;
    b.lastChild.textContent = (date.getMonth() + 1) + "/" + date.getDate();
    b.disabled = locked;
    b.addEventListener("click", function () { openDialog(weekKey, i, null, null); });
    container.appendChild(b);
  });

  var times = document.createElement("div");
  times.className = "times";
  times.style.height = totalPx + "px";
  for (var h = START_HOUR; h < END_HOUR; h++) {
    var s = document.createElement("span");
    s.style.top = (h - START_HOUR) * HOUR_PX + 10 + "px";
    s.textContent = fmt(h * 60).replace(":00", "");
    times.appendChild(s);
  }
  container.appendChild(times);

  DAYS.forEach(function (d, i) {
    var col = document.createElement("div");
    col.className = "day-col";
    col.style.height = totalPx + "px";

    if (!locked) {
      col.addEventListener("click", function (e) {
        if (e.target !== col) { return; }
        var y = e.offsetY;
        var mins = START_HOUR * 60 + Math.floor(y / HOUR_PX * 60 / STEP) * STEP;
        openDialog(weekKey, i, mins, null);
      });
    }

    signups.filter(function (s) { return s.week === weekKey && s.day === i; }).forEach(function (s) {
      var el = document.createElement("button");
      el.className = "slot";
      el.style.top = (s.start - START_HOUR * 60) / 60 * HOUR_PX + "px";
      el.style.height = s.length / 60 * HOUR_PX - 2 + "px";
      el.innerHTML = "<strong></strong><small></small>";
      el.querySelector("strong").textContent = s.name;
      el.querySelector("small").textContent =
        fmt(s.start) + " - " + fmt(s.start + s.length) + " | " + s.size;
      el.title = lengthLabel(s.length) + " - click to edit";
      el.disabled = locked;
      el.addEventListener("click", function () { openDialog(weekKey, i, null, s); });
      col.appendChild(el);
    });

    container.appendChild(col);
  });

  container.className = locked ? "week locked" : "week";
  if (locked) {
    var overlay = document.createElement("div");
    overlay.className = "lock-overlay";
    overlay.innerHTML = "<p><strong>Next week is locked</strong><br>" +
      "Sign-ups open Friday at 7:00 PM Central.</p>";
    container.appendChild(overlay);
  }
}

// ---------- Page layout ----------
function setup() {
  var parent = weekEl.parentNode;

  statusEl = document.createElement("div");
  statusEl.className = "status-bar";
  statusEl.style.display = "none";
  parent.insertBefore(statusEl, weekEl);

  title1El = document.createElement("h2");
  title1El.className = "week-title";
  parent.insertBefore(title1El, weekEl);

  title2El = document.createElement("h2");
  title2El.className = "week-title";
  week2El = document.createElement("main");
  week2El.id = "week2";
  parent.insertBefore(title2El, weekEl.nextSibling);
  parent.insertBefore(week2El, title2El.nextSibling);
}

function build() {
  var monday = parseKey(currentWeek);
  var nextMonday = addDays(monday, 7);

  title1El.textContent = "This week (" + shortDate(monday) + " - " + shortDate(addDays(monday, 6)) + ")";
  title2El.textContent = "Next week (" + shortDate(nextMonday) + " - " + shortDate(addDays(nextMonday, 6)) + ")";

  buildCalendar(weekEl, monday, false);
  buildCalendar(week2El, nextMonday, !nextWeekOpen);
  buildTracker();
}

// ---------- Shorts wash tracker ----------
// Each sign-up counts as one use of that size. Only this week and earlier
// weeks count (next week hasn't happened yet). "Mark washed" resets a size.
function buildTracker() {
  var box = document.getElementById("tracker");
  if (!box) {
    box = document.createElement("section");
    box.id = "tracker";
    box.className = "tracker";
    document.body.appendChild(box);
  }
  box.innerHTML = "";

  var title = document.createElement("h2");
  title.textContent = "Shorts use since last wash";
  box.appendChild(title);

  var table = document.createElement("table");
  table.innerHTML = "<thead><tr><th>Size</th><th>Uses</th><th></th></tr></thead>";
  var body = document.createElement("tbody");

  for (var i = 0; i < sizeEl.options.length; i++) {
    (function (size) {
      var uses = signups.filter(function (s) {
        return s.size === size && !s.washed && s.week <= currentWeek;
      }).length;

      var tr = document.createElement("tr");
      var tdSize = document.createElement("td");
      tdSize.textContent = size;
      var tdUses = document.createElement("td");
      tdUses.textContent = uses;
      if (uses >= 3) { tdUses.className = "high"; }
      var tdBtn = document.createElement("td");
      var btn = document.createElement("button");
      btn.type = "button";
      btn.className = "ghost";
      btn.textContent = "Mark washed";
      btn.disabled = uses === 0;
      btn.addEventListener("click", function () {
        request("POST", "/washed", { size: size }).then(function () {
          refresh(true);
        }).catch(function () {
          setStatus("Couldn't reach the server.");
        });
      });
      tdBtn.appendChild(btn);

      tr.appendChild(tdSize);
      tr.appendChild(tdUses);
      tr.appendChild(tdBtn);
      body.appendChild(tr);
    })(sizeEl.options[i].value);
  }

  table.appendChild(body);
  box.appendChild(table);
}

// ---------- Dialog ----------
function fillStartOptions() {
  startEl.innerHTML = "";
  for (var m = START_HOUR * 60; m < END_HOUR * 60; m += STEP) {
    var o = document.createElement("option");
    o.value = m;
    o.textContent = fmt(m);
    startEl.appendChild(o);
  }
}

function openDialog(weekKey, day, startMins, existing) {
  activeWeek = weekKey;
  activeDay = day;
  editingId = existing ? existing.id : null;
  errorEl.textContent = "";
  document.getElementById("dialogTitle").textContent =
    (existing ? "Edit sign-up - " : "Sign up - ") + DAYS[day];

  nameEl.value = existing ? existing.name : "";
  if (existing) {
    startEl.value = existing.start;
  } else if (startMins !== null && startMins !== undefined) {
    startEl.value = startMins;
  } else {
    startEl.value = START_HOUR * 60;
  }
  lengthEl.value = existing ? existing.length : 60;
  sizeEl.value = existing ? existing.size : "M";

  // Delete button only when editing
  var old = form.querySelector(".danger");
  if (old) { old.parentNode.removeChild(old); }

  if (existing) {
    var del = document.createElement("button");
    del.type = "button";
    del.className = "danger";
    del.textContent = "Delete";
    del.addEventListener("click", function () {
      request("POST", "/signups/delete", { id: editingId }).then(function () {
        dialog.close();
        refresh(true);
      }).catch(function () {
        errorEl.textContent = "Couldn't reach the server.";
      });
    });
    var actions = form.querySelector(".actions");
    actions.insertBefore(del, actions.firstChild);
  }

  dialog.showModal();
  nameEl.focus();
}

form.addEventListener("submit", function (e) {
  e.preventDefault();
  var name = nameEl.value.replace(/^\s+|\s+$/g, "");
  if (!name) {
    errorEl.textContent = "Enter your name.";
    return;
  }

  var params = {
    week: activeWeek,
    day: activeDay,
    name: name,
    start: startEl.value,
    length: lengthEl.value,
    size: sizeEl.value
  };
  var path = "/signups";
  if (editingId) {
    path = "/signups/update";
    params.id = editingId;
  }

  request("POST", path, params).then(function (r) {
    if (!r.ok) {
      // The server checks overlaps, the end of day, and the Friday lock
      errorEl.textContent = r.data.error || "Something went wrong.";
      return;
    }
    dialog.close();
    refresh(true);
  }).catch(function () {
    errorEl.textContent = "Couldn't reach the server. Try again.";
  });
});

document.getElementById("cancel").addEventListener("click", function () {
  dialog.close();
});

fillStartOptions();
setup();
refresh(true);
// Keep pulling so everyone sees new sign-ups, the Friday unlock, and the Sunday rollover
setInterval(function () { refresh(false); }, REFRESH_MS);