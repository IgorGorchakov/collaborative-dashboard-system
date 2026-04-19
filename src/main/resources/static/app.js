"use strict";

/**
 * Minimal vanilla client for the collaborative drawing dashboard.
 *
 * Identity model (per-dashboard, server-validated):
 *   - User types a display name on the Create or Join form.
 *   - The name is sent on the STOMP CONNECT frame (X-Username header) together
 *     with the dashboard id. The server rejects duplicates and invalid names.
 *   - Last-used name is remembered in localStorage to prefill the input only.
 *
 * Flow:
 *   1. ?id=<uuid> → show Join panel; otherwise show Create panel.
 *   2. Submit form → POST /api/dashboards (create) or GET /api/dashboards/{id} (join).
 *   3. Connect STOMP-over-SockJS to /ws with identity headers.
 *   4. Subscribe /topic/dashboard/{id} (strokes) and /topic/dashboard/{id}/users (presence).
 *   5. Fetch /history, replay; handle live frames.
 *   6. Batch pointer events every BATCH_MS and publish to /app/draw/{id}.
 */

const BATCH_MS = 50;
const USERNAME_KEY = "dashboard.username";

// ---------- DOM ----------

const statusEl       = document.getElementById("status");
const createPanel    = document.getElementById("create-panel");
const joinPanel      = document.getElementById("join-panel");
const drawPanel      = document.getElementById("draw-panel");
const createForm     = document.getElementById("create-form");
const createUsername = document.getElementById("create-username");
const createError    = document.getElementById("create-error");
const joinForm       = document.getElementById("join-form");
const joinUsername   = document.getElementById("join-username");
const joinError      = document.getElementById("join-error");
const canvas         = document.getElementById("canvas");
const ctx            = canvas.getContext("2d");
const colorInput     = document.getElementById("color");
const thicknessInp   = document.getElementById("thickness");
const dashboardIdEl  = document.getElementById("dashboard-id");
const copyLinkBtn    = document.getElementById("copy-link");
const clearBtn       = document.getElementById("clear-btn");
const deleteBtn      = document.getElementById("delete-btn");
const currentUserEl  = document.getElementById("current-user");
const usersListEl    = document.getElementById("users-list");

// ---------- identity ----------

function rememberUsername(name) {
    try { localStorage.setItem(USERNAME_KEY, name); } catch {}
}

function recalledUsername() {
    try { return localStorage.getItem(USERNAME_KEY) || ""; } catch { return ""; }
}

// ---------- session state ----------

let stompClient = null;
let dashboard   = null;
let username    = null;
let buffer      = [];
let drawing     = false;
let flushTimer  = null;
let lastRenderedOrdinal = 0;
let replayDone  = false;
let liveBuffer  = [];

// ---------- HTTP helper ----------

async function api(method, path, body) {
    const res = await fetch(path, {
        method,
        headers: { "Content-Type": "application/json" },
        body: body ? JSON.stringify(body) : undefined,
    });
    if (!res.ok) {
        let msg = res.status + " " + res.statusText;
        try { const j = await res.json(); if (j.message) msg = j.message; } catch {}
        throw new Error(msg);
    }
    const ct = res.headers.get("content-type") || "";
    if (ct.includes("application/json")) return res.json();
    return res.text();
}

// ---------- UI state ----------

function setStatus(state, text) {
    statusEl.className = "status " + state;
    statusEl.textContent = text || state;
}

function showPanel(panel) {
    for (const p of [createPanel, joinPanel, drawPanel]) p.classList.add("hidden");
    panel.classList.remove("hidden");
}

function showError(el, msg) {
    el.textContent = msg;
    el.classList.remove("hidden");
}

function clearError(el) {
    el.textContent = "";
    el.classList.add("hidden");
}

function humanizeStompError(reason) {
    switch (reason) {
        case "username_taken":          return "That username is already in use on this dashboard.";
        case "invalid_username":        return "Username must be 1–32 chars: letters, digits, space, _, ., -";
        case "invalid_dashboard_id":    return "Invalid dashboard id.";
        case "missing_identity_headers":return "Missing identity headers — please reload.";
        default:                        return reason || "Connection error.";
    }
}

// ---------- dashboard ----------

async function createDashboard(payload) { return api("POST", "/api/dashboards", payload); }
async function loadDashboard(id)        { return api("GET",  "/api/dashboards/" + id); }

