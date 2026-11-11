"use strict";
// Plain JS, no external requests. Server data is only inserted with textContent.
(function () {
  const TOKEN_KEY = "facenetCoprocessorToken";
  const $ = (id) => document.getElementById(id);
  const getToken = () => sessionStorage.getItem(TOKEN_KEY) || "";

  function say(el, text, kind) {
    el.textContent = text;
    el.className = kind || "muted";
  }

  async function api(method, path, body) {
    const token = getToken();
    if (!token) throw new Error("Paste the API token first");
    const headers = { Authorization: "Bearer " + token };
    let payload = body;
    if (body !== undefined && !(body instanceof FormData)) {
      headers["Content-Type"] = "application/json";
      payload = JSON.stringify(body);
    }
    const res = await fetch(path, { method, headers, body: payload, credentials: "omit", cache: "no-store" });
    const text = await res.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch (e) { data = null; }
    if (res.status === 401) throw new Error("401: token missing or wrong");
    if (!res.ok && !(res.status === 422 && data)) {
      const detail = data && data.error ? data.error + ": " + data.message : res.statusText;
      throw new Error(res.status + " " + detail);
    }
    return { status: res.status, data };
  }

  function guarded(msgEl, fn) {
    return async (event) => {
      if (event) event.preventDefault();
      try { await fn(); } catch (e) { say(msgEl, e.message, "error"); }
    };
  }

  function files(input) {
    const list = Array.from(input.files || []);
    if (list.length === 0) throw new Error("Choose at least one image");
    if (list.length > 10) throw new Error("At most 10 images");
    const form = new FormData();
    list.forEach((f) => form.append("image", f, f.name));
    return form;
  }

  function rejectedText(data) {
    const rejected = (data.images_rejected || []).map((r) => "#" + r.index + " " + r.reason);
    return rejected.length ? " · rejected: " + rejected.join("; ") : "";
  }

  // --- token ---
  function showTokenState() {
    say($("token-msg"), getToken() ? "Token set for this tab." : "No token yet.", getToken() ? "ok" : "muted");
  }
  $("token-form").addEventListener("submit", (e) => {
    e.preventDefault();
    const value = $("token").value.trim();
    if (value) sessionStorage.setItem(TOKEN_KEY, value);
    $("token").value = "";
    showTokenState();
    refreshAll();
  });
  $("token-clear").addEventListener("click", () => {
    sessionStorage.removeItem(TOKEN_KEY);
    showTokenState();
  });

  // --- status ---
  const loadStatus = guarded($("status-msg"), async () => {
    const { data } = await api("GET", "/api/status");
    const rows = [
      ["Battery", data.battery_pct == null ? "?" : data.battery_pct + "%" + (data.charging ? " (charging)" : "")],
      ["Battery temp", data.battery_temp_c == null ? "?" : data.battery_temp_c.toFixed(1) + " °C"],
      ["Thermal status", data.thermal_status || "n/a"],
      ["App heap", data.heap_used_mb + " / " + data.heap_max_mb + " MB"],
      ["Battery optimization", data.ignoring_battery_optimizations ? "unrestricted" : "optimized"],
      ["Persons / face records", data.persons + " / " + data.face_records],
      ["Threshold", String(data.threshold)],
      ["Inference backend", (data.inference_backend || "not loaded") +
        (data.inference_backend_requested && data.inference_backend_requested !== data.inference_backend
          ? " (requested " + data.inference_backend_requested + ")" : "")],
      ["Uptime", data.uptime_s + " s"],
      ["Requests served", String(data.requests_served)],
      ["Android SDK", String(data.android_sdk)],
    ];
    const dl = $("status");
    dl.replaceChildren();
    rows.forEach(([k, v]) => {
      const dt = document.createElement("dt");
      dt.textContent = k;
      const dd = document.createElement("dd");
      dd.textContent = v;
      dl.append(dt, dd);
    });
    say($("status-msg"), "Updated " + new Date().toLocaleTimeString());
  });
  $("status-refresh").addEventListener("click", loadStatus);

  // --- threshold ---
  const loadThreshold = guarded($("threshold-msg"), async () => {
    const { data } = await api("GET", "/api/settings/threshold");
    $("threshold").value = data.threshold.toFixed(2);
  });
  $("threshold-reload").addEventListener("click", loadThreshold);
  $("threshold-form").addEventListener("submit", guarded($("threshold-msg"), async (e) => {
    const value = parseFloat($("threshold").value);
    if (!(value >= 0 && value <= 1)) throw new Error("Threshold must be between 0 and 1");
    const { data } = await api("PUT", "/api/settings/threshold", { threshold: value });
    $("threshold").value = data.threshold.toFixed(2);
    say($("threshold-msg"), "Saved: " + data.threshold.toFixed(2), "ok");
  }));

  // --- inference backend ---
  let backendTimer = null;
  function mb(bytes) { return bytes == null ? "?" : Math.round(bytes / 1e6) + " MB"; }
  function renderBackend(data) {
    const box = $("backend-options");
    box.replaceChildren();
    data.backends.forEach((b) => {
      const label = document.createElement("label");
      label.className = "option";
      const input = document.createElement("input");
      input.type = "radio";
      input.name = "backend";
      input.value = b.id;
      input.checked = b.id === data.requested;
      input.disabled = !b.available;
      const text = document.createElement("span");
      let detail = b.precision + ", ";
      if (!b.available) detail = b.reason || "not available";
      else if (b.source === "bundled") detail += "bundled";
      else detail += b.downloaded ? "downloaded" : "download " + mb(b.size_bytes) + " on first use";
      text.textContent = " " + b.label + " — " + detail;
      label.append(input, text);
      box.append(label);
    });
    let state = "Active: " + (data.active || "not loaded") + " · " + data.phase;
    if (data.phase === "downloading") state += " " + mb(data.downloaded_bytes) + " / " + mb(data.total_bytes);
    if (data.fallback_reason) state += " · fell back to CPU: " + data.fallback_reason;
    say($("backend-state"), state, data.fallback_reason ? "error" : "ok");
    clearTimeout(backendTimer);
    if (data.phase === "downloading" || data.phase === "loading") backendTimer = setTimeout(loadBackend, 2000);
  }
  const loadBackend = guarded($("backend-msg"), async () => {
    const { data } = await api("GET", "/api/settings/backend");
    renderBackend(data);
  });
  $("backend-reload").addEventListener("click", loadBackend);
  $("backend-form").addEventListener("submit", guarded($("backend-msg"), async () => {
    const checked = document.querySelector('input[name="backend"]:checked');
    if (!checked) throw new Error("Choose a backend");
    const { data } = await api("PUT", "/api/settings/backend", { backend: checked.value });
    renderBackend(data);
    say($("backend-msg"), "Switch requested: " + checked.value, "ok");
  }));

  // --- recognize ---
  function loadImage(file) {
    return new Promise((resolve, reject) => {
      const url = URL.createObjectURL(file);
      const img = new Image();
      img.onload = () => { URL.revokeObjectURL(url); resolve(img); };
      img.onerror = () => { URL.revokeObjectURL(url); reject(new Error("This browser cannot display the image")); };
      img.src = url;
    });
  }

  function draw(img, data) {
    const canvas = $("canvas");
    const scale = Math.min(1, 1280 / img.naturalWidth);
    canvas.width = Math.round(img.naturalWidth * scale);
    canvas.height = Math.round(img.naturalHeight * scale);
    const ctx = canvas.getContext("2d");
    ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
    // Boxes are in the server's decoded image space (may be subsampled to 1920 px).
    const sx = canvas.width / data.image_width;
    const sy = canvas.height / data.image_height;
    ctx.lineWidth = 3;
    ctx.font = "16px system-ui, sans-serif";
    if (data.person_box) {
      const [px, py, pw, ph] = data.person_box;
      ctx.setLineDash([8, 6]);
      ctx.strokeStyle = "#f5a623";
      ctx.strokeRect(px * canvas.width, py * canvas.height, pw * canvas.width, ph * canvas.height);
      ctx.setLineDash([]);
    }
    data.faces.forEach((f) => {
      const b = f.bbox;
      const x = b.left * sx, y = b.top * sy, w = (b.right - b.left) * sx, h = (b.bottom - b.top) * sy;
      const color = f.recognized ? "#1db954" : f.is_stranger ? "#8e4ec6" : "#e5484d";
      let label = (f.recognized ? f.name : f.is_stranger && f.name ? f.name : "unknown") + " " + f.similarity.toFixed(2);
      if (f.is_stranger && f.stranger_status) label += " " + f.stranger_status + (f.appearances ? " x" + f.appearances : "");
      if (f.sharpness != null) label += " sharp=" + Math.round(f.sharpness);
      if (f.spoof) label += f.spoof.is_spoof ? " SPOOF" : " live";
      ctx.strokeStyle = color;
      ctx.strokeRect(x, y, w, h);
      const ty = Math.max(0, y - 22);
      ctx.fillStyle = color;
      ctx.fillRect(x, ty, ctx.measureText(label).width + 8, 22);
      ctx.fillStyle = "#fff";
      ctx.fillText(label, x + 4, ty + 16);
    });
  }

  $("recognize-form").addEventListener("submit", guarded($("recognize-msg"), async () => {
    const file = ($("recognize-file").files || [])[0];
    if (!file) throw new Error("Choose an image");
    const form = new FormData();
    form.append("image", file, file.name);
    const query = new URLSearchParams();
    if ($("liveness").checked) query.set("liveness", "true");
    if ($("cluster-strangers").checked) query.set("cluster_strangers", "true");
    const box = $("person-box").value.trim();
    if (box) query.set("person_box", box);
    const qs = query.toString().replace(/%2C/g, ",");
    const path = "/api/recognize" + (qs ? "?" + qs : "");
    say($("recognize-msg"), "Recognizing…");
    const started = performance.now();
    const { data } = await api("POST", path, form);
    const roundtrip = Math.round(performance.now() - started);
    draw(await loadImage(file), data);
    say($("recognize-msg"),
      "inference_ms=" + data.inference_ms + " · roundtrip=" + roundtrip + " ms · faces=" + data.face_count +
      " · known=" + data.recognized_count + " · threshold=" + data.threshold +
      (data.faces_outside_person_box ? " · outside person_box=" + data.faces_outside_person_box : ""), "ok");
    $("recognize-json").textContent = JSON.stringify(data, null, 2);
  }));

  // --- persons ---
  let personsCache = [];
  const loadPersons = guarded($("persons-msg"), async () => {
    const { data } = await api("GET", "/api/persons");
    personsCache = data.persons;
    fillPersonSelects();
    const body = $("persons");
    const select = $("add-person");
    body.replaceChildren();
    select.replaceChildren();
    data.persons.forEach((p) => {
      const tr = document.createElement("tr");
      [String(p.person_id), p.name, String(p.num_images), new Date(p.added_at_ms).toLocaleString()].forEach((v) => {
        const td = document.createElement("td");
        td.textContent = v;
        tr.append(td);
      });
      const td = document.createElement("td");
      const del = document.createElement("button");
      del.type = "button";
      del.textContent = "Delete";
      del.addEventListener("click", guarded($("persons-msg"), async () => {
        if (!window.confirm("Delete " + p.name + " (id " + p.person_id + ") and all face images?")) return;
        await api("DELETE", "/api/persons/" + p.person_id);
        say($("persons-msg"), "Deleted " + p.name, "ok");
        await loadPersons();
      }));
      td.append(del);
      tr.append(td);
      body.append(tr);

      const opt = document.createElement("option");
      opt.value = String(p.person_id);
      opt.textContent = p.person_id + " – " + p.name;
      select.append(opt);
    });
    say($("persons-msg"), data.count + " person(s)");
  });
  $("persons-refresh").addEventListener("click", loadPersons);

  $("enroll-form").addEventListener("submit", guarded($("enroll-msg"), async () => {
    const name = $("enroll-name").value.trim();
    if (!name) throw new Error("Name is required");
    const form = files($("enroll-files"));
    form.append("name", name);
    say($("enroll-msg"), "Enrolling…");
    const { status, data } = await api("POST", "/api/enroll", form);
    const msg = status === 201
      ? "Enrolled " + data.name + " as id " + data.person_id + " with " + data.images_accepted + " image(s)"
      : "No image accepted";
    say($("enroll-msg"), msg + rejectedText(data), status === 201 ? "ok" : "error");
    await loadPersons();
  }));

  $("add-form").addEventListener("submit", guarded($("add-msg"), async () => {
    const id = $("add-person").value;
    if (!id) throw new Error("Choose a person (refresh the list first)");
    const form = files($("add-files"));
    say($("add-msg"), "Uploading…");
    const { status, data } = await api("POST", "/api/persons/" + encodeURIComponent(id) + "/images", form);
    const msg = status === 200 ? "Added " + data.images_accepted + " image(s) to " + data.name : "No image accepted";
    say($("add-msg"), msg + rejectedText(data), status === 200 ? "ok" : "error");
    await loadPersons();
  }));

  // --- strangers ---
  function cell(text) {
    const td = document.createElement("td");
    td.textContent = text;
    return td;
  }
  function when(ms) { return ms ? new Date(ms).toLocaleString() : "?"; }

  function personOptions(select) {
    const keep = select.value;
    const placeholder = document.createElement("option");
    placeholder.value = "";
    placeholder.textContent = "existing person…";
    select.replaceChildren(placeholder);
    personsCache.forEach((p) => {
      const opt = document.createElement("option");
      opt.value = String(p.person_id);
      opt.textContent = p.name + " (id " + p.person_id + ")";
      select.append(opt);
    });
    select.value = personsCache.some((p) => String(p.person_id) === keep) ? keep : "";
  }
  function fillPersonSelects() {
    document.querySelectorAll("select.stranger-person").forEach(personOptions);
  }

  async function promote(stranger, body) {
    const { data } = await api("POST", "/api/strangers/" + encodeURIComponent(stranger.stranger_id) + "/promote", body);
    const verb = data.created ? "Created " + data.name + " (id " + data.person_id + ")"
      : "Added to " + data.name + " (id " + data.person_id + ")";
    await loadPersons();
    await loadStrangers();
    say($("strangers-msg"), data.stranger_name + " → " + verb + ", " + data.embeddings_moved + " embedding(s)", "ok");
  }

  function strangerRow(s) {
    const msg = $("strangers-msg");
    const tr = document.createElement("tr");
    tr.append(cell(s.name), cell(String(s.appearances)), cell(String(s.num_embeddings)),
      cell(when(s.first_seen_ms)), cell(when(s.last_seen_ms)));

    const actions = document.createElement("div");
    actions.className = "stranger-actions";
    const select = document.createElement("select");
    select.className = "stranger-person";
    select.setAttribute("aria-label", "Assign " + s.name + " to a known person");
    personOptions(select);
    const assign = document.createElement("button");
    assign.type = "button";
    assign.textContent = "Assign";
    assign.addEventListener("click", guarded(msg, async () => {
      const person = personsCache.find((p) => String(p.person_id) === select.value);
      if (!person) throw new Error("Choose a known person for " + s.name + " (refresh Persons first)");
      if (!window.confirm("Move " + s.name + " (" + s.num_embeddings + " embedding(s)) into " + person.name + " (id " + person.person_id + ")?")) return;
      await promote(s, { person_id: person.person_id });
    }));
    const name = document.createElement("input");
    name.maxLength = 64;
    name.placeholder = "new person name";
    name.setAttribute("aria-label", "New person name for " + s.name);
    const create = document.createElement("button");
    create.type = "button";
    create.textContent = "Create";
    create.addEventListener("click", guarded(msg, async () => {
      const value = name.value.trim();
      if (!value) throw new Error("Type a name for " + s.name);
      const same = personsCache.filter((p) => p.name === value);
      if (same.length > 1) throw new Error("Several persons are named " + value + "; use Assign instead");
      const question = same.length === 1
        ? value + " already exists (id " + same[0].person_id + "). Add " + s.name + " to that person?"
        : "Create new person " + value + " from " + s.name + "?";
      if (!window.confirm(question)) return;
      await promote(s, { name: value });
    }));
    actions.append(select, assign, name, create);
    const actionsTd = document.createElement("td");
    actionsTd.append(actions);

    const delTd = document.createElement("td");
    const del = document.createElement("button");
    del.type = "button";
    del.textContent = "Delete";
    del.addEventListener("click", guarded(msg, async () => {
      if (!window.confirm("Delete " + s.name + " and its " + s.num_embeddings + " embedding(s)?")) return;
      await api("DELETE", "/api/strangers/" + encodeURIComponent(s.stranger_id));
      await loadStrangers();
      say(msg, "Deleted " + s.name, "ok");
    }));
    delTd.append(del);
    tr.append(actionsTd, delTd);
    return tr;
  }

  const loadStrangers = guarded($("strangers-msg"), async () => {
    const { data } = await api("GET", "/api/strangers");
    $("strangers").replaceChildren(...data.strangers.map(strangerRow));
    say($("strangers-msg"), data.count + " / " + data.max_clusters + " stranger group(s)");
  });
  $("strangers-refresh").addEventListener("click", loadStrangers);
  $("strangers-delete-all").addEventListener("click", guarded($("strangers-msg"), async () => {
    if (!window.confirm("Delete ALL stranger groups and their embeddings?")) return;
    const { data } = await api("DELETE", "/api/strangers");
    await loadStrangers();
    say($("strangers-msg"), "Deleted " + data.deleted + " group(s)", "ok");
  }));

  function showStrangerSettings(data) {
    $("stranger-threshold").value = data.threshold.toFixed(2);
    $("stranger-limits").textContent = "max " + data.max_clusters + " groups × " + data.max_embeddings_per_cluster +
      " embeddings · faces < " + data.min_face_px + " px skipped";
  }
  const loadStrangerSettings = guarded($("stranger-settings-msg"), async () => {
    const { data } = await api("GET", "/api/settings/strangers");
    showStrangerSettings(data);
  });
  $("stranger-settings-form").addEventListener("submit", guarded($("stranger-settings-msg"), async () => {
    const value = parseFloat($("stranger-threshold").value);
    if (!(value >= 0 && value <= 1)) throw new Error("Threshold must be between 0 and 1");
    const { data } = await api("PUT", "/api/settings/strangers", { threshold: value });
    showStrangerSettings(data);
    say($("stranger-settings-msg"), "Saved: " + data.threshold.toFixed(2), "ok");
  }));

  function refreshAll() {
    if (!getToken()) return;
    loadStatus();
    loadThreshold();
    loadBackend();
    loadPersons();
    loadStrangers();
    loadStrangerSettings();
  }

  showTokenState();
  refreshAll();
})();
