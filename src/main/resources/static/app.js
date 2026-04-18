"use strict";

/**
 * Minimal vanilla client for the collaborative drawing dashboard.
 *
 * Identity model (anonymous):
 *   - On first visit, a random userName is generated (e.g. "user-a3f12b") and
 *     stored in localStorage. It persists across reloads but is not verified
 *     by the server — it is a display handle only.
 *
 * Flow:
 *   1. Ensure a local userName; create a dashboard (POST /api/dashboards)
 *      or open one via ?id=<uuid>.
 *   2. Connect STOMP-over-SockJS to /ws (no auth).
 *   3. Subscribe /topic/dashboard/{id}; fetch /history and replay; handle live frames.
 *   4. Batch pointer events every BATCH_MS and publish to /app/draw/{id}
 *      with our userName stamped in the body.
 */

const BATCH_MS = 50;
const USERNAME_KEY = "dashboard.username";

// ---------- DOM ----------

const statusEl       = document.getElementById("status");
const createPanel    = document.getElementById("create-panel");
const drawPanel      = document.getElementById("draw-panel");
const createForm     = document.getElementById("create-form");
const canvas         = document.getElementById("canvas");
const ctx            = canvas.getContext("2d");
const colorInput     = document.getElementById("color");
const thicknessInp   = document.getElementById("thickness");
const dashboardIdEl  = document.getElementById("dashboard-id");
const copyLinkBtn    = document.getElementById("copy-link");
const clearBtn       = document.getElementById("clear-btn");
const deleteBtn      = document.getElementById("delete-btn");
const currentUserEl  = document.getElementById("current-user");

// ---------- identity ----------

function generateUsername() {
    // 6 hex chars is plenty for a per-browser display handle.
    const rand = Math.floor(Math.random() * 0xffffff).toString(16).padStart(6, "0");
    return "user-" + rand;
}

function getUsername() {
    let name = localStorage.getItem(USERNAME_KEY);
    if (!name) {
        name = generateUsername();
        localStorage.setItem(USERNAME_KEY, name);
    }
    return name;
}

// ---------- session state ----------

let stompClient = null;
let dashboard   = null;
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

function showCreatePanel() {
    createPanel.classList.remove("hidden");
    drawPanel.classList.add("hidden");
}

// ---------- dashboard ----------

async function createDashboard(payload) { return api("POST", "/api/dashboards", payload); }
async function loadDashboard(id)        { return api("GET",  "/api/dashboards/" + id); }

function openDashboard(d) {
    dashboard = d;
    dashboardIdEl.textContent = d.id;
    canvas.width  = d.width;
    canvas.height = d.height;

    // Reset replay state (relevant when switching dashboards without full reload)
    lastRenderedOrdinal = 0;
    replayDone = false;
    liveBuffer = [];
    ctx.clearRect(0, 0, canvas.width, canvas.height);

    createPanel.classList.add("hidden");
    drawPanel.classList.remove("hidden");

    const url = new URL(window.location.href);
    url.searchParams.set("id", d.id);
    window.history.replaceState({}, "", url);

    connectStomp();
}

// ---------- live stream ----------

function handleStroke(stroke) {
    if (stroke.userId === getUsername()) return;             // skip own echo
    if (typeof stroke.ordinal === "number") {
        if (stroke.ordinal <= lastRenderedOrdinal) return;    // already drawn via history
        lastRenderedOrdinal = stroke.ordinal;
    }
    renderStroke(stroke);
}

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
        reconnectDelay: 3000,
        onConnect: () => {
            setStatus("connected");
            stompClient.subscribe("/topic/dashboard/" + dashboard.id, (frame) => {
                try {
                    const stroke = JSON.parse(frame.body);
                    if (!replayDone) { liveBuffer.push(stroke); return; }
                    handleStroke(stroke);
                } catch (e) { console.warn("Bad frame", e); }
            });
            replayHistory();
        },
        onStompError: (f) => {
            console.error("STOMP error", f.headers["message"], f.body);
            setStatus("disconnected", "error");
        },
        onWebSocketClose: () => setStatus("disconnected"),
    });
    stompClient.activate();
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
        userId: getUsername(),
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

// ---------- create / clear / delete ----------

createForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    const fd = new FormData(createForm);
    try {
        const d = await createDashboard({
            width:   parseInt(fd.get("width"), 10),
            height:  parseInt(fd.get("height"), 10),
        });
        openDashboard(d);
    } catch (err) { alert(err.message); }
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
        const url = new URL(window.location.href);
        url.searchParams.delete("id");
        window.history.replaceState({}, "", url);
        showCreatePanel();
    } catch (err) { alert("Delete failed: " + err.message); }
});

// ---------- bootstrap ----------

(async function init() {
    currentUserEl.textContent = getUsername();
    const id = new URLSearchParams(window.location.search).get("id");
    if (id) {
        try {
            openDashboard(await loadDashboard(id));
            return;
        } catch (err) {
            console.warn("Failed to open dashboard from URL", err);
        }
    }
    showCreatePanel();
})();