function openDashboard(d, user) {
    dashboard = d;
    username = user;
    rememberUsername(user);
    currentUserEl.textContent = user;
    dashboardIdEl.textContent = d.id;
    canvas.width  = d.width;
    canvas.height = d.height;

    // Reset replay state (relevant when switching dashboards without full reload)
    lastRenderedOrdinal = 0;
    replayDone = false;
    liveBuffer = [];
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    renderUsers([]);

    showPanel(drawPanel);

    const url = new URL(window.location.href);
    url.searchParams.set("id", d.id);
    window.history.replaceState({}, "", url);

    connectStomp();
}

// ---------- active users ----------

function renderUsers(users) {
    usersListEl.innerHTML = "";
    for (const name of users) {
        const li = document.createElement("li");
        li.textContent = name;
        if (name === username) li.classList.add("self");
        usersListEl.appendChild(li);
    }
}

// ---------- live stream ----------

function handleStroke(stroke) {
    if (stroke.userId === username) return;                  // skip own echo
    if (typeof stroke.ordinal === "number") {
        if (stroke.ordinal <= lastRenderedOrdinal) return;    // already drawn via history
        lastRenderedOrdinal = stroke.ordinal;
    }
    renderStroke(stroke);
}

// Streaming contract for /history (see README > REST API > History streaming):
//
//   Server — DashboardController#history returns a StreamingResponseBody.
//   StrokeService#writeHistory opens a JPA cursor (streamByDashboardIdOrder…)
//   inside a read-only transaction and writes payloads straight to the HTTP
//   response one row at a time: '[', payload1, ',', payload2, ..., ']'. The
//   response body begins flushing as soon as the first DB row arrives, and
//   the server never buffers the full array on the heap.
//
//   Client — we still consume the response with res.json() here. That's an
//   intentional trade-off: the server-side fix alone removes the DOS vector
//   described in audit H3, and within the 50k-row HISTORY_MAX cap the
//   browser parse is cheap enough. If replay memory ever becomes a problem
//   on very active boards, swap res.json() for an incremental parser
//   (ReadableStream + NDJSON / oboe.js) — the server already emits each
//   payload as a discrete JSON object, so line-delimiting is a one-line
//   server change and a drop-in reader here.
async function replayHistory() {
    try {
        const strokes = await api("GET", "/api/dashboards/" + dashboard.id + "/history");
        for (const s of strokes) {
            if (typeof s.ordinal === "number" && s.ordinal > lastRenderedOrdinal) {
                lastRenderedOrdinal = s.ordinal;
            }
            renderStroke(s);
        }
    } catch (e) {
        console.warn("History replay failed", e);
    } finally {
        for (const s of liveBuffer) handleStroke(s);
        liveBuffer = [];
        replayDone = true;
    }
}

function connectStomp() {
    setStatus("connecting");
    stompClient = new StompJs.Client({
        webSocketFactory: () => new SockJS("/ws"),
        reconnectDelay: 0, // don't auto-reconnect on identity errors
        connectHeaders: {
            "X-Dashboard-Id": dashboard.id,
            "X-Username": username,
        },
        onConnect: () => {
            setStatus("connected");
            stompClient.subscribe("/topic/dashboard/" + dashboard.id, (frame) => {
                try {
                    const stroke = JSON.parse(frame.body);
                    if (!replayDone) { liveBuffer.push(stroke); return; }
                    handleStroke(stroke);
                } catch (e) { console.warn("Bad frame", e); }
            });
            stompClient.subscribe("/topic/dashboard/" + dashboard.id + "/users", (frame) => {
                try {
                    const msg = JSON.parse(frame.body);
                    renderUsers(msg.users || []);
                } catch (e) { console.warn("Bad users frame", e); }
            });
            replayHistory();
        },
        onStompError: (f) => {
            const reason = (f.headers && f.headers["message"]) || "";
            console.error("STOMP error", reason, f.body);
            setStatus("disconnected", "error");
            returnToIdentityPanel(humanizeStompError(reason));
        },
        onWebSocketClose: () => setStatus("disconnected"),
    });
    stompClient.activate();
}

function returnToIdentityPanel(errorText) {
    if (stompClient) {
        try { stompClient.deactivate(); } catch {}
        stompClient = null;
    }
    // If dashboard exists, the user was joining/creating it — bounce them back
    // to the join panel with the current dashboard id in the URL intact.
    if (dashboard) {
        joinUsername.value = username || recalledUsername();
        showError(joinError, errorText);
        showPanel(joinPanel);
    } else {
        showError(createError, errorText);
        showPanel(createPanel);
    }
}

// ---------- drawing ----------

function pointFromEvent(e) {
    const rect = canvas.getBoundingClientRect();
    const scaleX = canvas.width  / rect.width;
    const scaleY = canvas.height / rect.height;
    return {
        x: (e.clientX - rect.left) * scaleX,
        y: (e.clientY - rect.top ) * scaleY,
    };
}

function renderStroke(stroke) {
    if (!stroke.points || stroke.points.length === 0) return;
    ctx.strokeStyle = stroke.color || "#000";
    ctx.lineWidth   = stroke.thickness || 2;
    ctx.lineCap     = "round";
    ctx.lineJoin    = "round";
    ctx.beginPath();
    ctx.moveTo(stroke.points[0].x, stroke.points[0].y);
    for (let i = 1; i < stroke.points.length; i++) {
        ctx.lineTo(stroke.points[i].x, stroke.points[i].y);
    }
    ctx.stroke();
}

function flushBuffer() {
    if (!stompClient || !stompClient.connected || buffer.length === 0) return;
    const payload = {
        dashboardId: dashboard.id,
        userId: username,
        points: buffer,
        color: colorInput.value,
        thickness: parseInt(thicknessInp.value, 10),
    };
    renderStroke(payload);
    stompClient.publish({
        destination: "/app/draw/" + dashboard.id,
        body: JSON.stringify(payload),
    });
    buffer = [buffer[buffer.length - 1]];
}

canvas.addEventListener("pointerdown", (e) => {
    drawing = true;
    buffer = [pointFromEvent(e)];
    canvas.setPointerCapture(e.pointerId);
});

canvas.addEventListener("pointermove", (e) => {
    if (!drawing) return;
    buffer.push(pointFromEvent(e));
    if (!flushTimer) flushTimer = setTimeout(() => { flushTimer = null; flushBuffer(); }, BATCH_MS);
});

function stopDrawing() {
    if (!drawing) return;
    drawing = false;
    flushBuffer();
    buffer = [];
}

canvas.addEventListener("pointerup",     stopDrawing);
canvas.addEventListener("pointercancel", stopDrawing);
canvas.addEventListener("pointerleave",  stopDrawing);

// ---------- create / join / clear / delete ----------

createForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    clearError(createError);
    const fd = new FormData(createForm);
    const user = (fd.get("username") || "").toString().trim();
    try {
        const d = await createDashboard({
            width:   parseInt(fd.get("width"), 10),
            height:  parseInt(fd.get("height"), 10),
            username: user,
        });
        openDashboard(d, user);
    } catch (err) { showError(createError, err.message); }
});

joinForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    clearError(joinError);
    const user = (joinUsername.value || "").trim();
    const id = new URLSearchParams(window.location.search).get("id");
    if (!id) { showError(joinError, "No dashboard id in URL."); return; }
    try {
        const d = await loadDashboard(id);
        openDashboard(d, user);
    } catch (err) { showError(joinError, err.message); }
});

copyLinkBtn.addEventListener("click", async () => {
    await navigator.clipboard.writeText(window.location.href);
    copyLinkBtn.textContent = "Copied!";
    setTimeout(() => (copyLinkBtn.textContent = "Copy share link"), 1500);
});

clearBtn.addEventListener("click", async () => {
    if (!confirm("Clear all strokes on this dashboard? This cannot be undone.")) return;
    try {
        await api("POST", "/api/dashboards/" + dashboard.id + "/clear");
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        lastRenderedOrdinal = 0;
    } catch (err) { alert("Clear failed: " + err.message); }
});

deleteBtn.addEventListener("click", async () => {
    if (!confirm("Delete this dashboard permanently?")) return;
    try {
        await api("DELETE", "/api/dashboards/" + dashboard.id);
        if (stompClient) try { stompClient.deactivate(); } catch {}
        stompClient = null;
        dashboard = null;
        username = null;
        currentUserEl.textContent = "";
        const url = new URL(window.location.href);
        url.searchParams.delete("id");
        window.history.replaceState({}, "", url);
        showPanel(createPanel);
    } catch (err) { alert("Delete failed: " + err.message); }
});

// ---------- bootstrap ----------

(function init() {
    const remembered = recalledUsername();
    createUsername.value = remembered;
    joinUsername.value = remembered;

    const id = new URLSearchParams(window.location.search).get("id");
    if (id) {
        showPanel(joinPanel);
    } else {
        showPanel(createPanel);
    }
})();
