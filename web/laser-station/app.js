const $ = (id) => document.getElementById(id);

const els = {
  endpoint: $("endpointInput"),
  token: $("tokenInput"),
  wsState: $("wsState"),
  saveConnection: $("saveConnectionBtn"),
  connectWs: $("connectWsBtn"),
  stationSelect: $("stationSelect"),
  stationName: $("stationNameInput"),
  stationCameraCount: $("stationCameraCount"),
  addStation: $("addStationBtn"),
  deleteStation: $("deleteStationBtn"),
  cameraList: $("cameraList"),
  cameraName: $("cameraNameInput"),
  cameraIp: $("cameraIpInput"),
  cameraRole: $("cameraRoleInput"),
  addCamera: $("addCameraBtn"),
  keepRatio: $("keepRatioInput"),
  startScan: $("startScanBtn"),
  scanMeta: $("scanMeta"),
  scanBanner: $("scanBanner"),
  scanBannerTitle: $("scanBannerTitle"),
  scanBannerDetail: $("scanBannerDetail"),
  measurePanel: $("measurePanel"),
  measureCompliance: $("measureCompliance"),
  measureBody: $("measureBody"),
  canvas: $("cloudCanvas"),
  markerLayer: $("markerLayer"),
  emptyHint: $("emptyHint"),
  activeCameraLabel: $("activeCameraLabel"),
  cloudTitle: $("cloudTitle"),
  cloudHint: $("cloudHint"),
  pointSize: $("pointSizeInput"),
  pointBudget: $("pointBudgetInput"),
  resetView: $("resetViewBtn"),
  deviceSelect: $("deviceSelect"),
  refreshDevice: $("refreshDeviceBtn"),
  deviceStatus: $("deviceStatus"),
  scanSpeed: $("scanSpeedInput"),
  zeroSpeed: $("zeroSpeedInput"),
  scanStart: $("scanStartInput"),
  scanAngle: $("scanAngleInput"),
  scanStop: $("scanStopInput"),
  scanAngleHint: $("scanAngleHint"),
  watchingAngle: $("watchingAngleInput"),
  ghost: $("ghostInput"),
  cameraFps: $("cameraFpsInput"),
  zoneMin: $("zoneMinInput"),
  zoneMax: $("zoneMaxInput"),
  applySettings: $("applySettingsBtn"),
  autoCalibBtn: $("autoCalibBtn"),
  autoCalibHint: $("autoCalibHint"),
  autoCalibResult: $("autoCalibResult"),
  markerLenMm: $("markerLenMm"),
  openFraming: $("openFramingBtn"),
  framingOverlay: $("framingOverlay"),
  closeFraming: $("closeFramingBtn"),
  runFraming: $("runFramingBtn"),
  framingStop: $("framingStopBtn"),
  framingStatus: $("framingStatus"),
  framingMarkerLen: $("framingMarkerLen"),
  framingSpeed: $("framingSpeed"),
  framingAStart: $("framingAStart"),
  framingAStop: $("framingAStop"),
  framingBStart: $("framingBStart"),
  framingBStop: $("framingBStop"),
  framingCanvasA: $("framingCanvasA"),
  framingCanvasB: $("framingCanvasB"),
  framingStripA: $("framingStripA"),
  framingStripB: $("framingStripB"),
  framingCountA: $("framingCountA"),
  framingCountB: $("framingCountB"),
  framingResult: $("framingResult"),
  camPreviewA: $("camPreviewA"),
  camPreviewB: $("camPreviewB"),
  camCanvasA: $("camCanvasA"),
  camCanvasB: $("camCanvasB"),
  camStatusA: $("camStatusA"),
  camStatusB: $("camStatusB"),
  camToggleA: $("camToggleA"),
  camToggleB: $("camToggleB"),
  calibHint: $("calibHint"),
  startCalib: $("startCalibBtn"),
  undoCalib: $("undoCalibBtn"),
  undoCalibView: $("undoCalibViewBtn"),
  clearCalib: $("clearCalibBtn"),
  clearCalibView: $("clearCalibViewBtn"),
  calibToolbar: $("calibToolbar"),
  solveCalib: $("solveCalibBtn"),
  calibPairs: $("calibPairs"),
  calibResult: $("calibResult"),
  regionHint: $("regionHint"),
  startRegion: $("startRegionBtn"),
  finishRegion: $("finishRegionBtn"),
  undoRegion: $("undoRegionBtn"),
  clearRegion: $("clearRegionBtn"),
  toggleRegionClip: $("toggleRegionClipBtn"),
  regionPoints: $("regionPoints"),
  drawer: $("configDrawer"),
  drawerBackdrop: $("drawerBackdrop"),
  closeConfig: $("closeConfigBtn"),
  toast: $("toast"),
};

const STORE_KEY = "gomob.laserStation.web.v1";
const DEFAULT_ENDPOINT = "http://127.0.0.1:18828";
const VIEW_DEFAULT_DISTANCE = 3.0;
const VIEW_MIN_DISTANCE = 0.03;
const VIEW_MAX_DISTANCE = 80;
const VIEW_MIN_PAN_DISTANCE = 0.75;
const VIEW_NEAR_PLANE = 0.001;
const VIEW_FAR_PLANE = 120;
const ROAM_DEFAULT_POS = [0, -1200, 1600];
const ROAM_DEFAULT_YAW = 0;
const ROAM_DEFAULT_PITCH = 0;
const ROAM_EYE_HEIGHT_MM = 1600;
const ROAM_MIN_EYE_HEIGHT_MM = 900;
const ROAM_MAX_EYE_HEIGHT_MM = 2200;
const ROAM_WALK_SPEED = 1100;
const ROAM_FAST_MULTIPLIER = 2.4;
const ROAM_LOOK_SENSITIVITY = 0.0032;
const ROAM_FOV_RAD = 50 * Math.PI / 180;
const ROAM_NEAR_PLANE_MM = 50;
const ROAM_MIN_FAR_MM = 6000;
const DEVICE_STATUS_POLL_MS = 1200;
const ACTIVE_SCAN_POLL_MS = 1200;
const CALIB_PICK_MAX_SCAN_POINTS = 1_500_000;
const CALIB_PICK_RADIUS_PX = 36;
const CALIB_CLICK_MAX_MOVE_PX = 5;
const INTERACTION_SETTLE_MS = 180;
const WEBGL_CONTEXT_NAMES = ["webgl2", "webgl", "experimental-webgl"];
const WEBGL_CONTEXT_OPTIONS = [
  {
    antialias: false,
    depth: true,
    alpha: false,
    stencil: false,
    preserveDrawingBuffer: false,
    powerPreference: "high-performance",
    failIfMajorPerformanceCaveat: false,
  },
  { antialias: false, depth: true, failIfMajorPerformanceCaveat: false },
  { antialias: false, depth: false, failIfMajorPerformanceCaveat: false },
  null,
];

class PointCloud {
  constructor(maxPoints) {
    this.maxPoints = maxPoints;
    this.data = new Float32Array(maxPoints * 3);
    this.colors = new Uint8Array(maxPoints * 3);
    this.count = 0;
    this.hasColor = false;
    this.dirty = true;
    this.colorDirty = true;
    this.resetBounds();
  }

  reset(maxPoints = this.maxPoints) {
    this.maxPoints = maxPoints;
    this.data = new Float32Array(maxPoints * 3);
    this.colors = new Uint8Array(maxPoints * 3);
    this.count = 0;
    this.hasColor = false;
    this.dirty = true;
    this.colorDirty = true;
    this.resetBounds();
  }

  resetBounds() {
    this.min = [Infinity, Infinity, Infinity];
    this.max = [-Infinity, -Infinity, -Infinity];
  }

  append(points, colors = null) {
    const add = Math.floor(points.length / 3);
    if (add <= 0) return;
    while (this.count + add > this.maxPoints && this.count > 1) {
      this.decimateHalf();
    }
    const room = Math.max(0, this.maxPoints - this.count);
    const take = Math.min(add, room);
    let dst = this.count * 3;
    for (let i = 0; i < take * 3; i += 3) {
      const x = Number(points[i]);
      const y = Number(points[i + 1]);
      const z = Number(points[i + 2]);
      this.data[dst++] = x;
      this.data[dst++] = y;
      this.data[dst++] = z;
      if (colors && colors.length >= i + 3) {
        const ci = this.count * 3 + i;
        this.colors[ci] = colors[i];
        this.colors[ci + 1] = colors[i + 1];
        this.colors[ci + 2] = colors[i + 2];
        this.hasColor = true;
      }
      if (x < this.min[0]) this.min[0] = x;
      if (y < this.min[1]) this.min[1] = y;
      if (z < this.min[2]) this.min[2] = z;
      if (x > this.max[0]) this.max[0] = x;
      if (y > this.max[1]) this.max[1] = y;
      if (z > this.max[2]) this.max[2] = z;
    }
    this.count += take;
    this.dirty = true;
    this.colorDirty = true;
  }

  replace(points, colors = null) {
    this.reset(this.maxPoints);
    const total = Math.floor(points.length / 3);
    if (total <= 0) return;
    const take = Math.min(total, this.maxPoints);
    const stride = total > this.maxPoints ? total / this.maxPoints : 1;
    let dst = 0;
    for (let j = 0; j < take; j++) {
      const src = Math.floor(j * stride) * 3;
      const x = Number(points[src]);
      const y = Number(points[src + 1]);
      const z = Number(points[src + 2]);
      if (!Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(z)) continue;
      this.data[dst++] = x;
      this.data[dst++] = y;
      this.data[dst++] = z;
      if (colors && colors.length >= src + 3) {
        const ci = dst - 3;
        this.colors[ci] = colors[src];
        this.colors[ci + 1] = colors[src + 1];
        this.colors[ci + 2] = colors[src + 2];
        this.hasColor = true;
      }
      if (x < this.min[0]) this.min[0] = x;
      if (y < this.min[1]) this.min[1] = y;
      if (z < this.min[2]) this.min[2] = z;
      if (x > this.max[0]) this.max[0] = x;
      if (y > this.max[1]) this.max[1] = y;
      if (z > this.max[2]) this.max[2] = z;
    }
    this.count = dst / 3;
    this.dirty = true;
    this.colorDirty = true;
  }

  decimateHalf() {
    let dst = 0;
    this.resetBounds();
    for (let i = 0; i < this.count; i += 2) {
      const src = i * 3;
      const x = this.data[src];
      const y = this.data[src + 1];
      const z = this.data[src + 2];
      this.data[dst++] = x;
      this.data[dst++] = y;
      this.data[dst++] = z;
      if (this.hasColor) {
        this.colors[dst - 3] = this.colors[src];
        this.colors[dst - 2] = this.colors[src + 1];
        this.colors[dst - 1] = this.colors[src + 2];
      }
      if (x < this.min[0]) this.min[0] = x;
      if (y < this.min[1]) this.min[1] = y;
      if (z < this.min[2]) this.min[2] = z;
      if (x > this.max[0]) this.max[0] = x;
      if (y > this.max[1]) this.max[1] = y;
      if (z > this.max[2]) this.max[2] = z;
    }
    this.count = Math.floor(this.count / 2) + (this.count % 2);
    this.dirty = true;
    this.colorDirty = true;
  }

  filter(predicate) {
    if (!this.count) return 0;
    let dst = 0;
    let kept = 0;
    const hadColor = this.hasColor;
    this.resetBounds();
    for (let i = 0; i < this.count; i++) {
      const src = i * 3;
      const x = this.data[src];
      const y = this.data[src + 1];
      const z = this.data[src + 2];
      if (!predicate(x, y, z)) continue;
      this.data[dst] = x;
      this.data[dst + 1] = y;
      this.data[dst + 2] = z;
      if (hadColor) {
        this.colors[dst] = this.colors[src];
        this.colors[dst + 1] = this.colors[src + 1];
        this.colors[dst + 2] = this.colors[src + 2];
      }
      if (x < this.min[0]) this.min[0] = x;
      if (y < this.min[1]) this.min[1] = y;
      if (z < this.min[2]) this.min[2] = z;
      if (x > this.max[0]) this.max[0] = x;
      if (y > this.max[1]) this.max[1] = y;
      if (z > this.max[2]) this.max[2] = z;
      dst += 3;
      kept++;
    }
    this.count = kept;
    this.dirty = true;
    this.colorDirty = true;
    return kept;
  }
}

function createViewState() {
  return {
    yaw: -0.75,
    pitch: 0.45,
    distance: VIEW_DEFAULT_DISTANCE,
    pan: [0, 0, 0],
    roamPos: [...ROAM_DEFAULT_POS],
    roamYaw: ROAM_DEFAULT_YAW,
    roamPitch: ROAM_DEFAULT_PITCH,
    roamRadius: 1500,
    roamFar: ROAM_MIN_FAR_MM,
    roamNeedsFit: true,
    center: [0, 0, 0],
    scale: 0.001,
    mvp: mat4Identity(),
  };
}

const app = {
  endpoint: DEFAULT_ENDPOINT,
  token: "",
  ws: null,
  stations: [],
  activeStationId: "",
  selectedCameraId: "",
  activeSessionKey: "",
  activeScanId: null,
  scanState: "idle",
  cloudMode: "split",
  finalCloudsLoading: false,
  finalCloudsLoaded: false,
  deferredFinalCloudPayload: null,
  restoringActiveScan: false,
  realtimeBacklog: [],
  restoreSerial: 0,
  fusedCount: 0,
  fusionUnavailable: false,
  measure: null,
  overlay: null,
  deviceStatuses: { a: null, b: null },
  deviceInfos: { a: null, b: null },
  liveAngles: { a: null, b: null },
  camPreview: { a: { hasFrame: false, collapsed: false }, b: { hasFrame: false, collapsed: false } },
  statusPolling: false,
  statusTimer: null,
  scanStatusPolling: false,
  scanStatusTimer: null,
  clouds: [new PointCloud(1_200_000), new PointCloud(1_200_000)],
  fusedCloud: new PointCloud(1_200_000),
  gl: null,
  ctx2d: null,
  renderer: "",
  webglContextName: "",
  webglStatus: "",
  webglDetail: "",
  webglEventsBound: false,
  program: null,
  buffers: [],
  colorBuffers: [],
  renderPanes: [],
  overlayKey: "",
  overlayDirty: true,
  renderDirty: true,
  wasInteractive: false,
  canvasSizeKey: "",
  views: {
    a: createViewState(),
    b: createViewState(),
    fused: createViewState(),
  },
  controlMode: "orbit",
  viewPreset: "free", // top|side|free；top/side 固化（拖动只平移不旋转，不会变成自由）
  activeViewKey: "a",
  dragging: false,
  dragButton: 0,
  dragPaneKey: "",
  lastPointer: [0, 0],
  clickCandidate: null,
  suppressNextClick: false,
  interactiveUntil: 0,
  keys: new Set(),
  lastFrameTs: 0,
  calibration: {
    enabled: false,
    nextUnit: 0,
    pendingA: null,
    pairs: [],
    result: null,
  },
  region: {
    enabled: false,
    points: [],
    closed: false,
    clipEnabled: false,
  },
};

function makeId(prefix) {
  return `${prefix}-${Math.random().toString(16).slice(2)}-${Date.now().toString(16)}`;
}

function defaultStations() {
  const id = makeId("station");
  return [{
    id,
    name: "默认扫描工位",
    cameras: [
      { id: makeId("cam"), name: "镜头 A", ip: "192.168.9.101", role: "a" },
      { id: makeId("cam"), name: "镜头 B", ip: "192.168.9.102", role: "b" },
    ],
  }];
}

function loadState() {
  try {
    const raw = JSON.parse(localStorage.getItem(STORE_KEY) || "{}");
    app.endpoint = raw.endpoint || DEFAULT_ENDPOINT;
    app.token = raw.token || "";
    app.stations = Array.isArray(raw.stations) && raw.stations.length ? raw.stations : defaultStations();
    app.activeStationId = raw.activeStationId || app.stations[0].id;
    app.selectedCameraId = raw.selectedCameraId || app.stations[0].cameras[0]?.id || "";
    app.pointBudget = Number(raw.pointBudget) || 1_200_000;
    app.keepRatio = Number(raw.keepRatio) || 1;
  } catch {
    app.stations = defaultStations();
    app.activeStationId = app.stations[0].id;
    app.selectedCameraId = app.stations[0].cameras[0]?.id || "";
    app.pointBudget = 1_200_000;
    app.keepRatio = 1;
  }
}

function saveState() {
  localStorage.setItem(STORE_KEY, JSON.stringify({
    endpoint: app.endpoint,
    token: app.token,
    stations: app.stations,
    activeStationId: app.activeStationId,
    selectedCameraId: app.selectedCameraId,
    pointBudget: app.pointBudget,
    keepRatio: app.keepRatio,
  }));
}

function stationCalibrationSignature() {
  return {
    unitAIp: cameraByRole("a")?.ip || "",
    unitBIp: cameraByRole("b")?.ip || "",
  };
}

function sanitizePoint(p) {
  if (!Array.isArray(p) || p.length < 3) return null;
  const point = [Number(p[0]), Number(p[1]), Number(p[2])];
  return point.every(Number.isFinite) ? point : null;
}

function saveCalibrationState() {
  const s = station();
  if (!s) return;
  const pairs = app.calibration.pairs
    .map((pair, i) => {
      const a = sanitizePoint(pair.a);
      const b = sanitizePoint(pair.b);
      return a && b ? { label: pair.label || `P${i + 1}`, a, b } : null;
    })
    .filter(Boolean);
  const pendingA = sanitizePoint(app.calibration.pendingA);
  if (!pairs.length && !pendingA && !app.calibration.result) {
    delete s.calibration;
    saveState();
    return;
  }
  s.calibration = {
    ...stationCalibrationSignature(),
    pairs,
    pendingA,
    nextUnit: pendingA ? 1 : 0,
    result: app.calibration.result || null,
  };
  saveState();
}

function restoreCalibrationState() {
  const saved = station()?.calibration;
  const sig = stationCalibrationSignature();
  const validForStation = saved
    && (!saved.unitAIp || saved.unitAIp === sig.unitAIp)
    && (!saved.unitBIp || saved.unitBIp === sig.unitBIp);
  if (!validForStation) {
    app.calibration.pairs = [];
    app.calibration.pendingA = null;
    app.calibration.nextUnit = 0;
    app.calibration.result = null;
    renderCalibration();
    return;
  }
  app.calibration.pairs = Array.isArray(saved.pairs)
    ? saved.pairs.map((pair, i) => {
      const a = sanitizePoint(pair.a);
      const b = sanitizePoint(pair.b);
      return a && b ? { label: pair.label || `P${i + 1}`, a, b } : null;
    }).filter(Boolean)
    : [];
  app.calibration.pendingA = sanitizePoint(saved.pendingA);
  app.calibration.nextUnit = app.calibration.pendingA ? 1 : 0;
  app.calibration.result = Array.isArray(saved.result?.matrix) ? saved.result : null;
  renderCalibration();
}

function saveRegionState() {
  const s = station();
  if (!s) return;
  const points = app.region.points.map(sanitizePoint).filter(Boolean);
  const closed = app.region.closed && points.length >= 3;
  if (!points.length) {
    delete s.regionCalibration;
    saveState();
    return;
  }
  s.regionCalibration = {
    ...stationCalibrationSignature(),
    points,
    closed,
    clipEnabled: closed && Boolean(app.region.clipEnabled),
  };
  saveState();
}

function restoreRegionState() {
  const saved = station()?.regionCalibration;
  const sig = stationCalibrationSignature();
  const validForStation = saved
    && (!saved.unitAIp || saved.unitAIp === sig.unitAIp)
    && (!saved.unitBIp || saved.unitBIp === sig.unitBIp);
  if (!validForStation) {
    app.region.points = [];
    app.region.closed = false;
    app.region.clipEnabled = false;
    app.region.enabled = false;
    renderRegionCalibration();
    return;
  }
  app.region.points = Array.isArray(saved.points) ? saved.points.map(sanitizePoint).filter(Boolean) : [];
  app.region.closed = Boolean(saved.closed) && app.region.points.length >= 3;
  app.region.clipEnabled = app.region.closed && Boolean(saved.clipEnabled);
  app.region.enabled = false;
  renderRegionCalibration();
}

function regionFilterForRequest() {
  if (!app.region.clipEnabled || !app.region.closed) return null;
  const points = app.region.points.map(sanitizePoint).filter(Boolean);
  if (points.length < 3) return null;
  const filter = { enabled: true, points };
  const siteResult = Array.isArray(app.calibration.result?.matrix) ? app.calibration.result : null;
  if (siteResult) filter.b_to_a = siteResult.matrix;
  return filter;
}

async function bootstrapStationSession() {
  try {
    const res = await fetch("/station/session", { cache: "no-store" });
    if (!res.ok) return;
    const session = await res.json();
    if (session.gateway) app.endpoint = session.gateway;
    if (session.access_token) app.token = session.access_token;
    els.endpoint.value = app.endpoint;
    els.token.value = app.token;
    saveState();
  } catch {
    // 静态文件方式打开时没有 /station/session，保留手动 Gateway token。
  }
}

function station() {
  return app.stations.find((s) => s.id === app.activeStationId) || app.stations[0];
}

function selectedCamera() {
  const s = station();
  return s?.cameras.find((c) => c.id === app.selectedCameraId) || s?.cameras[0] || null;
}

function cameraByRole(role) {
  return station()?.cameras.find((c) => c.role === role) || null;
}

function showToast(msg) {
  els.toast.textContent = msg;
  els.toast.classList.add("show");
  clearTimeout(showToast.timer);
  showToast.timer = setTimeout(() => els.toast.classList.remove("show"), 2600);
}

function renderStations() {
  const currentStation = station();
  els.stationSelect.innerHTML = "";
  for (const s of app.stations) {
    const option = document.createElement("option");
    option.value = s.id;
    option.textContent = s.name;
    els.stationSelect.append(option);
  }
  els.stationSelect.value = currentStation.id;
  els.stationName.value = currentStation.name;
  if (els.stationCameraCount) {
    els.stationCameraCount.textContent = `${currentStation.cameras.length} 台`;
  }

  els.cameraList.innerHTML = "";
  for (const cam of currentStation.cameras) {
    const item = document.createElement("div");
    item.className = `camera-item ${cam.id === app.selectedCameraId ? "active" : ""}`;
    item.innerHTML = `
      <div><strong>${escapeHtml(cam.name)}</strong><br><span>${escapeHtml(cam.ip)} · ${roleName(cam.role)}</span></div>
      <button data-delete-camera="${cam.id}">删除</button>
    `;
    item.addEventListener("click", (ev) => {
      if (ev.target.closest("button")) return;
      app.selectedCameraId = cam.id;
      saveState();
      renderStations();
    });
    els.cameraList.append(item);
  }

  for (const btn of els.cameraList.querySelectorAll("[data-delete-camera]")) {
    btn.addEventListener("click", () => {
      const id = btn.getAttribute("data-delete-camera");
      currentStation.cameras = currentStation.cameras.filter((c) => c.id !== id);
      app.selectedCameraId = currentStation.cameras[0]?.id || "";
      saveState();
      renderStations();
    });
  }

  els.deviceSelect.innerHTML = "";
  for (const cam of currentStation.cameras) {
    const option = document.createElement("option");
    option.value = cam.id;
    option.textContent = `${cam.name} · ${cam.ip}`;
    els.deviceSelect.append(option);
  }
  els.deviceSelect.value = selectedCamera()?.id || "";
  const camA = cameraByRole("a");
  const camB = cameraByRole("b");
  els.activeCameraLabel.textContent = `A ${camA?.ip || "-"} / B ${camB?.ip || "-"}`;
}

function roleName(role) {
  if (role === "a") return "镜头 A";
  if (role === "b") return "镜头 B";
  return "辅助";
}

function escapeHtml(text) {
  return String(text).replace(/[&<>"']/g, (m) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    "\"": "&quot;",
    "'": "&#039;",
  }[m]));
}

function renderScanMeta() {
  const status = scanStateView(app.scanState);
  const rows = [
    ["状态", status.label],
    ["A", cameraStatusSummary("a")],
    ["B", cameraStatusSummary("b")],
    ["scan_id", app.activeScanId || "-"],
  ];
  els.scanMeta.innerHTML = rows.map(([k, v]) => `<span>${k}</span><strong>${escapeHtml(v)}</strong>`).join("");
  renderScanActionButton();
  const counts = [app.clouds[0].count, app.clouds[1].count];
  const visibleCount = app.cloudMode === "fused" ? app.fusedCloud.count : counts[0] + counts[1];
  const fusionUnavailable = app.cloudMode === "fused" && app.fusionUnavailable;
  els.emptyHint.classList.toggle("hidden", visibleCount > 0);
  els.emptyHint.textContent = fusionUnavailable ? "未标定无法融合" : "等待点云数据";
  const title = app.cloudMode === "fused" ? "融合点云" : "分镜点云";
  els.cloudTitle.textContent = title;
  const controlHint = annotationActive()
    ? "标注中点云已冻结"
    : app.controlMode === "roam"
    ? "WASD 行走 · 左键转身/抬头"
    : "右键平移 · 左键旋转 · 滚轮缩放";
  const rendererHint = app.renderer === "webgl"
    ? `WebGL ${app.webglContextName || ""}`.trim()
    : (app.renderer === "2d" ? "Canvas 兼容渲染" : "");
  const hintParts = [controlHint];
  if (rendererHint) hintParts.push(rendererHint);
  if (app.webglStatus && app.renderer === "2d") hintParts.push(app.webglStatus);
  els.cloudHint.textContent = visibleCount > 0
    ? `${visibleCount.toLocaleString()} 点 · ${hintParts.join(" · ")}`
    : (fusionUnavailable ? "未标定无法融合" : (app.cloudMode === "fused" ? "等待融合点云" : `等待分镜点云${rendererHint ? ` · ${rendererHint}` : ""}`));
  updateScanBanner();
}

// 车辆外廓测量面板：随 scan.fusion_done 到达的 LWH + 轴距/前后悬 + GB7258 合规。
// 测量无效（未标定/点云退化）则隐藏；不编造数字。单位 mm。
function renderMeasure() {
  const p = app.measure;
  const panel = els.measurePanel;
  if (!panel) return;
  const done = app.scanState === "done" && Boolean(app.activeScanId);
  // 融合成功但测不出：面板仍出现并给原因，让"自动测量"可见、可排查（不静默隐藏）。
  if ((!p || !p.measure_valid)) {
    if (!done) { panel.hidden = true; return; }
    panel.hidden = false;
    let reason = "未能自动测量 · 请圈定车位框，或确保点云完整覆盖车辆";
    let badge = "需圈车位框";
    if (app.fusionUnavailable) { reason = "工位未标定，无法融合测量"; badge = "未标定"; }
    els.measureBody.innerHTML = `<span>状态</span><strong>${escapeHtml(reason)}</strong>`;
    els.measureCompliance.textContent = badge;
    els.measureCompliance.className = "measure-badge warn";
    return;
  }
  panel.hidden = false;
  const mm = (v) => `${Math.round(Number(v) || 0).toLocaleString()} mm`;
  const rows = [
    ["车长", mm(p.length_mm)],
    ["车宽", mm(p.width_mm)],
    ["车高", mm(p.height_mm)],
  ];
  if (p.axle_valid) {
    const wb = Array.isArray(p.wheelbases_mm) ? p.wheelbases_mm.map((v) => Math.round(v)).join(" / ") : "-";
    rows.push([`轴距(${p.num_axles}轴)`, `${wb} mm`]);
    rows.push(["总轴距", mm(p.total_wheelbase_mm)]);
    rows.push(["前悬/后悬", `${Math.round(p.front_overhang_mm)} / ${Math.round(p.rear_overhang_mm)} mm`]);
  }
  if (p.has_cargo_box) {
    rows.push(["货箱长×宽", `${Math.round(p.box_outer_length_mm)} × ${Math.round(p.box_outer_width_mm)} mm`]);
    rows.push(["货箱深", mm(p.box_depth_mm)]);
  }
  let html = rows
    .map(([k, v]) => `<span>${escapeHtml(k)}</span><strong>${escapeHtml(v)}</strong>`)
    .join("");
  if (app.overlay && app.overlay.valid && app.cloudMode === "fused") {
    html += `<span>叠加</span><strong class="ov-legend">` +
      `<i class="ov-dot ov-v"></i>车体 <i class="ov-dot ov-c"></i>货箱 <i class="ov-dot ov-a"></i>轴</strong>`;
  }
  els.measureBody.innerHTML = html;
  const ok = Boolean(p.compliant);
  const badge = els.measureCompliance;
  badge.textContent = ok ? "合规" : (Array.isArray(p.violations) && p.violations.length ? p.violations.join("、") : "超限");
  badge.className = `measure-badge ${ok ? "ok" : "bad"}`;
}

// 扫描进行中的醒目横幅：采集/融合/加载各状态 + 已用时 + 旋转 spinner。靠 1.2s 轮询驱动（不依赖实时 WS），
// 长扫描（采集慢 + 融合精修）有持续的“在动”反馈，避免被误判卡死而中途取消。
function updateScanBanner() {
  const b = els.scanBanner;
  if (!b) return;
  const state = normalizedScanState();
  const showing = !isScanTerminalState(state) ||
    (app.activeScanId && ["capturing", "scanning", "fusing", "processing"].includes(state));
  if (!showing) { b.hidden = true; return; }
  const sec = app.scanStartedAt ? Math.max(0, Math.floor((Date.now() - app.scanStartedAt) / 1000)) : 0;
  const mmss = `${String(Math.floor(sec / 60)).padStart(2, "0")}:${String(sec % 60).padStart(2, "0")}`;
  let title = "处理中…", detail = mmss, kind = "busy";
  if (["starting", "connecting"].includes(state)) {
    title = "启动中…"; detail = `连接设备 · 已用 ${mmss}`;
  } else if (["capturing", "scanning"].includes(state)) {
    const fa = app.liveFrames?.a || 0, fb = app.liveFrames?.b || 0;
    title = "采集中"; kind = "scan";
    detail = (fa || fb) ? `A ${fa.toLocaleString()} 帧 · B ${fb.toLocaleString()} 帧 · 已用 ${mmss}` : `云台扫掠中 · 已用 ${mmss}`;
  } else if (["fusing", "processing"].includes(state)) {
    title = "融合处理中，请稍候"; detail = `外参精修 + 拼合（约十几秒）· 已用 ${mmss}`;
  } else if (state === "loading_clouds") {
    title = "加载点云中…"; detail = `下载结果 · 已用 ${mmss}`;
  }
  els.scanBannerTitle.textContent = title;
  els.scanBannerDetail.textContent = detail;
  b.className = `scan-banner ${kind}`;
  b.hidden = false;
}

function scanStateView(state) {
  switch (String(state || "idle").toLowerCase()) {
    case "scanning":
    case "capturing":
      return { label: "采集中", kind: "scan" };
    case "fusing":
    case "loading_clouds":
    case "processing":
      return { label: "融合中", kind: "busy" };
    case "done":
    case "completed":
      return { label: "完成", kind: "ok" };
    case "error":
    case "failed":
      return { label: "错误", kind: "bad" };
    case "starting":
    case "connecting":
      return { label: "连接中", kind: "busy" };
    case "cancelled":
    case "canceled":
    case "idle":
    default:
      return { label: "就绪", kind: "idle" };
  }
}

function cameraStatusSummary(role) {
  const cam = cameraByRole(role);
  if (!cam) return "-";
  const status = app.deviceStatuses[role];
  const state = deviceStateLabel(status?.state || "");
  const angle = status?.angleDegs ?? app.liveAngles[role];
  const angleText = angle == null ? "--" : `${fmt2(angle)}°`;
  return `${state} ${angleText}`;
}

function normalizedScanState(state = app.scanState) {
  return String(state || "idle").toLowerCase();
}

function isScanTerminalState(state = app.scanState) {
  return ["idle", "done", "completed", "cancelled", "canceled", "failed", "error"].includes(normalizedScanState(state));
}

function isServerActiveScanState(state = app.scanState) {
  return !isScanTerminalState(state) || Boolean(app.activeScanId && ["capturing", "scanning", "fusing", "processing"].includes(normalizedScanState(state)));
}

function scanActionMode() {
  const state = normalizedScanState();
  if (["starting", "connecting", "loading_clouds"].includes(state)) return "busy";
  return isServerActiveScanState() ? "stop" : "start";
}

function renderScanActionButton() {
  const mode = scanActionMode();
  const loading = normalizedScanState() === "loading_clouds";
  els.startScan.textContent = mode === "stop" ? "结束扫描" : mode === "busy" ? (loading ? "加载中" : "启动中") : "开始扫描";
  els.startScan.disabled = mode === "busy";
  els.startScan.classList.toggle("danger", mode === "stop");
  els.startScan.classList.toggle("primary", mode !== "stop");
}

function setWsState(text, kind = "") {
  els.wsState.textContent = text;
  els.wsState.className = `state-pill ${kind}`;
}

function syncConnectionFromInputs(persist = false) {
  app.endpoint = els.endpoint.value.trim() || DEFAULT_ENDPOINT;
  app.token = els.token.value.trim();
  if (persist) saveState();
}

function endpointBase() {
  syncConnectionFromInputs();
  const raw = app.endpoint.replace(/\/+$/, "") || DEFAULT_ENDPOINT;
  return new URL(raw, window.location.origin).toString().replace(/\/+$/, "");
}

async function api(path, options = {}) {
  const base = endpointBase();
  const headers = new Headers(options.headers || {});
  if (app.token) headers.set("Authorization", `Bearer ${app.token}`);
  if (options.body && !(options.body instanceof FormData)) headers.set("Content-Type", "application/json");
  const res = await fetch(`${base}${path}`, { ...options, headers });
  const text = await res.text();
  let body = null;
  if (text) {
    try { body = JSON.parse(text); } catch { body = text; }
  }
  if (!res.ok) {
    const msg = body?.message || body?.error || text || `${res.status}`;
    throw new Error(msg);
  }
  return body;
}

async function apiBuffer(path) {
  const base = endpointBase();
  const headers = new Headers();
  if (app.token) headers.set("Authorization", `Bearer ${app.token}`);
  const res = await fetch(`${base}${path}`, { headers });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `${res.status}`);
  }
  return res.arrayBuffer();
}

function connectWs() {
  syncConnectionFromInputs(true);
  if (!app.token) {
    showToast("需要先填写 token");
    return Promise.reject(new Error("缺少 token"));
  }
  if (app.ws?.readyState === WebSocket.OPEN) {
    setWsState("实时已连接", "ok");
    return Promise.resolve();
  }
  if (app.ws) app.ws.close();
  const url = new URL(`${endpointBase()}/v1/ws`);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  url.searchParams.set("token", app.token);
  app.ws = new WebSocket(url.toString());
  attachWsMessageHandler();
  setWsState("连接中");
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("实时连接超时")), 3500);
    app.ws.onopen = () => {
      clearTimeout(timer);
      setWsState("实时已连接", "ok");
      resolve();
    };
    app.ws.onclose = () => {
      clearTimeout(timer);
      setWsState("实时已断开", "bad");
    };
    app.ws.onerror = () => {
      clearTimeout(timer);
      setWsState("实时错误", "bad");
      reject(new Error("实时连接失败"));
    };
  });
}

function attachWsMessageHandler() {
  if (!app.ws) return;
  app.ws.onmessage = (ev) => {
    try {
      handleRealtime(JSON.parse(ev.data));
    } catch (err) {
      console.warn("实时消息解析失败", err);
    }
  };
}

function handleRealtime(envelope) {
  const payload = envelope.payload || {};
  if (envelope.type === "laser.points") {
    if (app.activeSessionKey && payload.session_key !== app.activeSessionKey) return;
    const unit = Number(payload.unit);
    if (unit !== 0 && unit !== 1) return;
    const role = unit === 0 ? "a" : "b";
    const hAngle = finiteOrNull(payload.h_angle_deg ?? payload.hAngleDeg);
    if (hAngle != null) app.liveAngles[role] = hAngle;
    if (isCloudRefreshPaused()) {
      renderScanMeta();
      return;
    }
    const frame = clipRegionPointPayload({ unit, points: payload.points || [], colors: realtimeColors(payload) });
    if (app.restoringActiveScan) {
      app.realtimeBacklog.push(frame);
    }
    app.clouds[unit].append(frame.points, frame.colors);
    renderScanMeta();
    return;
  }
  if (envelope.type === "laser.status") {
    if (app.activeSessionKey && payload.session_key !== app.activeSessionKey) return;
    app.scanState = payload.state || app.scanState;
    renderScanMeta();
    if (payload.state === "done" && app.activeScanId && !app.finalCloudsLoaded && !app.finalCloudsLoading) {
      if (isCloudRefreshPaused()) {
        app.deferredFinalCloudPayload = { job_id: app.activeScanId };
        return;
      }
      setTimeout(() => {
        downloadFinalClouds({ job_id: app.activeScanId }).catch((err) => {
          console.warn("最终点云下载失败", err);
        });
      }, 600);
    }
    return;
  }
  if (envelope.type === "laser.frame") {
    if (!els.framingOverlay.hidden) {
      renderFramingFrame(payload); // 取景标定页打开 → 走胶片
    } else if (payload.session_key !== "site-framing") {
      renderScanPreview(payload); // 点云扫描页 → 相机实时小窗
    }
    return;
  }
  if (envelope.type === "scan.fusion_done" && payload.kind === "laser") {
    if (app.activeSessionKey && payload.session_key !== app.activeSessionKey) return;
    app.scanState = "done";
    app.fusionUnavailable = isRawScanPayload(payload);
    app.fusedCount = Number(payload.points || 0);
    app.measure = payload; // 始终留 payload：测得出显数字，测不出据此给原因
    app.overlay = payload.overlay || null; // 世界系车体框/货箱框/轴线，融合视图叠加
    app.overlayDirty = true;
    renderMeasure();
    renderScanMeta();
    if (isCloudRefreshPaused()) {
      app.deferredFinalCloudPayload = payload;
      return;
    }
    downloadFinalClouds(payload).catch((err) => {
      console.warn("最终点云下载失败", err);
      showToast(`最终点云下载失败：${err.message}`);
    });
  }
}

function realtimeColors(payload) {
  if (Array.isArray(payload.colors) || ArrayBuffer.isView(payload.colors)) {
    return payload.colors;
  }
  const rgb = payload.rgb;
  if (!Array.isArray(rgb) && !ArrayBuffer.isView(rgb)) return null;
  const out = new Uint8Array(rgb.length * 3);
  for (let i = 0; i < rgb.length; i++) {
    const c = Number(rgb[i]) >>> 0;
    out[i * 3] = (c >> 16) & 255;
    out[i * 3 + 1] = (c >> 8) & 255;
    out[i * 3 + 2] = c & 255;
  }
  return out;
}

function isRawScanPayload(payload) {
  if (!payload) return false;
  const alignMethod = String(payload.align_method || payload.alignMethod || "").toLowerCase();
  if (alignMethod === "raw") return true;
  if (payload.fusion_available === false || payload.fusionAvailable === false) return true;
  return false;
}

function annotationActive() {
  return Boolean(app.calibration.enabled || app.region.enabled);
}

function isCloudRefreshPaused() {
  return annotationActive();
}

function resumeDeferredCloudRefresh() {
  if (!app.deferredFinalCloudPayload || isCloudRefreshPaused()) return;
  const payload = app.deferredFinalCloudPayload;
  app.deferredFinalCloudPayload = null;
  downloadFinalClouds(payload).catch((err) => {
    console.warn("延后最终点云下载失败", err);
    showToast(`最终点云下载失败：${err.message}`);
  });
}

async function downloadFinalClouds(payload) {
  const scanId = Number(payload.job_id || app.activeScanId || 0);
  if (!scanId || app.finalCloudsLoading || app.finalCloudsLoaded) return;
  if (isCloudRefreshPaused()) {
    app.deferredFinalCloudPayload = payload;
    return;
  }
  app.finalCloudsLoading = true;
  app.scanState = "loading_clouds";
  const rawOnly = isRawScanPayload(payload) || app.fusionUnavailable;
  app.fusionUnavailable = rawOnly;
  if (rawOnly) {
    app.fusedCloud.reset(Number(els.pointBudget.value || 1_200_000));
    app.fusedCount = 0;
  }
  renderScanMeta();
  const items = [
    ["unit_a", app.clouds[0]],
    ["unit_b", app.clouds[1]],
  ];
  if (!rawOnly) items.push(["fused", app.fusedCloud]);
  const downloads = await Promise.allSettled(items.map(([name, cloud]) => downloadCloudInto(scanId, name, cloud)));
  const skipped = downloads.some((r) => r.status === "fulfilled" && r.value === false);
  if (skipped || isCloudRefreshPaused()) {
    app.deferredFinalCloudPayload = payload;
    app.scanState = "done";
    app.finalCloudsLoading = false;
    app.finalCloudsLoaded = false;
    renderScanMeta();
    return;
  }
  const failed = downloads.filter((r) => r.status === "rejected");
  app.scanState = failed.length === downloads.length ? "done" : "done";
  app.finalCloudsLoading = false;
  app.finalCloudsLoaded = failed.length !== downloads.length;
  if (app.finalCloudsLoaded) app.loadedCloudsScanId = scanId; // 记已展示的扫描，供完成时判是否需重载
  app.fusedCount = rawOnly ? 0 : (app.fusedCloud.count || app.fusedCount);
  renderScanMeta();
  if (failed.length === downloads.length) {
    throw failed[0].reason;
  }
  if (failed.length > 0) {
    showToast("部分最终点云下载失败，已显示可用点云");
    return;
  }
  if (rawOnly) showToast("当前工位未标定，已完成分镜点云采集，未执行融合");
}

async function downloadCloudInto(scanId, name, cloud) {
  const unit = name === "unit_a" ? 0 : name === "unit_b" ? 1 : 2;
  return downloadCloudFromPath(`/v1/scans/laser/${scanId}/cloud/${name}`, cloud, unit);
}

async function downloadCloudFromPath(path, cloud, unit = null) {
  const buffer = await apiBuffer(path);
  const parsed = await parsePcdAsync(buffer, cloud.maxPoints);
  if (isCloudRefreshPaused()) return false;
  const clipped = clipRegionPointPayload({ unit, points: parsed.points, colors: parsed.colors });
  cloud.replace(clipped.points, clipped.colors);
  return true;
}

function stationScanQuery() {
  const camA = cameraByRole("a");
  const camB = cameraByRole("b");
  if (!camA || !camB) return "";
  return `unit_a_ip=${encodeURIComponent(camA.ip)}&unit_b_ip=${encodeURIComponent(camB.ip)}`;
}

function clearActiveScanState() {
  app.activeScanId = null;
  app.activeSessionKey = "";
  app.scanState = "idle";
  app.finalCloudsLoading = false;
  app.finalCloudsLoaded = false;
  app.deferredFinalCloudPayload = null;
  app.restoringActiveScan = false;
  app.realtimeBacklog = [];
  app.fusedCount = 0;
  app.fusionUnavailable = false;
  app.liveAngles = { a: null, b: null };
  app.scanStartedAt = 0;
  app.liveFrames = { a: 0, b: 0 };
  app.camPreview.a.hasFrame = false;
  app.camPreview.b.hasFrame = false;
}

async function restoreActiveScan({ clearInactive = false, silent = false } = {}) {
  syncConnectionFromInputs();
  const query = stationScanQuery();
  if (!query || !app.token) {
    if (clearInactive) {
      clearActiveScanState();
      resetClouds();
    }
    return;
  }
  const serial = ++app.restoreSerial;
  let active;
  try {
    active = await api(`/v1/scans/laser/active?${query}`);
  } catch (err) {
    if (!silent) showToast(`恢复扫描失败：${err.message}`);
    return;
  }
  if (serial !== app.restoreSerial) return;
  if (!active?.active) {
    if (clearInactive) {
      clearActiveScanState();
      resetClouds();
    }
    return;
  }

  resetClouds();
  app.activeScanId = Number(active.scan_id || 0) || null;
  app.activeSessionKey = active.session_key || "";
  app.scanState = active.live_state || active.status || "capturing";
  app.fusedCount = Number(active.points || 0);
  app.fusionUnavailable = isRawScanPayload(active);
  app.finalCloudsLoading = false;
  app.finalCloudsLoaded = false;
  app.restoringActiveScan = true;
  app.realtimeBacklog = [];
  renderScanMeta();

  let restoredUnits = [];
  try {
    await connectWs().catch((err) => console.warn("恢复实时连接失败", err));
    restoredUnits = await downloadLiveClouds(query);
  } catch (err) {
    if (!silent) showToast(`恢复点云失败：${err.message}`);
  } finally {
    app.restoringActiveScan = false;
    flushRealtimeBacklog(restoredUnits);
  }
  restoreCalibrationState();
  restoreRegionState();
  if (!silent) showToast(`已恢复扫描 #${app.activeScanId}`);
}

// 无进行中扫描时，载入该工位上一次已完成扫描的结果点云（刷新后默认展示）。
// 直接问服务端要该工位最近一次 done 扫描，不依赖本地记忆——历史扫描、换浏览器都能还原。
async function loadLastScan() {
  if (app.activeScanId) return; // 已有进行中/已恢复的扫描，不覆盖
  const query = stationScanQuery();
  if (!app.token || !query) return;
  let scan;
  try {
    scan = await api(`/v1/scans/laser/latest?${query}`);
  } catch (err) {
    console.warn("查询上次扫描失败，忽略", err);
    return;
  }
  if (!scan || scan.found === false || !scan.scan_id) return; // 该工位还没有已完成的扫描
  const scanId = Number(scan.scan_id) || 0;
  if (!scanId) return;
  app.activeScanId = scanId;
  app.activeSessionKey = scan.session_key || "";
  app.scanState = "done";
  app.fusionUnavailable = isRawScanPayload(scan);
  app.finalCloudsLoading = false;
  app.finalCloudsLoaded = false;
  app.measure = scan; // 历史扫描测量随 /latest 拍平字段到达；始终留以便测不出给原因
  app.overlay = scan.overlay || null;
  app.overlayDirty = true;
  renderMeasure();
  renderScanMeta();
  try {
    await downloadFinalClouds({ job_id: scanId, ...scan });
  } catch (err) {
    console.warn("载入上次扫描点云失败", err);
  }
}

function applyActiveScanState(active) {
  app.activeScanId = Number(active.scan_id || 0) || app.activeScanId || null;
  app.activeSessionKey = active.session_key || app.activeSessionKey || "";
  app.scanState = active.live_state || active.status || app.scanState || "capturing";
  app.fusedCount = Number(active.points || app.fusedCount || 0);
  app.fusionUnavailable = isRawScanPayload(active);
  if (!app.scanStartedAt) app.scanStartedAt = Date.now(); // 恢复进行中扫描时也起计时
  app.liveFrames = { a: Number(active.frames_a || 0), b: Number(active.frames_b || 0) };
  const regionFilter = active.region_filter || active.regionFilter;
  if (regionFilter?.enabled && Array.isArray(regionFilter.points)) {
    app.region.points = regionFilter.points.map(sanitizePoint).filter(Boolean);
    app.region.closed = app.region.points.length >= 3;
    app.region.clipEnabled = app.region.closed;
    renderRegionCalibration();
  }
  renderScanMeta();
}

async function refreshActiveScanStatus({ silent = true } = {}) {
  if (app.scanStatusPolling) return null;
  const query = stationScanQuery();
  if (!query || !app.token) return null;
  app.scanStatusPolling = true;
  try {
    const active = await api(`/v1/scans/laser/active?${query}`);
    if (active?.active) {
      applyActiveScanState(active);
      return active;
    }

    if (app.activeScanId && isServerActiveScanState()) {
      await refreshCurrentScanAfterInactive();
    } else {
      renderScanMeta();
    }
    return active;
  } catch (err) {
    if (!silent) showToast(`扫描状态刷新失败：${err.message}`);
    return null;
  } finally {
    app.scanStatusPolling = false;
  }
}

async function refreshCurrentScanAfterInactive() {
  if (!app.activeScanId) {
    clearActiveScanState();
    renderScanMeta();
    return;
  }
  try {
    const scan = await api(`/v1/scans/laser/${app.activeScanId}`);
    app.scanState = scan.status || "done";
    app.activeSessionKey = scan.session_key || app.activeSessionKey;
    app.fusionUnavailable = isRawScanPayload(scan);
    renderScanMeta();
    if (["done", "completed"].includes(normalizedScanState(app.scanState))) {
      // 完成即自动展示，无需手动刷新。若当前展示的是上一笔（loadLastScan 载入的），强制重载本笔。
      if (app.loadedCloudsScanId !== app.activeScanId) {
        app.finalCloudsLoaded = false;
        app.finalCloudsLoading = false;
      }
      if (!app.finalCloudsLoaded && !app.finalCloudsLoading) {
        await downloadFinalClouds({ job_id: app.activeScanId });
      }
    }
  } catch (err) {
    console.warn("扫描终态刷新失败", err);
    clearActiveScanState();
    renderScanMeta();
  }
}

function startActiveScanPolling() {
  if (app.scanStatusTimer) return;
  refreshActiveScanStatus({ silent: true }).catch((err) => console.warn("扫描状态刷新失败", err));
  app.scanStatusTimer = setInterval(() => {
    refreshActiveScanStatus({ silent: true }).catch((err) => console.warn("扫描状态刷新失败", err));
  }, ACTIVE_SCAN_POLL_MS);
}

async function downloadLiveClouds(query = stationScanQuery()) {
  if (!query) return;
  const items = [
    { unit: 0, path: `/v1/scans/laser/active/cloud/unit_a?${query}`, cloud: app.clouds[0] },
    { unit: 1, path: `/v1/scans/laser/active/cloud/unit_b?${query}`, cloud: app.clouds[1] },
  ];
  const downloads = await Promise.allSettled(items.map((item) => downloadCloudFromPath(item.path, item.cloud, item.unit)));
  const restoredUnits = downloads
    .map((r, i) => (r.status === "fulfilled" && r.value !== false ? items[i].unit : null))
    .filter((unit) => unit !== null);
  const failed = downloads.filter((r) => r.status === "rejected");
  renderScanMeta();
  if (failed.length === downloads.length) {
    throw failed[0].reason;
  }
  if (failed.length > 0) {
    showToast("部分实时点云快照恢复失败，已显示可用点云");
  }
  return restoredUnits;
}

function flushRealtimeBacklog(restoredUnits = []) {
  if (!app.realtimeBacklog.length) return;
  const needsReplay = new Set(restoredUnits);
  for (const frame of app.realtimeBacklog) {
    if (!needsReplay.has(frame.unit)) continue;
    const clipped = clipRegionPointPayload(frame);
    app.clouds[frame.unit].append(clipped.points, clipped.colors);
  }
  app.realtimeBacklog = [];
  renderScanMeta();
}

function yieldToBrowser() {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

async function parsePcdAsync(buffer, maxPoints) {
  const bytes = new Uint8Array(buffer);
  const decoder = new TextDecoder("utf-8");
  let start = 0;
  let dataOffset = -1;
  const headerLines = [];
  for (let i = 0; i < bytes.length; i++) {
    if (bytes[i] !== 10) continue;
    const line = decoder.decode(bytes.subarray(start, i)).trim();
    headerLines.push(line);
    start = i + 1;
    if (line.startsWith("DATA")) {
      if (line !== "DATA binary") throw new Error(`不支持的 PCD 数据模式：${line}`);
      dataOffset = start;
      break;
    }
  }
  if (dataOffset < 0) throw new Error("PCD 缺少 DATA binary");
  const fieldsLine = headerLines.find((l) => l.startsWith("FIELDS "));
  const sizeLine = headerLines.find((l) => l.startsWith("SIZE "));
  const typeLine = headerLines.find((l) => l.startsWith("TYPE "));
  const countLine = headerLines.find((l) => l.startsWith("COUNT "));
  const pointsLine = headerLines.find((l) => l.startsWith("POINTS "));
  if (!fieldsLine || !sizeLine || !pointsLine) throw new Error("PCD 头不完整");
  const fields = fieldsLine.slice("FIELDS ".length).trim().split(/\s+/);
  const sizes = sizeLine.slice("SIZE ".length).trim().split(/\s+/).map((v) => Number(v));
  const types = typeLine
    ? typeLine.slice("TYPE ".length).trim().split(/\s+/)
    : fields.map(() => "F");
  const counts = countLine
    ? countLine.slice("COUNT ".length).trim().split(/\s+/).map((v) => Number(v))
    : fields.map(() => 1);
  const totalPoints = Number(pointsLine.slice("POINTS ".length).trim());
  const ix = fields.indexOf("x");
  const iy = fields.indexOf("y");
  const iz = fields.indexOf("z");
  const irgb = fields.indexOf("rgb") >= 0 ? fields.indexOf("rgb") : fields.indexOf("rgba");
  const ir = fields.indexOf("r");
  const ig = fields.indexOf("g");
  const ib = fields.indexOf("b");
  if (ix < 0 || iy < 0 || iz < 0 || !Number.isFinite(totalPoints)) {
    throw new Error("PCD 缺少 x/y/z 字段");
  }
  const offsets = [];
  let rowSize = 0;
  for (let i = 0; i < fields.length; i++) {
    offsets[i] = rowSize;
    rowSize += (sizes[i] || 4) * (counts[i] || 1);
  }
  const bodyBytes = bytes.length - dataOffset;
  const available = Math.floor(bodyBytes / rowSize);
  const points = Math.min(totalPoints, available);
  const take = Math.min(points, maxPoints);
  const stride = points > maxPoints ? points / maxPoints : 1;
  const view = new DataView(buffer, dataOffset);
  const out = new Float32Array(take * 3);
  const hasPackedRGB = irgb >= 0 && (sizes[irgb] || 4) >= 4;
  const hasSeparateRGB = ir >= 0 && ig >= 0 && ib >= 0;
  const colors = hasPackedRGB || hasSeparateRGB ? new Uint8Array(take * 3) : null;
  const chunkPoints = 40_000;
  for (let j = 0; j < take; j++) {
    const p = Math.floor(j * stride);
    const base = p * rowSize;
    out[j * 3] = view.getFloat32(base + offsets[ix], true);
    out[j * 3 + 1] = view.getFloat32(base + offsets[iy], true);
    out[j * 3 + 2] = view.getFloat32(base + offsets[iz], true);
    if (hasPackedRGB) {
      const raw = view.getUint32(base + offsets[irgb], true);
      colors[j * 3] = (raw >> 16) & 255;
      colors[j * 3 + 1] = (raw >> 8) & 255;
      colors[j * 3 + 2] = raw & 255;
    } else if (hasSeparateRGB) {
      colors[j * 3] = readColorComponent(view, base + offsets[ir], sizes[ir], types[ir]);
      colors[j * 3 + 1] = readColorComponent(view, base + offsets[ig], sizes[ig], types[ig]);
      colors[j * 3 + 2] = readColorComponent(view, base + offsets[ib], sizes[ib], types[ib]);
    }
    if (j > 0 && j % chunkPoints === 0) {
      await yieldToBrowser();
    }
  }
  return { points: out, colors };
}

function readColorComponent(view, offset, size = 1, type = "U") {
  const t = String(type || "U").toUpperCase();
  let v = 0;
  if (t === "F") {
    v = view.getFloat32(offset, true);
    if (Number.isFinite(v) && v >= 0 && v <= 1) v *= 255;
  } else if (size === 1) {
    v = t === "I" ? view.getInt8(offset) : view.getUint8(offset);
  } else if (size === 2) {
    v = t === "I" ? view.getInt16(offset, true) : view.getUint16(offset, true);
  } else {
    v = t === "I" ? view.getInt32(offset, true) : view.getUint32(offset, true);
  }
  return Number.isFinite(v) ? Math.max(0, Math.min(255, Math.round(v))) : 0;
}

async function startScan() {
  const camA = cameraByRole("a");
  const camB = cameraByRole("b");
  if (!camA || !camB) {
    showToast("当前工位需要至少配置镜头 A 和镜头 B");
    return;
  }
  let siteResult = null;
  try {
    siteResult = ensureStationCalibrationResult();
  } catch (err) {
    showToast(`标定结果计算失败：${err.message}`);
    return;
  }
  const rawOnly = !siteResult;
  const siteJSON = rawOnly ? "" : JSON.stringify(toNativeSiteJson(siteResult));
  const regionFilter = regionFilterForRequest();
  if (regionFilter && rawOnly) {
    showToast("启用区域墙过滤前，请先完成多镜头融合标定");
    return;
  }
  if (rawOnly) {
    showToast("当前工位未标定，本次只采集分镜点云，无法融合");
  }
  app.restoreSerial++;
  app.restoringActiveScan = false;
  app.realtimeBacklog = [];
  resetClouds({ force: true });
  app.scanState = "starting";
  app.scanStartedAt = Date.now();
  app.liveFrames = { a: 0, b: 0 };
  app.fusionUnavailable = rawOnly;
  app.finalCloudsLoading = false;
  app.finalCloudsLoaded = false;
  renderScanMeta();
  try {
    const body = {
      unit_a_ip: camA.ip,
      unit_b_ip: camB.ip,
      align: rawOnly ? "raw" : "site",
      site_json: siteJSON,
      keep_ratio: Number(els.keepRatio.value || 1),
    };
    if (regionFilter) body.region_filter = regionFilter;
    const resp = await api("/v1/scans/laser", { method: "POST", body: JSON.stringify(body) });
    app.activeScanId = resp.scan_id;
    app.activeSessionKey = resp.session_key;
    app.scanState = resp.status || "capturing";
    renderScanMeta();
    startActiveScanPolling();
    connectWs().catch((err) => {
      console.warn("实时连接失败，扫描状态改由服务端轮询兜底", err);
      showToast("扫描已开始，实时连接未接通，正在用服务端状态兜底");
    });
  } catch (err) {
    app.scanState = "error";
    renderScanMeta();
    if (String(err.message || "").includes("已有进行中的激光扫描")) {
      await restoreActiveScan({ clearInactive: false, silent: true });
      showToast("已恢复正在扫描的任务");
      return;
    }
    showToast(`起扫失败：${err.message}`);
  }
}

function ensureStationCalibrationResult() {
  if (Array.isArray(app.calibration.result?.matrix)) {
    return app.calibration.result;
  }
  if (app.calibration.pendingA || app.calibration.pairs.length < 3) {
    return null;
  }
  const result = solveRigidTransform(
    app.calibration.pairs.map((p) => p.b),
    app.calibration.pairs.map((p) => p.a),
  );
  app.calibration.result = result;
  saveCalibrationState();
  renderCalibration();
  return result;
}

async function stopScan() {
  if (!app.activeScanId) return;
  try {
    const resp = await api(`/v1/scans/laser/${app.activeScanId}/stop`, { method: "POST" });
    app.scanState = resp.status || "cancelled";
    app.activeSessionKey = "";
    renderScanMeta();
    await refreshActiveScanStatus({ silent: true });
  } catch (err) {
    showToast(`结束扫描失败：${err.message}`);
  }
}

async function toggleScan() {
  if (scanActionMode() === "stop") {
    await stopScan();
    return;
  }
  await startScan();
}

function resetClouds({ force = false } = {}) {
  if (annotationActive() && !force) {
    app.deferredFinalCloudPayload = app.activeScanId ? { job_id: app.activeScanId } : app.deferredFinalCloudPayload;
    renderScanMeta();
    renderCalibration();
    renderRegionCalibration();
    return false;
  }
  const budget = Number(els.pointBudget.value || 1_200_000);
  app.clouds[0].reset(budget);
  app.clouds[1].reset(budget);
  app.fusedCloud.reset(budget);
  app.fusedCount = 0;
  app.fusionUnavailable = false;
  app.deferredFinalCloudPayload = null;
  app.liveAngles = { a: null, b: null };
  app.measure = null; // 清掉上一笔测量，避免新扫描时残留旧数字
  app.overlay = null;
  app.overlayDirty = true;
  renderMeasure();
  markRoamFitDirty();
  renderCalibration();
  renderRegionCalibration();
  renderScanMeta();
  return true;
}

async function refreshDevice({ silent = false } = {}) {
  const cam = selectedCamera();
  if (!cam) return;
  const [statusResult, infoResult] = await Promise.allSettled([
    api(`/v1/scans/laser/device-status?ip=${encodeURIComponent(cam.ip)}`),
    api(`/v1/scans/laser/device-info?ip=${encodeURIComponent(cam.ip)}`),
  ]);
  const status = statusResult.status === "fulfilled" ? normalizeDeviceStatus(statusResult.value, cam) : null;
  const info = infoResult.status === "fulfilled" ? infoResult.value : null;
  if (info && (cam.role === "a" || cam.role === "b")) app.deviceInfos[cam.role] = info;
  if (status && (cam.role === "a" || cam.role === "b")) {
    app.deviceStatuses[cam.role] = status;
    renderScanMeta();
  }
  if (info) fillSettings(controlFromInfo(info));
  renderDeviceStatus(status || {}, info || {});
  if (!status && !info) {
    const err = infoResult.reason || statusResult.reason;
    if (!silent) showToast(`设备刷新失败：${err.message}`);
  } else if (!info && !silent) {
    showToast(`配置读取失败：${infoResult.reason?.message || "device-info 不可用"}`);
  } else if (!status && !silent) {
    showToast("配置已读取，状态信息暂不可用");
  }
}

async function refreshStationDeviceStatuses({ silent = true } = {}) {
  if (app.statusPolling) return;
  const cameras = [
    ["a", cameraByRole("a")],
    ["b", cameraByRole("b")],
  ].filter(([, cam]) => cam?.ip);
  if (!cameras.length) return;
  app.statusPolling = true;
  try {
    const results = await Promise.allSettled(cameras.map(([, cam]) =>
      api(`/v1/scans/laser/device-status?ip=${encodeURIComponent(cam.ip)}`),
    ));
    results.forEach((result, index) => {
      const [role, cam] = cameras[index];
      if (result.status === "fulfilled") {
        app.deviceStatuses[role] = normalizeDeviceStatus(result.value, cam);
      } else if (!silent) {
        showToast(`${role.toUpperCase()} 状态刷新失败：${result.reason?.message || "不可达"}`);
      }
    });
    renderScanMeta();
    if (selectedCamera()) {
      const selectedStatus = app.deviceStatuses[selectedCamera().role];
      const selectedInfo = app.deviceInfos[selectedCamera().role] || {};
      if (selectedStatus) renderDeviceStatus(selectedStatus, selectedInfo);
    }
  } finally {
    app.statusPolling = false;
  }
}

function startDeviceStatusPolling() {
  if (app.statusTimer) return;
  refreshStationDeviceStatuses({ silent: true }).catch((err) => console.warn("设备状态刷新失败", err));
  app.statusTimer = setInterval(() => {
    refreshStationDeviceStatuses({ silent: true }).catch((err) => console.warn("设备状态刷新失败", err));
  }, DEVICE_STATUS_POLL_MS);
}

function renderDeviceStatus(status, info) {
  const state = status.state || "";
  const temp = status.tempre;
  const rows = [
    ["当前角度", `${fmt2(status.angleDegs)}°`],
    ["最新角度", `${fmt2(status.latestAngle)}° · 零位 ${fmt2(status.zeroDegs)}°`],
    ["运行时长", fmtDuration(status.uptimeSec)],
    ["错误码", formatErrorCode(status.errorCode)],
    ["型号", info.model || "-"],
    ["SN", info.sn || "-"],
  ];
  if (status.scanMsg) rows.push(["消息", status.scanMsg]);
  els.deviceStatus.innerHTML = [
    `<div class="device-status-head">
      ${deviceStateBadgeHtml(state)}
      <strong>${escapeHtml(status.ip || selectedCamera()?.ip || "-")}</strong>
      <em>${fmt1(temp)}℃</em>
    </div>`,
    `<div class="online-row">
      ${onlineChipHtml("编码器", status.encoderOnline)}
      ${onlineChipHtml("激光", status.lidarOnline)}
      ${onlineChipHtml("相机", status.cameraOnline)}
      ${onlineChipHtml("控制", status.controlOnline)}
    </div>`,
    ...rows.map(([k, v]) => `<span>${k}</span><strong>${escapeHtml(v)}</strong>`),
  ].join("");
}

function normalizeDeviceStatus(raw = {}, cam = null) {
  return {
    ip: raw.ip || cam?.ip || "",
    online: boolValue(raw.online),
    state: raw.state || "",
    scanMsg: raw.scan_msg ?? raw.scanMsg ?? "",
    uptimeSec: numOrNull(raw.uptime ?? raw.uptimeSec) ?? 0,
    encoderOnline: boolValue(raw.encoder_online ?? raw.encoderOnline),
    lidarOnline: boolValue(raw.lidar_online ?? raw.lidarOnline),
    cameraOnline: boolValue(raw.camera_online ?? raw.cameraOnline),
    controlOnline: boolValue(raw.control_online ?? raw.controlOnline),
    latestAngle: numOrNull(raw.latest_angle ?? raw.latestAngle),
    zeroDegs: numOrNull(raw.zero_degs ?? raw.zeroDegs),
    angleDegs: numOrNull(raw.angle_degs ?? raw.angleDegs),
    errorCode: Number(raw.error_code ?? raw.errorCode ?? 0),
    tempre: numOrNull(raw.tempre),
  };
}

function deviceStateLabel(state) {
  const s = String(state || "").toUpperCase();
  if (!s) return "未知";
  if (s === "READY") return "就绪";
  if (s === "SCAN") return "扫描";
  if (s === "ALIGN") return "回零";
  if (s === "BUSY") return "忙碌";
  if (s === "WATCH") return "守望";
  if (s === "ERROR") return "错误";
  return s;
}

function deviceStateKind(state) {
  const s = String(state || "").toUpperCase();
  if (s === "READY") return "ok";
  if (["SCAN", "ALIGN", "BUSY", "WATCH"].includes(s)) return "busy";
  if (s === "ERROR") return "bad";
  return "";
}

function deviceStateBadgeHtml(state) {
  const raw = String(state || "").toUpperCase();
  const label = raw || "-";
  return `<span class="device-state ${deviceStateKind(raw)}">${escapeHtml(label)}</span>`;
}

function onlineChipHtml(label, online) {
  return `<span class="online-chip ${online ? "ok" : ""}">${escapeHtml(label)}</span>`;
}

function formatErrorCode(code) {
  const n = Number(code || 0);
  if (!Number.isFinite(n) || n === 0) return "无";
  return `0x${Math.trunc(n).toString(16).toUpperCase()} (bit ${bitList(n)})`;
}

function bitList(code) {
  const n = Number(code || 0);
  const bits = [];
  for (let i = 0; i < 32; i++) {
    if ((n & (1 << i)) !== 0) bits.push(i);
  }
  return bits.join(",") || "-";
}

function fmtDuration(seconds) {
  const s = Number(seconds);
  if (!Number.isFinite(s) || s <= 0) return "-";
  return `${Math.floor(s / 3600)}h ${Math.floor((s % 3600) / 60)}m`;
}

function controlFromInfo(info) {
  return info?.control || info?.Control || info?.data?.control || info?.data?.Control || {};
}

function boolText(v) {
  return v ? "在线" : "离线";
}

function fmt(v) {
  return Number.isFinite(Number(v)) ? Number(v).toFixed(2) : "-";
}

function fmt1(v) {
  const n = Number(v);
  return Number.isFinite(n) ? n.toFixed(1) : "-";
}

function fmt2(v) {
  const n = Number(v);
  return Number.isFinite(n) ? n.toFixed(2) : "-";
}

function finiteOrNull(v) {
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

function numOrNull(v) {
  return finiteOrNull(v);
}

function parseNumberInput(v) {
  if (String(v ?? "").trim() === "") return null;
  return finiteOrNull(v);
}

function boolValue(v) {
  return v === true || v === 1 || v === "1" || String(v).toLowerCase() === "true";
}

function signedScanAngleDeg(start, stop) {
  return stop - start;
}

function stopAngleFromScan(start, scanAngle) {
  return start + scanAngle;
}

function scanAngleWarning(start, scanAngle) {
  if (start == null || scanAngle == null) return "初始位置和扫描角度必须是数字";
  const span = Math.abs(scanAngle);
  const stop = stopAngleFromScan(start, scanAngle);
  if (start <= -180 || start >= 180) return "无效范围：初始位置需在 -180°～180° 内，并避开 ±180° 边界";
  if (scanAngle <= 0) return "当前固件只支持沿设备正向扫描；负扫描角会跨 +180° 扫成超大角度，请调换初始位置后使用正角度";
  if (stop <= -180 || stop >= 180) return `无效范围：结束位置 ${fmt2(stop)}° 需避开 ±180° 边界`;
  if (span < 10) return "无效范围：扫描角度需 ≥10°，过小角度不会形成有效点云";
  if (span >= 179.5) return "无效范围：单段扫描角度必须小于 180°";
  return null;
}

function updateScanAngleHint() {
  const start = parseNumberInput(els.scanStart.value);
  const sweep = parseNumberInput(els.scanAngle.value);
  const warning = scanAngleWarning(start, sweep);
  if (start != null && sweep != null) {
    els.scanStop.value = stopAngleFromScan(start, sweep);
  }
  els.scanAngleHint.textContent = warning || `结束角：${fmt2(stopAngleFromScan(start, sweep))}°`;
  els.scanAngleHint.classList.toggle("bad", Boolean(warning));
  els.applySettings.disabled = Boolean(warning);
}

function fillSettings(c) {
  els.scanSpeed.value = c.scan_speed ?? c.scanSpeed ?? "";
  els.zeroSpeed.value = c.zero_speed ?? c.zeroSpeed ?? "";
  const start = c.scan_start_angle ?? c.scanStartAngle ?? "";
  const stop = c.scan_stop_angle ?? c.scanStopAngle ?? "";
  const sweep = c.scan_angle ?? c.scanAngle ?? signedScanAngleDeg(Number(start) || 0, Number(stop) || 0);
  els.scanStart.value = start;
  els.scanAngle.value = sweep;
  els.scanStop.value = stopAngleFromScan(Number(start) || 0, Number(sweep) || 0);
  els.watchingAngle.value = c.watching_angle ?? c.watchingAngle ?? "";
  els.ghost.value = c.lidar_filter_ghost ?? c.lidarFilterGhost ?? "";
  els.cameraFps.value = c.camera_fps ?? c.cameraFps ?? "";
  const zone = c.lidar_filter_zone || c.lidarFilterZone || [];
  els.zoneMin.value = zone[0] ?? "";
  els.zoneMax.value = zone[1] ?? "";
  updateScanAngleHint();
}

async function applySettings() {
  const cam = selectedCamera();
  if (!cam) return;
  const startAngle = parseNumberInput(els.scanStart.value);
  const sweepAngle = parseNumberInput(els.scanAngle.value);
  const warning = scanAngleWarning(startAngle, sweepAngle);
  if (warning) {
    updateScanAngleHint();
    showToast(warning);
    return;
  }
  const stopAngle = stopAngleFromScan(startAngle, sweepAngle);
  els.scanStop.value = stopAngle;
  const body = {
    scan_speed: num(els.scanSpeed.value),
    zero_speed: num(els.zeroSpeed.value),
    scan_start_angle: startAngle,
    scan_stop_angle: stopAngle,
    scan_angle: sweepAngle,
    watching_angle: num(els.watchingAngle.value),
    lidar_filter_ghost: num(els.ghost.value),
    lidar_filter_zone: [num(els.zoneMin.value), num(els.zoneMax.value)],
    camera_fps: num(els.cameraFps.value),
  };
  try {
    await api(`/v1/scans/laser/device-scan-settings?ip=${encodeURIComponent(cam.ip)}`, {
      method: "POST",
      body: JSON.stringify(body),
    });
    showToast("配置已下发");
    await refreshDevice();
  } catch (err) {
    showToast(`下发失败：${err.message}`);
  }
}

function num(v) {
  const n = Number(v);
  return Number.isFinite(n) ? n : 0;
}

async function sendCommand(cmd) {
  const cam = selectedCamera();
  if (!cam) return;
  try {
    await api(`/v1/scans/laser/device-command?ip=${encodeURIComponent(cam.ip)}`, {
      method: "POST",
      body: JSON.stringify({ cmd }),
    });
    showToast(`命令已发送：${cmd}`);
    setTimeout(refreshDevice, 600);
  } catch (err) {
    showToast(`命令失败：${err.message}`);
  }
}

function webglApiExists(name) {
  if (name === "webgl2") return typeof window.WebGL2RenderingContext !== "undefined";
  return typeof window.WebGLRenderingContext !== "undefined";
}

function createWebglContext(canvas) {
  const errors = [];
  const recordError = (message) => {
    const text = String(message || "").trim();
    if (text && !errors.includes(text)) errors.push(text);
  };
  const onCreationError = (ev) => recordError(ev.statusMessage || ev.message || "浏览器拒绝创建 WebGL context");
  canvas.addEventListener("webglcontextcreationerror", onCreationError, false);
  try {
    for (const name of WEBGL_CONTEXT_NAMES) {
      if (!webglApiExists(name)) {
        recordError(`${name}: API 不存在`);
        continue;
      }
      for (const options of WEBGL_CONTEXT_OPTIONS) {
        try {
          const gl = options ? canvas.getContext(name, options) : canvas.getContext(name);
          if (gl) return { gl, name, errors };
        } catch (err) {
          recordError(`${name}: ${err.message || err}`);
        }
      }
    }
  } finally {
    canvas.removeEventListener("webglcontextcreationerror", onCreationError, false);
  }
  return { gl: null, name: "", errors };
}

function summarizeWebglFailure(errors) {
  const noApi = typeof window.WebGLRenderingContext === "undefined"
    && typeof window.WebGL2RenderingContext === "undefined";
  if (noApi) {
    return {
      status: "浏览器无 WebGL API",
      detail: "浏览器没有暴露 WebGLRenderingContext / WebGL2RenderingContext",
    };
  }
  const useful = errors.filter((item) => !item.includes("API 不存在")).slice(0, 2);
  const detail = useful.length ? useful.join("；") : "浏览器拒绝创建 WebGL context，但没有返回具体原因";
  const lower = detail.toLowerCase();
  if (lower.includes("swiftshader") || lower.includes("software webgl") || lower.includes("unsafe")) {
    return {
      status: "软件 WebGL 未启用",
      detail,
    };
  }
  if (lower.includes("disabled") || lower.includes("blocked") || lower.includes("deny")) {
    return {
      status: "浏览器禁用了 WebGL",
      detail,
    };
  }
  if (lower.includes("gpu") || lower.includes("driver") || lower.includes("gl") || lower.includes("egl") || lower.includes("angle")) {
    return {
      status: "图形后端初始化失败",
      detail,
    };
  }
  return {
    status: "WebGL 创建失败",
    detail,
  };
}

function webglFallbackMessage(summary) {
  return `${summary.status}，已切换 Canvas 兼容渲染：${summary.detail}`;
}

function initGl() {
  bindWebglEvents();
  const result = createWebglContext(els.canvas);
  const gl = result.gl;
  if (!gl) {
    const summary = summarizeWebglFailure(result.errors);
    console.warn("WebGL context 创建失败", {
      ...summary,
      errors: result.errors,
      userAgent: navigator.userAgent,
      gpuHint: "服务器/远程桌面环境常见原因：Chrome 带了 --disable-gpu/--disable-webgl，或没有启用 --enable-unsafe-swiftshader。",
    });
    fallbackToCanvas2d(webglFallbackMessage(summary), summary);
    return;
  }
  console.info("WebGL context 已创建", {
    context: result.name,
    vendor: gl.getParameter(gl.VENDOR),
    renderer: gl.getParameter(gl.RENDERER),
  });
  app.gl = gl;
  app.renderer = "webgl";
  app.webglContextName = result.name;
  app.webglStatus = "";
  app.webglDetail = "";
  const vs = `
    attribute vec3 aPosition;
    attribute vec3 aColor;
    uniform mat4 uMvp;
    uniform vec3 uCenter;
    uniform float uScale;
    uniform float uPointSize;
    uniform vec3 uFallbackColor;
    uniform bool uUseVertexColor;
    varying vec3 vColor;
    void main() {
      vec3 p = (aPosition - uCenter) * uScale;
      gl_Position = uMvp * vec4(p, 1.0);
      gl_PointSize = uPointSize;
      vColor = uUseVertexColor ? aColor : uFallbackColor;
    }
  `;
  const fs = `
    precision mediump float;
    varying vec3 vColor;
    void main() {
      vec2 uv = gl_PointCoord - vec2(0.5);
      if (dot(uv, uv) > 0.25) discard;
      gl_FragColor = vec4(vColor, 0.94);
    }
  `;
  try {
    app.program = createProgram(gl, vs, fs);
  } catch (err) {
    console.warn("WebGL 初始化失败，切换 Canvas 兼容模式", err);
    fallbackToCanvas2d("WebGL 初始化失败，已切换兼容点云渲染");
    return;
  }
  app.attribs = {
    position: gl.getAttribLocation(app.program, "aPosition"),
    color: gl.getAttribLocation(app.program, "aColor"),
  };
  app.uniforms = {
    mvp: gl.getUniformLocation(app.program, "uMvp"),
    center: gl.getUniformLocation(app.program, "uCenter"),
    scale: gl.getUniformLocation(app.program, "uScale"),
    pointSize: gl.getUniformLocation(app.program, "uPointSize"),
    fallbackColor: gl.getUniformLocation(app.program, "uFallbackColor"),
    useVertexColor: gl.getUniformLocation(app.program, "uUseVertexColor"),
  };
  app.buffers = [gl.createBuffer(), gl.createBuffer(), gl.createBuffer()];
  app.colorBuffers = [gl.createBuffer(), gl.createBuffer(), gl.createBuffer()];
  gl.enable(gl.DEPTH_TEST);
  gl.enable(gl.BLEND);
  gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
}

function fallbackToCanvas2d(message, summary = null) {
  app.gl = null;
  app.program = null;
  app.webglContextName = "";
  app.ctx2d = els.canvas.getContext("2d");
  app.renderer = app.ctx2d ? "2d" : "";
  app.webglStatus = summary?.status || "";
  app.webglDetail = summary?.detail || "";
  if (app.ctx2d) {
    showToast(message);
    renderScanMeta();
  } else {
    showToast("当前浏览器不支持点云画布");
  }
}

function bindWebglEvents() {
  if (app.webglEventsBound) return;
  els.canvas.addEventListener("webglcontextlost", (ev) => {
    ev.preventDefault();
    app.gl = null;
    app.program = null;
    app.buffers = [];
    app.colorBuffers = [];
    app.webglContextName = "";
    app.webglStatus = "WebGL context 已丢失";
    app.webglDetail = "";
    app.renderer = app.ctx2d ? "2d" : "";
    renderScanMeta();
    showToast("WebGL context 已丢失，等待浏览器恢复");
  }, false);
  els.canvas.addEventListener("webglcontextrestored", () => {
    showToast("WebGL context 已恢复，重新初始化点云渲染");
    initGl();
    for (const cloud of app.clouds) {
      cloud.dirty = true;
      cloud.colorDirty = true;
    }
    app.fusedCloud.dirty = true;
    app.fusedCloud.colorDirty = true;
    markRenderDirty();
  }, false);
  app.webglEventsBound = true;
}

function createProgram(gl, vsSource, fsSource) {
  const vs = compileShader(gl, gl.VERTEX_SHADER, vsSource);
  const fs = compileShader(gl, gl.FRAGMENT_SHADER, fsSource);
  const program = gl.createProgram();
  gl.attachShader(program, vs);
  gl.attachShader(program, fs);
  gl.linkProgram(program);
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
    throw new Error(gl.getProgramInfoLog(program));
  }
  return program;
}

function compileShader(gl, type, source) {
  const shader = gl.createShader(type);
  gl.shaderSource(shader, source);
  gl.compileShader(shader);
  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
    throw new Error(gl.getShaderInfoLog(shader));
  }
  return shader;
}

function renderLoop() {
  requestAnimationFrame(renderLoop);
  updateRoam();
  const interactive = isInteractionActive();
  if (canvasSizeChanged()) app.renderDirty = true;
  if (interactive || app.wasInteractive || app.renderDirty || cloudsNeedDraw()) {
    drawClouds();
    app.renderDirty = false;
  }
  app.wasInteractive = interactive;
  renderMarkersIfNeeded();
  layoutCamPreviews(app.renderPanes); // 相机卡片不在 markerLayer，交互中也保持贴住分镜
}

function drawClouds() {
  if (!app.gl) {
    drawClouds2d();
    return;
  }
  const gl = app.gl;
  resizeCanvas();
  const rect = els.canvas.getBoundingClientRect();
  const dpr = Math.max(1, els.canvas.width / Math.max(1, rect.width));
  app.renderPanes = buildRenderPanes(rect);

  gl.viewport(0, 0, els.canvas.width, els.canvas.height);
  gl.clearColor(0.067, 0.086, 0.082, 1);
  gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);

  gl.useProgram(app.program);
  gl.uniform1f(app.uniforms.pointSize, Number(els.pointSize.value || 2));

  gl.enable(gl.SCISSOR_TEST);
  for (const pane of app.renderPanes) {
    const vx = Math.max(0, Math.floor(pane.x * dpr));
    const vy = Math.max(0, Math.floor((rect.height - pane.y - pane.h) * dpr));
    const vw = Math.max(1, Math.floor(pane.w * dpr));
    const vh = Math.max(1, Math.floor(pane.h * dpr));
    gl.viewport(vx, vy, vw, vh);
    gl.scissor(vx, vy, vw, vh);
    gl.clearColor(pane.background[0], pane.background[1], pane.background[2], 1);
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);

    const cloud = pane.cloud;
    if (!cloud.count) continue;
    gl.uniformMatrix4fv(app.uniforms.mvp, false, pane.mvp);
    gl.uniform3fv(app.uniforms.center, pane.center);
    gl.uniform1f(app.uniforms.scale, pane.scale);
    gl.uniform3fv(app.uniforms.fallbackColor, pane.color);
    const useVertexColor = cloud.hasColor && app.attribs.color >= 0;
    gl.uniform1i(app.uniforms.useVertexColor, useVertexColor ? 1 : 0);

    gl.bindBuffer(gl.ARRAY_BUFFER, app.buffers[pane.bufferIndex]);
    if (cloud.dirty) {
      gl.bufferData(gl.ARRAY_BUFFER, cloud.data.subarray(0, cloud.count * 3), gl.DYNAMIC_DRAW);
      cloud.dirty = false;
    }
    gl.enableVertexAttribArray(app.attribs.position);
    gl.vertexAttribPointer(app.attribs.position, 3, gl.FLOAT, false, 0, 0);

    if (useVertexColor) {
      gl.bindBuffer(gl.ARRAY_BUFFER, app.colorBuffers[pane.bufferIndex]);
      if (cloud.colorDirty) {
        gl.bufferData(gl.ARRAY_BUFFER, cloud.colors.subarray(0, cloud.count * 3), gl.DYNAMIC_DRAW);
        cloud.colorDirty = false;
      }
      gl.enableVertexAttribArray(app.attribs.color);
      gl.vertexAttribPointer(app.attribs.color, 3, gl.UNSIGNED_BYTE, true, 0, 0);
    } else if (app.attribs.color >= 0) {
      gl.disableVertexAttribArray(app.attribs.color);
    }

    gl.drawArrays(gl.POINTS, 0, cloud.count);
  }
  gl.disable(gl.SCISSOR_TEST);
}

function drawClouds2d() {
  const ctx = app.ctx2d;
  if (!ctx) return;
  resizeCanvas();
  const rect = els.canvas.getBoundingClientRect();
  const dpr = Math.max(1, els.canvas.width / Math.max(1, rect.width));
  app.renderPanes = buildRenderPanes(rect);
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, rect.width, rect.height);
  ctx.fillStyle = "#111615";
  ctx.fillRect(0, 0, rect.width, rect.height);

  const size = Math.max(1, Number(els.pointSize.value || 2));
  for (const pane of app.renderPanes) {
    ctx.save();
    ctx.beginPath();
    ctx.rect(pane.x, pane.y, pane.w, pane.h);
    ctx.clip();
    ctx.fillStyle = pane.backgroundCss;
    ctx.fillRect(pane.x, pane.y, pane.w, pane.h);

    const cloud = pane.cloud;
    if (!cloud.count) {
      ctx.restore();
      continue;
    }
    const targetPoints = cloud.hasColor ? 160_000 : 220_000;
    const stride = Math.max(1, Math.floor(cloud.count / targetPoints));
    if (!cloud.hasColor) ctx.fillStyle = pane.colorCss;
    for (let i = 0; i < cloud.count; i += stride) {
      const idx = i * 3;
      const p = [cloud.data[idx], cloud.data[idx + 1], cloud.data[idx + 2]];
      const s = projectPointInPane(p, pane);
      if (!s) continue;
      if (cloud.hasColor) {
        ctx.fillStyle = `rgb(${cloud.colors[idx]}, ${cloud.colors[idx + 1]}, ${cloud.colors[idx + 2]})`;
      }
      ctx.fillRect(s[0], s[1], size, size);
    }
    ctx.restore();
  }
}

function cloudsNeedDraw() {
  return app.clouds.some((cloud) => cloud.dirty || cloud.colorDirty) ||
    app.fusedCloud.dirty || app.fusedCloud.colorDirty;
}

function canvasSizeChanged() {
  const rect = els.canvas.getBoundingClientRect();
  const key = `${Math.round(rect.width)}x${Math.round(rect.height)}@${Math.min(window.devicePixelRatio || 1, 2)}`;
  if (key === app.canvasSizeKey) return false;
  app.canvasSizeKey = key;
  return true;
}

function markRenderDirty() {
  app.renderDirty = true;
  markOverlayDirty();
}

function resizeCanvas() {
  const rect = els.canvas.getBoundingClientRect();
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  const w = Math.max(1, Math.floor(rect.width * dpr));
  const h = Math.max(1, Math.floor(rect.height * dpr));
  if (els.canvas.width !== w || els.canvas.height !== h) {
    els.canvas.width = w;
    els.canvas.height = h;
  }
}

function buildRenderPanes(rect) {
  const full = { x: 0, y: 0, w: rect.width, h: rect.height };
  if (app.cloudMode === "fused") {
    return [makePane("fused", null, "融合", app.fusedCloud, 2, full)];
  }
  const gap = rect.width >= 720 ? 8 : 4;
  const w = Math.max(1, (rect.width - gap) / 2);
  return [
    makePane("a", 0, "镜头 A", app.clouds[0], 0, { x: 0, y: 0, w, h: rect.height }),
    makePane("b", 1, "镜头 B", app.clouds[1], 1, { x: w + gap, y: 0, w, h: rect.height }),
  ];
}

function makePane(key, unit, label, cloud, bufferIndex, rect) {
  const frame = cloudFrame(cloud);
  const isRoam = app.controlMode === "roam";
  if (isRoam && viewForPaneKey(key).roamNeedsFit && cloud.count) {
    resetRoamView(viewForPaneKey(key), cloud);
  }
  const aspect = Math.max(1, rect.w) / Math.max(1, rect.h);
  const proj = isRoam
    ? mat4Perspective(ROAM_FOV_RAD, aspect, ROAM_NEAR_PLANE_MM, Math.max(ROAM_MIN_FAR_MM, viewForPaneKey(key).roamFar || ROAM_MIN_FAR_MM))
    : mat4Perspective(Math.PI / 4, aspect, VIEW_NEAR_PLANE, VIEW_FAR_PLANE);
  const viewState = viewForPaneKey(key);
  const view = isRoam ? roamViewMatrix(viewState) : orbitViewMatrix(viewState);
  const mvp = mat4Multiply(proj, view);
  const displayCenter = isRoam ? [0, 0, 0] : frame.center;
  const displayScale = isRoam ? 1 : frame.scale;
  viewState.center = displayCenter;
  viewState.scale = displayScale;
  viewState.mvp = mvp;
  return {
    ...rect,
    key,
    unit,
    label,
    cloud,
    bufferIndex,
    center: displayCenter,
    scale: displayScale,
    frame,
    mvp,
    view: viewState,
    color: fallbackColor(key),
    colorCss: fallbackColorCss(key),
    background: paneBackground(key),
    backgroundCss: paneBackgroundCss(key),
  };
}

function orbitViewMatrix(view) {
  const eye = orbitEye(view);
  return mat4LookAt(add3(eye, view.pan), view.pan, [0, 0, 1]);
}

function roamViewMatrix(view) {
  const eye = view.roamPos;
  return mat4LookAt(eye, add3(eye, roamLookForward(view)), [0, 0, 1]);
}

function cloudFrame(cloud) {
  if (!cloud?.count || !Number.isFinite(cloud.min[0])) {
    return { center: [0, 0, 0], scale: 0.001, radius: 1000 };
  }
  const center = [
    (cloud.min[0] + cloud.max[0]) / 2,
    (cloud.min[1] + cloud.max[1]) / 2,
    (cloud.min[2] + cloud.max[2]) / 2,
  ];
  const dx = cloud.max[0] - cloud.min[0];
  const dy = cloud.max[1] - cloud.min[1];
  const dz = cloud.max[2] - cloud.min[2];
  const radius = Math.max(100, Math.hypot(dx, dy, dz) / 2);
  return { center, scale: 1 / radius, radius };
}

function fallbackColor(key) {
  if (key === "a") return [0.24, 0.78, 0.86];
  if (key === "b") return [1.0, 0.56, 0.24];
  return [0.86, 0.9, 0.88];
}

function fallbackColorCss(key) {
  if (key === "a") return "rgba(61, 199, 219, 0.92)";
  if (key === "b") return "rgba(255, 143, 61, 0.92)";
  return "rgba(219, 230, 224, 0.92)";
}

function paneBackground(key) {
  if (key === "b") return [0.057, 0.073, 0.07];
  return [0.067, 0.086, 0.082];
}

function paneBackgroundCss(key) {
  return key === "b" ? "#0f1312" : "#111615";
}

function viewForPaneKey(key) {
  if (key === "b") return app.views.b;
  if (key === "fused") return app.views.fused;
  return app.views.a;
}

function cloudForPaneKey(key) {
  if (key === "b") return app.clouds[1];
  if (key === "fused") return app.fusedCloud;
  return app.clouds[0];
}

function markRoamFitDirty() {
  for (const view of Object.values(app.views)) view.roamNeedsFit = true;
}

function orbitEye(view = viewForPaneKey(app.activeViewKey)) {
  const d = view.distance;
  const cp = Math.cos(view.pitch);
  return [
    d * cp * Math.cos(view.yaw),
    d * cp * Math.sin(view.yaw),
    d * Math.sin(view.pitch),
  ];
}

function orbitBasis(view = viewForPaneKey(app.activeViewKey)) {
  const forward = normalize3(orbitEye(view).map((v) => -v));
  let right = normalize3(cross3(forward, [0, 0, 1]));
  if (len3(right) < 1e-6) right = [1, 0, 0];
  const up = normalize3(cross3(right, forward));
  return { right, up };
}

function panView(dx, dy, view = viewForPaneKey(app.activeViewKey), pane = null) {
  const rect = els.canvas.getBoundingClientRect();
  const height = pane?.h || rect.height;
  const panDistance = Math.max(view.distance, VIEW_MIN_PAN_DISTANCE);
  const unitsPerPixel = (2 * panDistance * Math.tan(Math.PI / 8)) / Math.max(1, height);
  const { right, up } = orbitBasis(view);
  const sx = -dx * unitsPerPixel;
  const sy = dy * unitsPerPixel;
  view.pan = add3(view.pan, add3(scale3(right, sx), scale3(up, sy)));
}

function setViewPreset(name, resetPan = false) {
  app.viewPreset = name === "top" || name === "side" ? name : "free";
  for (const key of visibleViewKeys()) {
    applyViewPreset(viewForPaneKey(key), name, resetPan);
  }
  updateViewButtons();
  markRenderDirty();
}

// 高亮当前视角按钮（顶视/侧视/自由），让"固化"状态可见。
function updateViewButtons() {
  document.querySelectorAll("[data-view]").forEach((btn) => {
    btn.classList.toggle("active", btn.getAttribute("data-view") === app.viewPreset);
  });
}

function applyViewPreset(view, name, resetPan = false) {
  if (name === "top") {
    view.yaw = 0;
    view.pitch = Math.PI / 2 - 0.02;
    view.distance = VIEW_DEFAULT_DISTANCE;
  } else if (name === "side") {
    view.yaw = 0;
    view.pitch = 0.04;
    view.distance = VIEW_DEFAULT_DISTANCE;
  } else {
    view.yaw = -0.75;
    view.pitch = 0.45;
    view.distance = VIEW_DEFAULT_DISTANCE;
  }
  if (resetPan) {
    view.pan = [0, 0, 0];
    resetRoamView(view);
  }
}

function visibleViewKeys() {
  return app.cloudMode === "fused" ? ["fused"] : ["a", "b"];
}

function setControlMode(mode) {
  const next = mode === "roam" ? "roam" : "orbit";
  const enteringRoam = app.controlMode !== "roam" && next === "roam";
  app.controlMode = next;
  app.keys.clear();
  if (enteringRoam) prepareRoamViews(true);
  markRenderDirty();
  document.querySelectorAll("[data-control-mode]").forEach((btn) => {
    btn.classList.toggle("active", btn.getAttribute("data-control-mode") === app.controlMode);
  });
  renderScanMeta();
}

function resetRoamView(view, cloud = null) {
  const spawn = computeRoamSpawn(cloud);
  view.roamPos = spawn.pos;
  view.roamYaw = spawn.yaw;
  view.roamPitch = ROAM_DEFAULT_PITCH;
  view.roamRadius = spawn.radius;
  view.roamFar = spawn.far;
  view.roamNeedsFit = !cloud?.count;
}

function computeRoamSpawn(cloud) {
  const fit = robustCloudFit(cloud);
  if (!fit) {
    return {
      pos: [...ROAM_DEFAULT_POS],
      yaw: ROAM_DEFAULT_YAW,
      radius: 1500,
      far: ROAM_MIN_FAR_MM,
    };
  }
  const margin = Math.max(650, Math.min(900, fit.half[2] * 0.25 + 700));
  const floorZ = fit.center[2] - fit.half[2];
  const eyeZ = floorZ + Math.max(ROAM_MIN_EYE_HEIGHT_MM, Math.min(ROAM_MAX_EYE_HEIGHT_MM, ROAM_EYE_HEIGHT_MM));
  const startY = fit.center[1] - fit.half[1] - margin;
  const radius = Math.max(500, len3(fit.half) + margin);
  return {
    pos: [fit.center[0], startY, eyeZ],
    yaw: 0,
    radius,
    far: Math.max(ROAM_MIN_FAR_MM, radius * 2 + 3000),
  };
}

function robustCloudFit(cloud) {
  if (!cloud?.count) return null;
  const maxSamples = 80_000;
  const stride = Math.max(1, Math.floor(cloud.count / maxSamples));
  const xs = [];
  const ys = [];
  const zs = [];
  for (let i = 0; i < cloud.count; i += stride) {
    const idx = i * 3;
    const x = cloud.data[idx];
    const y = cloud.data[idx + 1];
    const z = cloud.data[idx + 2];
    if (!Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(z)) continue;
    if (Math.abs(x) > 50_000 || Math.abs(y) > 50_000 || Math.abs(z) > 50_000) continue;
    xs.push(x);
    ys.push(y);
    zs.push(z);
  }
  if (!xs.length) return null;
  xs.sort((a, b) => a - b);
  ys.sort((a, b) => a - b);
  zs.sort((a, b) => a - b);
  const min = [percentileSorted(xs, 0.02), percentileSorted(ys, 0.02), percentileSorted(zs, 0.02)];
  const max = [percentileSorted(xs, 0.98), percentileSorted(ys, 0.98), percentileSorted(zs, 0.98)];
  const center = [
    (min[0] + max[0]) / 2,
    (min[1] + max[1]) / 2,
    (min[2] + max[2]) / 2,
  ];
  const half = [
    Math.max(100, (max[0] - min[0]) / 2),
    Math.max(100, (max[1] - min[1]) / 2),
    Math.max(100, (max[2] - min[2]) / 2),
  ];
  return { center, half };
}

function percentileSorted(values, p) {
  if (!values.length) return 0;
  const idx = Math.max(0, Math.min(values.length - 1, Math.round((values.length - 1) * p)));
  return values[idx];
}

function prepareRoamViews(force = false) {
  for (const key of visibleViewKeys()) {
    const view = viewForPaneKey(key);
    if (force || view.roamNeedsFit) resetRoamView(view, cloudForPaneKey(key));
  }
}

function roamLookForward(view) {
  const cp = Math.cos(view.roamPitch);
  return [
    cp * Math.sin(view.roamYaw),
    cp * Math.cos(view.roamYaw),
    Math.sin(view.roamPitch),
  ];
}

function roamWalkBasis(view) {
  const forward = normalize3([Math.sin(view.roamYaw), Math.cos(view.roamYaw), 0]);
  const right = normalize3([Math.cos(view.roamYaw), -Math.sin(view.roamYaw), 0]);
  return { forward, right };
}

function updateRoam() {
  const now = performance.now();
  const dt = app.lastFrameTs ? Math.min(0.05, (now - app.lastFrameTs) / 1000) : 0;
  app.lastFrameTs = now;
  if (app.controlMode !== "roam" || dt <= 0 || shouldIgnoreKeyboard()) return;
  const view = viewForPaneKey(app.activeViewKey);
  const { forward, right } = roamWalkBasis(view);
  let move = [0, 0, 0];
  if (app.keys.has("w")) move = add3(move, forward);
  if (app.keys.has("s")) move = sub3(move, forward);
  if (app.keys.has("d")) move = add3(move, right);
  if (app.keys.has("a")) move = sub3(move, right);
  if (app.keys.has("q")) move = add3(move, [0, 0, 1]);
  if (app.keys.has("e")) move = sub3(move, [0, 0, 1]);
  if (len3(move) < 1e-6) return;
  markInteraction();
  const speed = ROAM_WALK_SPEED * (app.keys.has("shift") ? ROAM_FAST_MULTIPLIER : 1);
  view.roamPos = add3(view.roamPos, scale3(normalize3(move), speed * dt));
  app.renderDirty = true;
}

function shouldIgnoreKeyboard() {
  if (els.drawer.classList.contains("open")) return true;
  const tag = document.activeElement?.tagName;
  return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT";
}

function canvasPoint(ev) {
  const rect = els.canvas.getBoundingClientRect();
  return [ev.clientX - rect.left, ev.clientY - rect.top, rect.width, rect.height];
}

function handleCanvasClick(ev) {
  if (!annotationActive()) return;
  if (app.region.enabled) {
    handleRegionCanvasClick(ev);
    return;
  }
  if (!app.calibration.enabled) return;
  if (ev.button !== 0 || app.suppressNextClick) {
    app.suppressNextClick = false;
    return;
  }
  const [x, y, w, h] = canvasPoint(ev);
  const unit = app.calibration.nextUnit;
  const pane = paneAtPoint(x, y, w, h);
  if (!pane || !pointInPane(x, y, pane) || pane.unit !== unit) {
    showToast(`请在镜头 ${unit === 0 ? "A" : "B"} 的点云窗口里标注`);
    return;
  }
  const picked = pickNearestInPane(pane, x, y);
  if (!picked) {
    showToast(`没有点到镜头 ${unit === 0 ? "A" : "B"} 的点云，请靠近点再点`);
    return;
  }
  if (unit === 0) {
    app.calibration.pendingA = picked;
    app.calibration.nextUnit = 1;
    app.calibration.result = null;
  } else {
    if (!app.calibration.pendingA) {
      showToast("请先选择 A 点云里的对应点");
      app.calibration.nextUnit = 0;
      return;
    }
    app.calibration.pairs.push({
      label: `P${app.calibration.pairs.length + 1}`,
      a: app.calibration.pendingA,
      b: picked,
    });
    app.calibration.pendingA = null;
    app.calibration.nextUnit = 0;
    app.calibration.result = null;
  }
  saveCalibrationState();
  renderCalibration();
}

function handleRegionCanvasClick(ev) {
  if (ev.button !== 0 || app.suppressNextClick) {
    app.suppressNextClick = false;
    return;
  }
  const [x, y, w, h] = canvasPoint(ev);
  const pane = paneAtPoint(x, y, w, h);
  if (!pane || !pointInPane(x, y, pane)) {
    showToast("请在点云窗口里标注区域边界");
    return;
  }
  // 平面级标注：反投影到地面平面取 XY（可点空白处、所有点共面），而非取最近 3D 点
  const picked = unprojectToGroundPlane(pane, x, y);
  if (!picked) {
    showToast("无法定位标注点，请确认该窗口已加载点云");
    return;
  }
  const regionPoint = pointToRegionFrame(pane, picked);
  if (!regionPoint) return;
  app.region.points.push(regionPoint);
  app.region.closed = false;
  app.region.clipEnabled = false;
  saveRegionState();
  renderRegionCalibration();
  renderMarkers();
}

function pointToRegionFrame(pane, picked) {
  if (pane.unit === 1 || pane.key === "b") {
    const matrix = Array.isArray(app.calibration.result?.matrix) ? app.calibration.result.matrix : null;
    if (!matrix) {
      showToast("B 点云区域标定需要先完成多镜头融合标定，或改在 A/融合点云标注");
      return null;
    }
    return transformPoint4(matrix, picked);
  }
  return [...picked];
}

function pickNearest(unit, x, y, width, height) {
  const pane = paneForUnit(unit, width, height);
  if (!pane || !pointInPane(x, y, pane)) return null;
  return pickNearestInPane(pane, x, y);
}

function pickNearestInPane(pane, x, y) {
  const cloud = pane.cloud;
  if (!cloud.count) return null;
  const stride = Math.max(1, Math.floor(cloud.count / CALIB_PICK_MAX_SCAN_POINTS));
  let best = null;
  let bestD2 = Math.pow(Math.max(CALIB_PICK_RADIUS_PX, Number(els.pointSize.value || 2) * 10), 2);
  for (let i = 0; i < cloud.count; i += stride) {
    const idx = i * 3;
    const p = [cloud.data[idx], cloud.data[idx + 1], cloud.data[idx + 2]];
    const s = projectPointInPane(p, pane);
    if (!s) continue;
    const dx = s[0] - x;
    const dy = s[1] - y;
    const d2 = dx * dx + dy * dy;
    if (d2 < bestD2) {
      bestD2 = d2;
      best = p;
    }
  }
  return best;
}

// 区域标定专用：把屏幕点反投影到【水平地面平面】(Z=该云最低点)，得平面级 XY。
// 与 pickNearestInPane（取最近 3D 点、Z 各异）不同：可点空白处描线，所有点共面 → 真正的顶视平面标注。
// 服务端区域过滤只看 XY（无限高虚拟墙），故 Z 取地面仅用于让多边形落在一个平面上、便于可视化。
function unprojectToGroundPlane(pane, sx, sy) {
  const cloud = pane.cloud;
  if (!cloud || !cloud.count || !Number.isFinite(cloud.min[2])) return null;
  const inv = mat4Invert(pane.mvp);
  if (!inv) return null;
  const ndcX = ((sx - pane.x) / pane.w) * 2 - 1;
  const ndcY = 1 - ((sy - pane.y) / pane.h) * 2;
  // 反投影一个 NDC 点（含深度 ndcZ）回世界：inv·[ndcX,ndcY,ndcZ,1] → 除 w 得 scaled → /scale+center。
  const unproj = (ndcZ) => {
    const cx = inv[0] * ndcX + inv[4] * ndcY + inv[8] * ndcZ + inv[12];
    const cy = inv[1] * ndcX + inv[5] * ndcY + inv[9] * ndcZ + inv[13];
    const cz = inv[2] * ndcX + inv[6] * ndcY + inv[10] * ndcZ + inv[14];
    const cw = inv[3] * ndcX + inv[7] * ndcY + inv[11] * ndcZ + inv[15];
    if (Math.abs(cw) < 1e-9) return null;
    return [
      (cx / cw) / pane.scale + pane.center[0],
      (cy / cw) / pane.scale + pane.center[1],
      (cz / cw) / pane.scale + pane.center[2],
    ];
  };
  const nearP = unproj(-1), farP = unproj(1);
  if (!nearP || !farP) return null;
  const planeZ = cloud.min[2]; // 地面平面
  const dz = farP[2] - nearP[2];
  if (Math.abs(dz) < 1e-9) return null;
  const t = (planeZ - nearP[2]) / dz;
  return [
    nearP[0] + t * (farP[0] - nearP[0]),
    nearP[1] + t * (farP[1] - nearP[1]),
    planeZ,
  ];
}

function paneForUnit(unit, width, height) {
  let pane = app.renderPanes.find((p) => p.unit === unit);
  if (!pane) {
    const panes = buildRenderPanes({ width, height });
    pane = panes.find((p) => p.unit === unit);
  }
  return pane || null;
}

function pointInPane(x, y, pane) {
  return x >= pane.x && x <= pane.x + pane.w && y >= pane.y && y <= pane.y + pane.h;
}

function paneAtPoint(x, y, width, height) {
  const panes = app.renderPanes.length ? app.renderPanes : buildRenderPanes({ width, height });
  return panes.find((pane) => pointInPane(x, y, pane)) || panes[0] || null;
}

function projectPoint(p, width, height, unit = null) {
  let pane = unit == null ? app.renderPanes[0] : paneForUnit(unit, width, height);
  if (!pane) pane = buildRenderPanes({ width, height })[0];
  return pane ? projectPointInPane(p, pane) : null;
}

function projectPointInPane(p, pane) {
  const x = (p[0] - pane.center[0]) * pane.scale;
  const y = (p[1] - pane.center[1]) * pane.scale;
  const z = (p[2] - pane.center[2]) * pane.scale;
  const m = pane.mvp;
  const cx = m[0] * x + m[4] * y + m[8] * z + m[12];
  const cy = m[1] * x + m[5] * y + m[9] * z + m[13];
  const cz = m[2] * x + m[6] * y + m[10] * z + m[14];
  const cw = m[3] * x + m[7] * y + m[11] * z + m[15];
  if (cw <= 0 || cz / cw < -1 || cz / cw > 1) return null;
  return [
    pane.x + (cx / cw * 0.5 + 0.5) * pane.w,
    pane.y + (1 - (cy / cw * 0.5 + 0.5)) * pane.h,
  ];
}

function renderMarkers() {
  const rect = els.canvas.getBoundingClientRect();
  els.markerLayer.innerHTML = "";
  els.markerLayer.style.visibility = "visible";
  renderPaneFrames(rect);
  renderCoordinateAxes(rect);
  renderScanSweepGizmo(rect);
  renderRegionWall(rect);
  renderVehicleOverlay(rect);
  if (!app.calibration.enabled) return;
  const markers = calibrationMarkersForRender();
  for (const m of markers) {
    const p = projectPoint(m.point, rect.width, rect.height, m.unit);
    if (!p) continue;
    const el = document.createElement("div");
    el.className = `marker ${m.role === "a" ? "a" : "b"}`;
    el.textContent = m.label;
    el.style.left = `${p[0]}px`;
    el.style.top = `${p[1]}px`;
    els.markerLayer.append(el);
  }
}

function renderMarkersIfNeeded() {
  // 拖动/旋转/缩放时也逐帧重投影覆盖层，让标注区域、坐标轴等与点云 GL 渲染严格同步锁定，
  // 不再整层隐藏再回弹（那样标注区域看起来会"挪动、定不住"）。覆盖层节点很少且 detached 构建，
  // 逐帧重建开销远低于点云本身的逐帧 GL 重绘，不会引入卡顿。
  if (isInteractionActive()) {
    renderMarkers();
    app.overlayKey = overlayRenderKey();
    app.overlayDirty = false;
    return;
  }
  const key = overlayRenderKey();
  if (!app.overlayDirty && key === app.overlayKey) return;
  app.overlayKey = key;
  app.overlayDirty = false;
  renderMarkers();
}

function overlayRenderKey() {
  const rect = els.canvas.getBoundingClientRect();
  const round = (v, n = 2) => Number.isFinite(Number(v)) ? Number(v).toFixed(n) : "-";
  const paneKey = (app.renderPanes || []).map((pane) => [
    pane.key,
    round(pane.x, 0), round(pane.y, 0), round(pane.w, 0), round(pane.h, 0),
    pane.cloud?.count || 0,
    round(pane.center?.[0]), round(pane.center?.[1]), round(pane.center?.[2]),
    round(pane.scale, 6),
    round(pane.view?.yaw, 4), round(pane.view?.pitch, 4), round(pane.view?.distance, 4),
    round(pane.view?.pan?.[0]), round(pane.view?.pan?.[1]), round(pane.view?.pan?.[2]),
    round(pane.view?.roamYaw, 4), round(pane.view?.roamPitch, 4),
    round(pane.view?.roamPos?.[0]), round(pane.view?.roamPos?.[1]), round(pane.view?.roamPos?.[2]),
  ].join(",")).join("|");
  return [
    round(rect.width, 0), round(rect.height, 0),
    app.cloudMode, app.controlMode, app.activeViewKey,
    app.calibration.enabled ? 1 : 0,
    app.calibration.nextUnit,
    app.calibration.pairs.length,
    app.calibration.pendingA ? 1 : 0,
    app.region.enabled ? 1 : 0,
    app.region.closed ? 1 : 0,
    app.region.clipEnabled ? 1 : 0,
    app.region.points.length,
    app.liveAngles.a ?? "",
    app.liveAngles.b ?? "",
    app.deviceStatuses.a?.angleDegs ?? "",
    app.deviceStatuses.b?.angleDegs ?? "",
    paneKey,
  ].join(";");
}

function markOverlayDirty() {
  app.overlayDirty = true;
}

function markInteraction(duration = INTERACTION_SETTLE_MS) {
  app.interactiveUntil = Math.max(app.interactiveUntil || 0, performance.now() + duration);
}

function isInteractionActive() {
  return Boolean(app.dragging || performance.now() < (app.interactiveUntil || 0) || (app.controlMode === "roam" && app.keys.size > 0));
}

function renderRegionWall(rect) {
  // 只在标注状态（进入区域标定、未暂停/未完成）才画区域墙轮廓；平时查看/测量不显示，
  // 避免一直挂着分散注意。完成闭合后区域仍作为裁剪过滤生效（与轮廓显示无关）。
  if (!app.region.enabled) return;
  if (!app.region.points.length) return;
  const panes = app.renderPanes.length ? app.renderPanes : buildRenderPanes(rect);
  for (const pane of panes) {
    const points = regionPointsForPane(pane);
    const screen = points
      .map((p) => projectPointInPane(p, pane))
      .filter(Boolean)
      .map((p) => [p[0] - pane.x, p[1] - pane.y]);
    if (screen.length < 2) continue;
    const layer = document.createElement("div");
    layer.className = `region-wall-pane ${app.region.closed ? "closed" : "open"}`;
    layer.style.left = `${pane.x}px`;
    layer.style.top = `${pane.y}px`;
    layer.style.width = `${pane.w}px`;
    layer.style.height = `${pane.h}px`;
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("class", "region-wall-svg");
    svg.setAttribute("viewBox", `0 0 ${pane.w} ${pane.h}`);
    const pointsAttr = screen.map((p) => `${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(" ");
    const shape = document.createElementNS("http://www.w3.org/2000/svg", app.region.closed ? "polygon" : "polyline");
    shape.setAttribute("points", pointsAttr);
    shape.setAttribute("class", app.region.clipEnabled ? "clip-on" : "clip-off");
    svg.append(shape);
    screen.forEach((p, i) => {
      const dot = document.createElementNS("http://www.w3.org/2000/svg", "circle");
      dot.setAttribute("cx", p[0]);
      dot.setAttribute("cy", p[1]);
      dot.setAttribute("r", "4");
      dot.setAttribute("class", "region-dot");
      svg.append(dot);
      const text = document.createElementNS("http://www.w3.org/2000/svg", "text");
      text.setAttribute("x", p[0] + 7);
      text.setAttribute("y", p[1] - 7);
      text.setAttribute("class", "region-label");
      text.textContent = `R${i + 1}`;
      svg.append(text);
    });
    layer.append(svg);
    els.markerLayer.append(layer);
  }
}

// 可视分割叠加：把融合点云世界系的 车体框/货箱框/轴线 投影到融合视图上画 SVG。
// 几何由服务端按融合云坐标导出（overlay.go），前端只投影连线，不需懂测量坐标系。
// 只在融合视图画（A/B 分镜各自设备系，与融合世界框不同帧）。
const OVERLAY_BOX_EDGES = [[0,1],[1,2],[2,3],[3,0],[4,5],[5,6],[6,7],[7,4],[0,4],[1,5],[2,6],[3,7]];
function renderVehicleOverlay(rect) {
  const ov = app.overlay;
  if (!ov || !ov.valid || app.cloudMode !== "fused") return;
  const panes = app.renderPanes.length ? app.renderPanes : buildRenderPanes(rect);
  const SVGNS = "http://www.w3.org/2000/svg";
  for (const pane of panes) {
    if (pane.key !== "fused") continue;
    const layer = document.createElement("div");
    layer.className = "overlay-pane";
    layer.style.left = `${pane.x}px`;
    layer.style.top = `${pane.y}px`;
    layer.style.width = `${pane.w}px`;
    layer.style.height = `${pane.h}px`;
    const svg = document.createElementNS(SVGNS, "svg");
    svg.setAttribute("class", "overlay-svg");
    svg.setAttribute("viewBox", `0 0 ${pane.w} ${pane.h}`);
    const line = (a, b, cls) => {
      const sa = projectPointInPane(a, pane), sb = projectPointInPane(b, pane);
      if (!sa || !sb) return;
      const el = document.createElementNS(SVGNS, "line");
      el.setAttribute("x1", (sa[0] - pane.x).toFixed(1));
      el.setAttribute("y1", (sa[1] - pane.y).toFixed(1));
      el.setAttribute("x2", (sb[0] - pane.x).toFixed(1));
      el.setAttribute("y2", (sb[1] - pane.y).toFixed(1));
      el.setAttribute("class", cls);
      svg.append(el);
    };
    const box = (corners, cls) => {
      if (!Array.isArray(corners) || corners.length !== 8) return;
      for (const [a, b] of OVERLAY_BOX_EDGES) line(corners[a], corners[b], cls);
    };
    box(ov.vehicle_box, "ov-vehicle");
    if (ov.has_cargo_box) box(ov.cargo_box, "ov-cargo");
    if (Array.isArray(ov.axle_lines)) {
      for (const ln of ov.axle_lines) if (Array.isArray(ln) && ln.length === 2) line(ln[0], ln[1], "ov-axle");
    }
    layer.append(svg);
    els.markerLayer.append(layer);
  }
}

function regionPointsForPane(pane) {
  if (pane.unit === 1 || pane.key === "b") {
    const matrix = Array.isArray(app.calibration.result?.matrix) ? app.calibration.result.matrix : null;
    const inv = matrix ? invertRigidMatrix4(matrix) : null;
    return inv ? app.region.points.map((p) => transformPoint4(inv, p)) : [];
  }
  return app.region.points;
}

function renderCoordinateAxes(rect) {
  const panes = app.renderPanes.length ? app.renderPanes : buildRenderPanes(rect);
  for (const pane of panes) {
    const axisPane = document.createElement("div");
    axisPane.className = `axis-pane ${pane.key}`;
    axisPane.style.left = `${pane.x}px`;
    axisPane.style.top = `${pane.y}px`;
    axisPane.style.width = `${pane.w}px`;
    axisPane.style.height = `${pane.h}px`;
    els.markerLayer.append(axisPane);
    const origin = [0, 0, 0];
    const axisLength = coordinateAxisLength(pane);
    const originScreen = projectPointInPane(origin, pane);
    if (originScreen) {
      const dot = document.createElement("div");
      dot.className = "axis-origin";
      dot.style.left = `${originScreen[0] - pane.x}px`;
      dot.style.top = `${originScreen[1] - pane.y}px`;
      axisPane.append(dot);
    }
    for (const axis of coordinateAxes()) {
      const end = scale3(axis.dir, axisLength);
      const neg = scale3(axis.dir, -axisLength * 0.35);
      appendAxisSegment(pane, axisPane, origin, end, axis.key, false);
      appendAxisSegment(pane, axisPane, neg, origin, axis.key, true);
      appendAxisLabel(pane, axisPane, end, axis.key);
    }
  }
}

function coordinateAxes() {
  return [
    { key: "x", dir: [1, 0, 0] },
    { key: "y", dir: [0, 1, 0] },
    { key: "z", dir: [0, 0, 1] },
  ];
}

function coordinateAxisLength(pane) {
  const radius = Number(pane?.frame?.radius || 1000);
  return Math.max(500, Math.min(4000, radius * 0.35));
}

function appendAxisSegment(pane, axisPane, startPoint, endPoint, key, negative) {
  const start = projectPointInPane(startPoint, pane);
  const end = projectPointInPane(endPoint, pane);
  if (!start || !end) return;
  const dx = end[0] - start[0];
  const dy = end[1] - start[1];
  const length = Math.hypot(dx, dy);
  if (length < 4) return;
  const line = document.createElement("div");
  line.className = `axis-line ${key}${negative ? " negative" : ""}`;
  line.style.left = `${start[0] - pane.x}px`;
  line.style.top = `${start[1] - pane.y}px`;
  line.style.width = `${length}px`;
  line.style.transform = `rotate(${Math.atan2(dy, dx)}rad)`;
  axisPane.append(line);
}

function appendAxisLabel(pane, axisPane, point, key) {
  const p = projectPointInPane(point, pane);
  if (!p) return;
  const label = document.createElement("div");
  label.className = `axis-label ${key}`;
  label.textContent = key.toUpperCase();
  label.style.left = `${p[0] - pane.x}px`;
  label.style.top = `${p[1] - pane.y}px`;
  axisPane.append(label);
}

// 扫描云台扫掠方位区间：点云绕 Z 轴（扫描原点）的方位角覆盖弧，data-driven（直接从点云算，
// 不依赖设备配置）。返回 {a0,a1,spanDeg}：CCW 从 a0 扫到 a1，a1 为旋转终点（箭头端），弧度。
// 与前向模型一致：P_world 经 Rz(+h) 绕 Z 累积，heading 增大 = 方位角增大（CCW），故终点取高方位端。
// 结果按 count 缓存，避免每次覆盖层刷新都重算大点云。
function scanSweepExtent(cloud) {
  if (!cloud?.count || cloud.count < 40) return null;
  if (cloud._sweep && cloud._sweep.count === cloud.count) return cloud._sweep.ext;
  const data = cloud.data;
  const n = cloud.count;
  const stride = Math.max(1, Math.floor(n / 2400)); // 采样上限 ~2400，够定方位区间
  const az = [];
  for (let i = 0; i < n; i += stride) {
    const x = data[i * 3];
    const y = data[i * 3 + 1];
    if (x * x + y * y < 1e-4) continue; // 原点附近点无方位意义（单位：m）
    az.push(Math.atan2(y, x));
  }
  let ext = null;
  if (az.length >= 8) {
    az.sort((p, q) => p - q);
    // 覆盖弧 = 最大方位间隙的补集（处理 ±π 环绕）：间隙后是弧起点、间隙前是弧终点
    let gap = -1, gapAt = 0;
    for (let i = 0; i < az.length; i++) {
      const next = i + 1 < az.length ? az[i + 1] : az[0] + Math.PI * 2;
      const d = next - az[i];
      if (d > gap) { gap = d; gapAt = i; }
    }
    const a0 = az[(gapAt + 1) % az.length];
    let a1 = az[gapAt];
    if (a1 < a0) a1 += Math.PI * 2; // 保证 a1>a0，CCW 方向
    ext = { a0, a1, spanDeg: Math.round((a1 - a0) * 180 / Math.PI) };
  }
  cloud._sweep = { count: cloud.count, ext };
  return ext;
}

// 扫描旋转方向 gizmo：在 Z 轴根部（地面 XY 平面、绕原点）画一段紧凑弧形箭头，
// 标出点云是绕哪个方向、从多大方位区间叠加出来的。每个 pane 用各自点云（A/B 各自设备系，
// 原点=该单元扫描中心；融合系以 A 原点为参考）。SVG 渲染，仿 region-wall 同范式。
function renderScanSweepGizmo(rect) {
  const panes = app.renderPanes.length ? app.renderPanes : buildRenderPanes(rect);
  for (const pane of panes) {
    const ext = scanSweepExtent(pane.cloud);
    if (!ext) continue;
    const R = coordinateAxisLength(pane) * 0.55; // 紧凑环，落在坐标轴十字内、不挡点云
    const arcPt = (t) => {
      const sp = projectPointInPane([R * Math.cos(t), R * Math.sin(t), 0], pane);
      return sp ? [sp[0] - pane.x, sp[1] - pane.y] : null;
    };
    const steps = Math.max(10, Math.min(120, Math.round(ext.spanDeg / 3)));
    const arc = [];
    for (let i = 0; i <= steps; i++) {
      const p = arcPt(ext.a0 + (ext.a1 - ext.a0) * (i / steps));
      if (p) arc.push(p);
    }
    if (arc.length < 2) continue;
    const tip = arcPt(ext.a1);
    const pre = arcPt(ext.a1 - 0.09);
    const radial = (t, r) => {
      const sp = projectPointInPane([R * r * Math.cos(t), R * r * Math.sin(t), 0], pane);
      return sp ? [sp[0] - pane.x, sp[1] - pane.y] : null;
    };
    const layer = document.createElement("div");
    layer.className = "sweep-gizmo-pane";
    layer.style.left = `${pane.x}px`;
    layer.style.top = `${pane.y}px`;
    layer.style.width = `${pane.w}px`;
    layer.style.height = `${pane.h}px`;
    const NS = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(NS, "svg");
    svg.setAttribute("class", "sweep-gizmo-svg");
    svg.setAttribute("viewBox", `0 0 ${pane.w} ${pane.h}`);
    // 弧线
    const poly = document.createElementNS(NS, "polyline");
    poly.setAttribute("class", "sweep-arc");
    poly.setAttribute("points", arc.map((p) => `${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(" "));
    svg.append(poly);
    // 起止径向刻度
    for (const t of [ext.a0, ext.a1]) {
      const a = radial(t, 0.82), b = radial(t, 1.18);
      if (!a || !b) continue;
      const tick = document.createElementNS(NS, "line");
      tick.setAttribute("class", "sweep-tick");
      tick.setAttribute("x1", a[0].toFixed(1)); tick.setAttribute("y1", a[1].toFixed(1));
      tick.setAttribute("x2", b[0].toFixed(1)); tick.setAttribute("y2", b[1].toFixed(1));
      svg.append(tick);
    }
    // 旋转方向箭头（弧终点切线方向）
    if (tip && pre) {
      const ang = Math.atan2(tip[1] - pre[1], tip[0] - pre[0]);
      const sz = 10;
      const p1 = [tip[0] - sz * Math.cos(ang - 0.42), tip[1] - sz * Math.sin(ang - 0.42)];
      const p2 = [tip[0] - sz * Math.cos(ang + 0.42), tip[1] - sz * Math.sin(ang + 0.42)];
      const head = document.createElementNS(NS, "polygon");
      head.setAttribute("class", "sweep-arrow");
      head.setAttribute("points", `${tip[0].toFixed(1)},${tip[1].toFixed(1)} ${p1[0].toFixed(1)},${p1[1].toFixed(1)} ${p2[0].toFixed(1)},${p2[1].toFixed(1)}`);
      svg.append(head);
      const label = document.createElementNS(NS, "text");
      label.setAttribute("class", "sweep-label");
      label.setAttribute("x", (tip[0] + 8 * Math.cos(ang + Math.PI / 2)).toFixed(1));
      label.setAttribute("y", (tip[1] + 8 * Math.sin(ang + Math.PI / 2)).toFixed(1));
      label.textContent = `↺${ext.spanDeg}°`;
      svg.append(label);
    }
    layer.append(svg);
    els.markerLayer.append(layer);
  }
}

function calibrationMarkersForRender() {
  const markers = [];
  const fused = app.cloudMode === "fused";
  const matrix = Array.isArray(app.calibration.result?.matrix) ? app.calibration.result.matrix : null;
  for (const pair of app.calibration.pairs) {
    markers.push({ unit: fused ? null : 0, role: "a", label: `${pair.label}A`, point: pair.a });
    markers.push({
      unit: fused ? null : 1,
      role: "b",
      label: `${pair.label}B`,
      point: fused && matrix ? transformPoint4(matrix, pair.b) : pair.b,
    });
  }
  if (app.calibration.pendingA) {
    markers.push({ unit: fused ? null : 0, role: "a", label: "待配A", point: app.calibration.pendingA });
  }
  return markers;
}

function renderPaneFrames(rect) {
  const panes = app.renderPanes.length ? app.renderPanes : buildRenderPanes(rect);
  for (const pane of panes) {
    const frame = document.createElement("div");
    frame.className = `pane-frame ${pane.key}`;
    frame.style.left = `${pane.x}px`;
    frame.style.top = `${pane.y}px`;
    frame.style.width = `${pane.w}px`;
    frame.style.height = `${pane.h}px`;
    // A/B 分镜的状态信息移进左上角相机卡片（layoutCamPreviews），此处不再重复画标签；融合/辅助窗口仍画。
    if (pane.unit !== 0 && pane.unit !== 1) {
      const label = document.createElement("span");
      label.textContent = paneFrameLabel(pane);
      frame.append(label);
    }
    els.markerLayer.append(frame);
  }
}

function paneFrameLabel(pane) {
  const parts = [pane.label];
  if (pane.unit === 0 || pane.unit === 1) {
    const role = pane.unit === 0 ? "a" : "b";
    const status = app.deviceStatuses[role];
    const angle = status?.angleDegs ?? app.liveAngles[role];
    parts.push(`当前角度 ${angle == null ? "--" : `${fmt2(angle)}°`}`);
  }
  parts.push(`${pane.cloud.count.toLocaleString()} 点`);
  return parts.join(" · ");
}

function renderCalibration() {
  const unitText = app.calibration.nextUnit === 0 ? "A 点云" : "B 点云";
  const n = app.calibration.pairs.length;
  els.calibHint.textContent = app.calibration.enabled
    ? `正在标注：在 ${unitText} 点同一真实特征的第 ${n + 1} 个对应点。已 ${n} 对，建议 ≥4 对且散开不共线。`
    : "不用标记板：点「开始标注」，在 A、B 点云里依次点同一个真实特征（车角、轮子、后视镜…）。点 4+ 对、尽量散开不共线；点完「计算外参」。粗点即可，扫描时会自动 ICP 精修到毫米级。两台固定不动，标一次永久复用。";
  els.startCalib.textContent = app.calibration.enabled ? "暂停标注" : "开始标注";
  els.calibToolbar.hidden = !app.calibration.enabled;
  const canUndo = Boolean(app.calibration.pendingA || app.calibration.pairs.length);
  const canClear = Boolean(app.calibration.pendingA || app.calibration.pairs.length || app.calibration.result);
  for (const btn of [els.undoCalib, els.undoCalibView]) btn.disabled = !canUndo;
  for (const btn of [els.clearCalib, els.clearCalibView]) btn.disabled = !canClear;
  els.calibPairs.innerHTML = "";
  for (const pair of app.calibration.pairs) {
    const row = document.createElement("div");
    row.className = "pair-row";
    row.innerHTML = `<strong>${pair.label}</strong><code>A ${fmtPoint(pair.a)}\nB ${fmtPoint(pair.b)}</code>`;
    els.calibPairs.append(row);
  }
  if (app.calibration.pendingA) {
    const row = document.createElement("div");
    row.className = "pair-row";
    row.innerHTML = `<strong>待配</strong><code>A ${fmtPoint(app.calibration.pendingA)}\nB 请选择</code>`;
    els.calibPairs.append(row);
  }
  if (app.calibration.result) {
    els.calibResult.textContent = calibrationResultText(app.calibration.result);
  } else {
    els.calibResult.textContent = "点 4+ 对散开的对应点后算 B→A（最少 3 对、不共线）。粗略即可，扫描时自动 ICP 精修。";
  }
}

function undoCalibrationPoint() {
  if (app.calibration.pendingA) {
    app.calibration.pendingA = null;
    app.calibration.nextUnit = 0;
  } else if (app.calibration.pairs.length) {
    const last = app.calibration.pairs.pop();
    app.calibration.pendingA = last.a;
    app.calibration.nextUnit = 1;
  } else {
    showToast("没有可撤销的标注点");
    renderCalibration();
    return;
  }
  app.calibration.result = null;
  saveCalibrationState();
  renderCalibration();
}

function clearCalibrationPoints({ persist = true } = {}) {
  app.calibration.pairs = [];
  app.calibration.pendingA = null;
  app.calibration.nextUnit = 0;
  app.calibration.result = null;
  if (persist) saveCalibrationState();
  renderCalibration();
}

function renderRegionCalibration() {
  if (!els.regionHint) return;
  const count = app.region.points.length;
  if (app.region.enabled) {
    els.regionHint.textContent = `正在区域标定：已标 ${count} 个边界点，完成后会自动闭合为虚拟墙。`;
  } else if (app.region.closed) {
    els.regionHint.textContent = `区域墙已闭合，共 ${count} 个边界点；${app.region.clipEnabled ? "扫描会只保留墙内点云。" : "墙内过滤未开启。"}`;
  } else {
    els.regionHint.textContent = count > 0
      ? `已标 ${count} 个边界点，至少 3 点后可完成闭合。`
      : "开始后在点云窗口依次标注区域边界点，完成后自动闭合为虚拟墙。";
  }
  els.startRegion.textContent = app.region.enabled ? "暂停区域标定" : "开始区域标定";
  els.finishRegion.disabled = count < 3;
  els.undoRegion.disabled = count === 0;
  els.clearRegion.disabled = count === 0;
  els.toggleRegionClip.disabled = !app.region.closed;
  els.toggleRegionClip.textContent = app.region.clipEnabled ? "关闭墙内过滤" : "只显示墙内点云";
  els.regionPoints.innerHTML = "";
  app.region.points.forEach((p, i) => {
    const row = document.createElement("div");
    row.className = "pair-row";
    row.innerHTML = `<strong>R${i + 1}</strong><code>${fmtPoint(p)}</code>`;
    els.regionPoints.append(row);
  });
}

function undoRegionPoint() {
  if (!app.region.points.length) {
    showToast("没有可撤销的区域点");
    renderRegionCalibration();
    return;
  }
  app.region.points.pop();
  app.region.closed = false;
  app.region.clipEnabled = false;
  saveRegionState();
  renderRegionCalibration();
  renderMarkers();
}

function clearRegionCalibration() {
  app.region.points = [];
  app.region.closed = false;
  app.region.clipEnabled = false;
  saveRegionState();
  renderRegionCalibration();
  renderMarkers();
}

function finishRegionCalibration() {
  if (app.region.points.length < 3) {
    showToast("区域标定至少需要 3 个点");
    return;
  }
  if (Math.abs(regionPolygonArea2(app.region.points)) < 1e-3) {
    showToast("区域标定点不能共线");
    return;
  }
  app.region.closed = true;
  app.region.enabled = false;
  app.region.clipEnabled = true;
  saveRegionState();
  resumeDeferredCloudRefresh();
  applyRegionClipToLoadedClouds();
  renderRegionCalibration();
  renderScanMeta();
  renderMarkers();
}

function toggleRegionClip() {
  if (!app.region.closed) {
    showToast("请先完成区域闭合");
    return;
  }
  app.region.clipEnabled = !app.region.clipEnabled;
  if (app.region.clipEnabled) applyRegionClipToLoadedClouds();
  saveRegionState();
  renderRegionCalibration();
  renderScanMeta();
  renderMarkers();
}

function applyRegionClipToLoadedClouds() {
  if (!app.region.closed || app.region.points.length < 3) return;
  const matrix = Array.isArray(app.calibration.result?.matrix) ? app.calibration.result.matrix : null;
  app.clouds[0].filter((x, y) => pointInRegionXY(x, y, app.region.points));
  if (matrix) {
    app.clouds[1].filter((x, y, z) => {
      const p = transformPoint4(matrix, [x, y, z]);
      return pointInRegionXY(p[0], p[1], app.region.points);
    });
  } else if (app.clouds[1].count) {
    showToast("B 点云需要多镜头融合标定后才能按区域墙裁剪");
  }
  app.fusedCloud.filter((x, y) => pointInRegionXY(x, y, app.region.points));
  app.fusedCount = app.fusedCloud.count;
  markRoamFitDirty();
}

function regionClipActive() {
  return Boolean(app.region.clipEnabled && app.region.closed && app.region.points.length >= 3);
}

function clipRegionPointPayload(frame) {
  if (!regionClipActive() || frame.unit == null) return frame;
  const points = frame.points || [];
  const colors = frame.colors || null;
  const total = Math.floor(points.length / 3);
  if (!total) return frame;
  const out = [];
  const outColors = colors ? [] : null;
  for (let i = 0; i < total; i++) {
    const j = i * 3;
    const x = Number(points[j]);
    const y = Number(points[j + 1]);
    const z = Number(points[j + 2]);
    if (!pointPassesRegion(frame.unit, x, y, z)) continue;
    out.push(x, y, z);
    if (outColors) outColors.push(colors[j], colors[j + 1], colors[j + 2]);
  }
  return {
    ...frame,
    points: new Float32Array(out),
    colors: outColors ? new Uint8Array(outColors) : null,
  };
}

function pointPassesRegion(unit, x, y, z) {
  if (unit === 1) {
    const matrix = Array.isArray(app.calibration.result?.matrix) ? app.calibration.result.matrix : null;
    if (!matrix) return true;
    const p = transformPoint4(matrix, [x, y, z]);
    return pointInRegionXY(p[0], p[1], app.region.points);
  }
  return pointInRegionXY(x, y, app.region.points);
}

function pointInRegionXY(x, y, points) {
  if (!Array.isArray(points) || points.length < 3) return true;
  let inside = false;
  let j = points.length - 1;
  for (let i = 0; i < points.length; i++) {
    const [xi, yi] = points[i];
    const [xj, yj] = points[j];
    if (pointOnRegionEdge(x, y, xi, yi, xj, yj)) return true;
    if ((yi > y) !== (yj > y)) {
      const crossX = ((xj - xi) * (y - yi)) / (yj - yi) + xi;
      if (x <= crossX) inside = !inside;
    }
    j = i;
  }
  return inside;
}

function regionPolygonArea2(points) {
  let sum = 0;
  for (let i = 0; i < points.length; i++) {
    const j = (i + 1) % points.length;
    sum += points[i][0] * points[j][1] - points[j][0] * points[i][1];
  }
  return sum;
}

function pointOnRegionEdge(x, y, ax, ay, bx, by) {
  const cross = (x - ax) * (by - ay) - (y - ay) * (bx - ax);
  if (Math.abs(cross) > 1e-3) return false;
  return (x - ax) * (x - bx) + (y - ay) * (y - by) <= 1e-3;
}

function fmtPoint(p) {
  return p.map((v) => `${v.toFixed(1)}`).join(", ");
}

// 端点返回 native(米) B→A；result.matrix 用显示约定(diag(1,1,-1,1) 共轭 + 平移 mm)。
// displayBToAToNativeBToA 是对合(D·()·D)，native<->display 同一函数；再把平移 m→mm。
function nativeBToAToDisplay(native) {
  const disp = displayBToAToNativeBToA(native);
  disp[3] *= 1000;
  disp[7] *= 1000;
  disp[11] *= 1000;
  return disp;
}

// 一键自动标定：调后端 site-calib（两镜头 sweep+采图→ArUco 自标定 B→A），结果写入 calibration.result。
async function runAutoCalibration() {
  const camA = cameraByRole("a");
  const camB = cameraByRole("b");
  const lenMm = Number(els.markerLenMm?.value || 150);
  const markerLen = (lenMm > 0 ? lenMm : 150) / 1000;
  const q = new URLSearchParams();
  if (camA?.ip) q.set("unit_a_ip", camA.ip);
  if (camB?.ip) q.set("unit_b_ip", camB.ip);
  q.set("marker_len", String(markerLen));

  const setBusy = (busy, msg) => {
    els.autoCalibBtn.disabled = busy;
    els.autoCalibBtn.textContent = busy ? "标定中…" : "⚡ 一键自动标定";
    if (msg) els.autoCalibHint.textContent = msg;
  };
  setBusy(true, "采图中…两镜头各扫一圈，请勿遮挡标记（约 30–90 秒）");
  els.autoCalibResult.hidden = true;
  try {
    const resp = await api(`/v1/scans/laser/site-calib?${q.toString()}`, { method: "POST" });
    const ncommon = resp.n_common ?? 0;
    const rmsMm = ((resp.rms_m ?? 0) * 1000).toFixed(1);
    if (resp.ok && Array.isArray(resp.b_to_a) && resp.b_to_a.length === 16) {
      app.calibration.result = {
        matrix: nativeBToAToDisplay(resp.b_to_a),
        source: "aruco",
        rms_m: resp.rms_m,
        n_common: ncommon,
      };
      saveCalibrationState();
      renderCalibration();
      els.autoCalibResult.hidden = false;
      els.autoCalibResult.textContent = `✓ 标定成功\n公共标记 ${ncommon} 个，RMS ${rmsMm} mm\nB→A 已写入，可直接起扫融合。`;
      els.autoCalibHint.textContent = "标定完成。换车位 / 移动镜头后需重标。";
      showToast(`自动标定成功：公共 ${ncommon}，RMS ${rmsMm}mm`);
    } else {
      els.autoCalibResult.hidden = false;
      els.autoCalibResult.textContent =
        `✗ 未达标：公共标记 ${ncommon} 个，RMS ${rmsMm} mm\n` +
        `检查：≥4 个标记两镜头都看得到、贴牢不反光、边长填对。\n${resp.log || ""}`;
      els.autoCalibHint.textContent = "未达标，调整标记后重试。";
      showToast("自动标定未达标，见结果区");
    }
  } catch (err) {
    els.autoCalibResult.hidden = false;
    els.autoCalibResult.textContent = `✗ 出错：${err.message}`;
    els.autoCalibHint.textContent = "标定出错，重试或检查设备 / 标记。";
    showToast(`自动标定失败：${err.message}`);
  } finally {
    setBusy(false);
  }
}

// 让容器可"按住拖动滑动"（桌面鼠标抓拽，类似平板手指滑）。触屏走原生滑动不拦。
// 拖动超阈值时抑制随之而来的子元素 click（避免拖一下误切帧）。
function enableDragScroll(el) {
  if (!el) return;
  let down = false, moved = false, startX = 0, startLeft = 0;
  el.addEventListener("pointerdown", (e) => {
    if (e.pointerType === "touch") return; // 触屏交给浏览器原生横滑（带惯性）
    down = true;
    moved = false;
    startX = e.clientX;
    startLeft = el.scrollLeft;
    el.setPointerCapture?.(e.pointerId);
    el.classList.add("dragging");
  });
  el.addEventListener("pointermove", (e) => {
    if (!down) return;
    const dx = e.clientX - startX;
    if (Math.abs(dx) > 4) moved = true;
    el.scrollLeft = startLeft - dx;
  });
  const end = (e) => {
    if (!down) return;
    down = false;
    el.classList.remove("dragging");
    el.releasePointerCapture?.(e.pointerId);
    if (moved) {
      // 拖动结束抑制紧随的缩略图 click（捕获阶段拦截，once 自清，setTimeout 兜底）
      const kill = (ev) => { ev.stopPropagation(); ev.preventDefault(); };
      el.addEventListener("click", kill, { capture: true, once: true });
      setTimeout(() => el.removeEventListener("click", kill, true), 0);
    }
  };
  el.addEventListener("pointerup", end);
  el.addEventListener("pointercancel", end);
}

// 收到点云扫描页的相机 RGB 帧（laser.frame，扫描中由 laserworker 推送，仅云台转动时有画面）：
// 画到该镜头卡片的画布并标记 hasFrame（首帧即展开），卡片位置由 layoutCamPreviews 贴到分镜左上角。
function renderScanPreview(payload) {
  const unit = Number(payload.unit);
  const role = unit === 0 ? "a" : unit === 1 ? "b" : null;
  if (!role || !payload.jpeg_b64) return;
  app.camPreview[role].hasFrame = true;
  const canvas = els[`camCanvas${role.toUpperCase()}`];
  if (canvas) {
    const img = new Image();
    img.onload = () => {
      canvas.width = payload.w || img.width;
      canvas.height = payload.h || img.height;
      canvas.getContext("2d").drawImage(img, 0, 0, canvas.width, canvas.height);
    };
    img.src = `data:image/jpeg;base64,${payload.jpeg_b64}`;
  }
  layoutCamPreviews(app.renderPanes);
}

// 把 A/B 相机卡片定位到各自分镜窗口左上角，卡片头即该窗口状态信息（角度·点数），可收起。
// 仅在该单元有独立分镜（分镜模式）时显示；融合模式无单相机窗口则隐藏。
// 每帧都被渲染循环调用，故只在值真正变化时写 DOM，避免拖动时无谓 reflow 拖慢平移。
function layoutCamPreviews(panes) {
  panes = panes && panes.length ? panes : [];
  for (const role of ["a", "b"]) {
    const R = role.toUpperCase();
    const card = els[`camPreview${R}`];
    if (!card) continue;
    const unit = role === "a" ? 0 : 1;
    const pane = panes.find((p) => p.unit === unit);
    if (!pane) {
      if (!card.hidden) card.hidden = true;
      continue;
    }
    if (card.hidden) card.hidden = false;
    // 只贴位置（左上角），尺寸交给 CSS 默认 + 用户手动 resize；仅在变化时写。
    const left = `${Math.round(pane.x + 12)}px`;
    const top = `${Math.round(pane.y + 70)}px`;
    if (card.style.left !== left) card.style.left = left;
    if (card.style.top !== top) card.style.top = top;
    const status = els[`camStatus${R}`];
    if (status) {
      const label = paneFrameLabel(pane);
      if (status.textContent !== label) status.textContent = label;
    }
    const st = app.camPreview[role];
    const toggle = els[`camToggle${R}`];
    if (toggle) {
      const disp = st.hasFrame ? "" : "none";
      if (toggle.style.display !== disp) toggle.style.display = disp;
    }
    const wantCollapsed = !(st.hasFrame && !st.collapsed);
    if (card.classList.contains("collapsed") !== wantCollapsed) {
      card.classList.toggle("collapsed", wantCollapsed);
    }
  }
}

// ===== 实时取景标定（全屏，看相机 RGB 图对标记）=====
function openFramingPage() {
  els.framingOverlay.hidden = false;
  els.framingResult.hidden = true;
  resetFramingPanes();
  els.framingStatus.textContent = "设好扫描角后点「扫描取景」，两镜头各扫一段。";
  const lenMm = Number(els.markerLenMm?.value);
  if (lenMm > 0) els.framingMarkerLen.value = lenMm;
  prefillFramingControls();
  // 取景帧经 ws laser.frame 推送：进页即预连实时通道，避免扫描时通道未建导致看不到胶片
  connectWs().catch((err) => console.warn("取景实时预连失败（扫描时会再试）", err));
}

function closeFramingPage() {
  els.framingOverlay.hidden = true;
}

function resetFramingPanes() {
  for (const role of ["A", "B"]) {
    const c = els[`framingCanvas${role}`];
    c.getContext("2d").clearRect(0, 0, c.width, c.height);
    els[`framingStrip${role}`].innerHTML = "";
    els[`framingCount${role}`].textContent = "—";
  }
  app.framing = {
    a: { frames: 0, ids: new Set(), shots: [], selected: null },
    b: { frames: 0, ids: new Set(), shots: [], selected: null },
  };
}

// 用设备当前扫描角/速度预填控件（取不到用默认线性扫程）。
async function prefillFramingControls() {
  const fill = async (role, startEl, stopEl, defStart, defStop) => {
    const cam = cameraByRole(role);
    let c = {};
    if (cam?.ip) {
      try {
        c = controlFromInfo(await api(`/v1/scans/laser/device-info?ip=${encodeURIComponent(cam.ip)}`));
      } catch {
        /* 取不到用默认 */
      }
    }
    startEl.value = c.scan_start_angle ?? c.scanStartAngle ?? defStart;
    stopEl.value = c.scan_stop_angle ?? c.scanStopAngle ?? defStop;
    const sp = c.scan_speed ?? c.scanSpeed;
    if (Number(sp) > 0) els.framingSpeed.value = sp;
  };
  await fill("a", els.framingAStart, els.framingAStop, 0, 90);
  await fill("b", els.framingBStart, els.framingBStop, -170, -10);
}

// 扫描取景并解算：POST site-framing（角度/速度=云台控制），帧经 ws laser.frame 边扫边渲染，结果回写标定。
async function runFraming() {
  const camA = cameraByRole("a");
  const camB = cameraByRole("b");
  const lenMm = Number(els.framingMarkerLen.value || 150);
  const markerLen = (lenMm > 0 ? lenMm : 150) / 1000;
  const q = new URLSearchParams();
  if (camA?.ip) q.set("unit_a_ip", camA.ip);
  if (camB?.ip) q.set("unit_b_ip", camB.ip);
  q.set("marker_len", String(markerLen));
  q.set("preview_width", "1280");
  const num = (el) => {
    const n = Number(el.value);
    return Number.isFinite(n) ? n : null;
  };
  const aS = num(els.framingAStart), aE = num(els.framingAStop);
  const bS = num(els.framingBStart), bE = num(els.framingBStop);
  if (aS != null && aE != null) {
    q.set("a_start", String(aS));
    q.set("a_stop", String(aE));
  }
  if (bS != null && bE != null) {
    q.set("b_start", String(bS));
    q.set("b_stop", String(bE));
  }
  const sp = num(els.framingSpeed);
  if (sp != null && sp > 0) q.set("speed", String(sp));

  resetFramingPanes();
  els.framingResult.hidden = true;
  app.framingStopping = false;
  els.runFraming.hidden = true; // 扫描中换成「停止」按钮（与点云扫描同范式）
  if (els.framingStop) { els.framingStop.hidden = false; els.framingStop.disabled = false; }
  els.framingStatus.textContent = "云台转动中，相机逐帧推送…（约 30–90 秒，勿遮挡标记）。可随时「停止」用已采集帧解算。";
  try {
    // 帧经 laser.frame ws 推送：扫描前必须确保实时通道已连，否则看不到胶片（解算仍会照常返回）
    await connectWs().catch((err) => {
      console.warn("实时连接失败，胶片预览不可用", err);
      els.framingStatus.textContent = "⚠ 实时通道未连，看不到胶片；解算仍在进行。可点顶部「连接」后重试。";
    });
    const resp = await api(`/v1/scans/laser/site-framing?${q.toString()}`, { method: "POST" });
    const ncommon = resp.n_common ?? 0;
    const rmsMm = ((resp.rms_m ?? 0) * 1000).toFixed(1);
    if (resp.ok && Array.isArray(resp.b_to_a) && resp.b_to_a.length === 16) {
      app.calibration.result = {
        matrix: nativeBToAToDisplay(resp.b_to_a),
        source: "aruco",
        rms_m: resp.rms_m,
        n_common: ncommon,
      };
      saveCalibrationState();
      renderCalibration();
      els.framingResult.hidden = false;
      els.framingResult.textContent = `✓ 标定成功\n公共标记 ${ncommon} 个，RMS ${rmsMm} mm\nB→A 已写入，可直接起扫融合。`;
      els.framingStatus.textContent = "标定完成。换车位 / 移动镜头后需重标。";
      showToast(`取景标定成功：公共 ${ncommon}，RMS ${rmsMm}mm`);
    } else {
      els.framingResult.hidden = false;
      els.framingResult.textContent =
        `✗ 未达标：公共标记 ${ncommon} 个，RMS ${rmsMm} mm\n` +
        `看上方两镜头胶片：确认 ≥4 个标记两镜头都拍到、绿框套住、边长填对。\n${resp.log || ""}`;
      els.framingStatus.textContent = "未达标，按胶片调整标记 / 扫描角后重试。";
      showToast("取景标定未达标，见结果区");
    }
  } catch (err) {
    els.framingResult.hidden = false;
    els.framingResult.textContent = `✗ 出错：${err.message}`;
    els.framingStatus.textContent = "出错，重试或检查设备 / 标记。";
    showToast(`取景标定失败：${err.message}`);
  } finally {
    els.runFraming.hidden = false;
    els.runFraming.disabled = false;
    if (els.framingStop) els.framingStop.hidden = true;
  }
}

// 停止取景：向两单元发 SCAN_STOP，设备回 READY 后 framing-stream 自然收尾，
// 解算器用已采集的帧出结果（够 4 个公共标记即标定成功）——与点云扫描「停止」同范式。
async function stopFraming() {
  if (els.framingStop) els.framingStop.disabled = true;
  app.framingStopping = true;
  els.framingStatus.textContent = "停止中…正用已采集的帧解算（够 4 个公共标记即出结果）。";
  const cams = [cameraByRole("a"), cameraByRole("b")].filter((c) => c?.ip);
  await Promise.allSettled(
    cams.map((cam) =>
      api(`/v1/scans/laser/device-command?ip=${encodeURIComponent(cam.ip)}`, {
        method: "POST",
        body: JSON.stringify({ cmd: "SCAN_STOP" }),
      })
    )
  );
}

// 渲染一帧取景：画到对应镜头画布 + 叠 ArUco 绿框 + 入胶片缩略图 + 更新计数。
// 把一帧（jpeg + ArUco 绿框）画到该镜头固定的主显示区。
function drawFramingShot(role, shot) {
  const canvas = els[`framingCanvas${role}`];
  if (!canvas || !shot?.jpeg_b64) return;
  const img = new Image();
  img.onload = () => {
    const w = shot.w || img.width, h = shot.h || img.height;
    canvas.width = w;
    canvas.height = h;
    const ctx = canvas.getContext("2d");
    ctx.drawImage(img, 0, 0, w, h);
    ctx.lineWidth = Math.max(2, Math.round(w / 400));
    ctx.strokeStyle = "#27e08a";
    ctx.fillStyle = "#27e08a";
    ctx.font = `${Math.max(14, Math.round(w / 60))}px sans-serif`;
    for (const m of shot.markers || []) {
      const px = m.px || [];
      if (px.length < 4) continue;
      ctx.beginPath();
      ctx.moveTo(px[0][0], px[0][1]);
      for (let i = 1; i < 4; i++) ctx.lineTo(px[i][0], px[i][1]);
      ctx.closePath();
      ctx.stroke();
      ctx.fillText(`#${m.id}`, px[0][0], Math.max(12, px[0][1] - 4));
    }
  };
  img.src = `data:image/jpeg;base64,${shot.jpeg_b64}`;
}

// 点胶片缩略图：把主显示区切到该帧并 pin 住（不再跟随最新），高亮当前选中。
function selectFramingShot(role, index) {
  const st = app.framing?.[role.toLowerCase()];
  if (!st || !st.shots[index]) return;
  st.selected = index;
  drawFramingShot(role, st.shots[index]);
  const strip = els[`framingStrip${role}`];
  [...strip.children].forEach((el, i) => el.classList.toggle("active", i === index));
}

// 收到一帧取景：存帧 + 入胶片缩略条；未手动 pin 时主区跟随最新，已 pin 则保持不跳。
// 主区固定、胶片条单独横向滚动；带标记的帧缩略图描绿边，便于挑"≥4 标记"的帧。
function renderFramingFrame(payload) {
  const unit = Number(payload.unit);
  const role = unit === 0 ? "A" : unit === 1 ? "B" : null;
  if (!role || !payload.jpeg_b64) return;
  const st = app.framing?.[role.toLowerCase()];
  if (!st) return;
  const shot = {
    jpeg_b64: payload.jpeg_b64,
    markers: Array.isArray(payload.markers) ? payload.markers : [],
    w: payload.w,
    h: payload.h,
    heading: Number(payload.heading_deg || 0),
  };
  // pin 跟随判定：之前停在最新（或未选）则继续跟随，否则保持用户挑的那帧不跳
  const following = st.selected === null || st.selected === st.shots.length - 1;
  const idx = st.shots.length;
  st.shots.push(shot);
  st.frames = st.shots.length;
  for (const m of shot.markers) st.ids.add(m.id);

  const strip = els[`framingStrip${role}`];
  const thumb = document.createElement("img");
  thumb.className = `framing-thumb${shot.markers.length ? " has-markers" : ""}`;
  thumb.title = `航向 ${shot.heading.toFixed(1)}° · 标记 ${shot.markers.length}（点击查看）`;
  thumb.draggable = false; // 禁原生图片拖拽，避免与拖动滑动冲突
  thumb.addEventListener("click", () => selectFramingShot(role, idx));
  strip.appendChild(thumb);
  // 缩略图独立降采样生成，不占主图分辨率
  const timg = new Image();
  timg.onload = () => {
    const tw = 96, th = Math.max(1, Math.round(tw * (shot.h || timg.height) / (shot.w || timg.width)));
    const tc = document.createElement("canvas");
    tc.width = tw;
    tc.height = th;
    tc.getContext("2d").drawImage(timg, 0, 0, tw, th);
    thumb.src = tc.toDataURL("image/jpeg", 0.6);
  };
  timg.src = `data:image/jpeg;base64,${shot.jpeg_b64}`;

  if (following) {
    st.selected = idx;
    drawFramingShot(role, shot);
    [...strip.children].forEach((el, i) => el.classList.toggle("active", i === idx));
    strip.scrollLeft = strip.scrollWidth; // 跟随滚到最新
  }
  els[`framingCount${role}`].textContent = `帧 ${st.frames} · 标记 ${st.ids.size}`;
}

function solveCalibration() {
  if (app.calibration.pairs.length < 3) {
    showToast("至少需要 3 对标定点");
    return;
  }
  try {
    const result = solveRigidTransform(
      app.calibration.pairs.map((p) => p.b),
      app.calibration.pairs.map((p) => p.a),
    );
    app.calibration.result = result;
    saveCalibrationState();
    els.calibResult.textContent = calibrationResultText(result);
  } catch (err) {
    showToast(`标定失败：${err.message}`);
  }
}

function calibrationResultText(result) {
  if (!Array.isArray(result?.matrix)) return "标定结果格式异常，请重新计算。";
  const nativeSite = toNativeSiteJson(result);
  return [
    `平均残差: ${Number(result.meanError || 0).toFixed(2)} mm`,
    `最大残差: ${Number(result.maxError || 0).toFixed(2)} mm`,
    "",
    "Web/端侧 BToA(mm):",
    JSON.stringify({ b_to_a_mm: result.matrix }, null, 2),
    "",
    "native site 外参(m, 未翻转坐标):",
    JSON.stringify(nativeSite, null, 2),
  ].join("\n");
}

function solveRigidTransform(source, target) {
  const n = source.length;
  const cs = centroid(source);
  const ct = centroid(target);
  const h = [
    [0, 0, 0],
    [0, 0, 0],
    [0, 0, 0],
  ];
  for (let i = 0; i < n; i++) {
    const s = sub3(source[i], cs);
    const t = sub3(target[i], ct);
    for (let r = 0; r < 3; r++) {
      for (let c = 0; c < 3; c++) h[r][c] += s[r] * t[c];
    }
  }
  const q = largestEigenQuaternion(h);
  const r = quatToMat3(q);
  const t = sub3(ct, mat3MulVec(r, cs));
  const matrix = [
    r[0], r[1], r[2], t[0],
    r[3], r[4], r[5], t[1],
    r[6], r[7], r[8], t[2],
    0, 0, 0, 1,
  ];
  const errors = source.map((p, i) => len3(sub3(add3(mat3MulVec(r, p), t), target[i])));
  return {
    rotation: r,
    translation: t,
    matrix,
    errors,
    meanError: errors.reduce((a, b) => a + b, 0) / errors.length,
    maxError: Math.max(...errors),
  };
}

function largestEigenQuaternion(h) {
  const sxx = h[0][0], sxy = h[0][1], sxz = h[0][2];
  const syx = h[1][0], syy = h[1][1], syz = h[1][2];
  const szx = h[2][0], szy = h[2][1], szz = h[2][2];
  const tr = sxx + syy + szz;
  const n = [
    [tr, syz - szy, szx - sxz, sxy - syx],
    [syz - szy, sxx - syy - szz, sxy + syx, szx + sxz],
    [szx - sxz, sxy + syx, -sxx + syy - szz, syz + szy],
    [sxy - syx, szx + sxz, syz + szy, -sxx - syy + szz],
  ];
  let q = [1, 0, 0, 0];
  for (let iter = 0; iter < 80; iter++) {
    const nq = [0, 0, 0, 0];
    for (let r = 0; r < 4; r++) {
      for (let c = 0; c < 4; c++) nq[r] += n[r][c] * q[c];
    }
    const l = Math.hypot(...nq) || 1;
    q = nq.map((v) => v / l);
  }
  return q;
}

function quatToMat3(q) {
  const [w, x, y, z] = q;
  const xx = x * x, yy = y * y, zz = z * z;
  const xy = x * y, xz = x * z, yz = y * z;
  const wx = w * x, wy = w * y, wz = w * z;
  return [
    1 - 2 * (yy + zz), 2 * (xy - wz), 2 * (xz + wy),
    2 * (xy + wz), 1 - 2 * (xx + zz), 2 * (yz - wx),
    2 * (xz - wy), 2 * (yz + wx), 1 - 2 * (xx + yy),
  ];
}

function toNativeSiteJson(result) {
  const m = displayBToAToNativeBToA(result.matrix);
  m[3] /= 1000;
  m[7] /= 1000;
  m[11] /= 1000;
  return { b_to_a: m };
}

function displayBToAToNativeBToA(matrix) {
  const signs = [1, 1, -1, 1];
  const out = matrix.slice();
  for (let r = 0; r < 4; r++) {
    for (let c = 0; c < 4; c++) {
      out[r * 4 + c] = matrix[r * 4 + c] * signs[r] * signs[c];
    }
  }
  return out;
}

function centroid(points) {
  const c = [0, 0, 0];
  for (const p of points) {
    c[0] += p[0];
    c[1] += p[1];
    c[2] += p[2];
  }
  return c.map((v) => v / points.length);
}

function sub3(a, b) { return [a[0] - b[0], a[1] - b[1], a[2] - b[2]]; }
function add3(a, b) { return [a[0] + b[0], a[1] + b[1], a[2] + b[2]]; }
function scale3(a, s) { return [a[0] * s, a[1] * s, a[2] * s]; }
function len3(a) { return Math.hypot(a[0], a[1], a[2]); }
function mat3MulVec(m, v) {
  return [
    m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
    m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
    m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
  ];
}

function transformPoint4(m, p) {
  const x = m[0] * p[0] + m[1] * p[1] + m[2] * p[2] + m[3];
  const y = m[4] * p[0] + m[5] * p[1] + m[6] * p[2] + m[7];
  const z = m[8] * p[0] + m[9] * p[1] + m[10] * p[2] + m[11];
  const w = m[12] * p[0] + m[13] * p[1] + m[14] * p[2] + m[15];
  if (Math.abs(w) > 1e-6 && Math.abs(w - 1) > 1e-6) {
    return [x / w, y / w, z / w];
  }
  return [x, y, z];
}

function invertRigidMatrix4(m) {
  if (!Array.isArray(m) || m.length < 16) return null;
  const r00 = m[0], r01 = m[1], r02 = m[2], tx = m[3];
  const r10 = m[4], r11 = m[5], r12 = m[6], ty = m[7];
  const r20 = m[8], r21 = m[9], r22 = m[10], tz = m[11];
  return [
    r00, r10, r20, -(r00 * tx + r10 * ty + r20 * tz),
    r01, r11, r21, -(r01 * tx + r11 * ty + r21 * tz),
    r02, r12, r22, -(r02 * tx + r12 * ty + r22 * tz),
    0, 0, 0, 1,
  ];
}

function mat4Identity() {
  return new Float32Array([1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]);
}

function mat4Perspective(fovy, aspect, near, far) {
  const f = 1 / Math.tan(fovy / 2);
  const nf = 1 / (near - far);
  return new Float32Array([
    f / aspect, 0, 0, 0,
    0, f, 0, 0,
    0, 0, (far + near) * nf, -1,
    0, 0, (2 * far * near) * nf, 0,
  ]);
}

function mat4LookAt(eye, target, up) {
  let z = normalize3(sub3(eye, target));
  let x = normalize3(cross3(up, z));
  if (len3(x) < 1e-6) x = [1, 0, 0];
  const y = cross3(z, x);
  return new Float32Array([
    x[0], y[0], z[0], 0,
    x[1], y[1], z[1], 0,
    x[2], y[2], z[2], 0,
    -dot3(x, eye), -dot3(y, eye), -dot3(z, eye), 1,
  ]);
}

// 通用 4x4 求逆（列优先，与 pane.mvp 同序）；奇异返回 null。用于屏幕点反投影到世界射线。
function mat4Invert(m) {
  const a00 = m[0], a01 = m[1], a02 = m[2], a03 = m[3];
  const a10 = m[4], a11 = m[5], a12 = m[6], a13 = m[7];
  const a20 = m[8], a21 = m[9], a22 = m[10], a23 = m[11];
  const a30 = m[12], a31 = m[13], a32 = m[14], a33 = m[15];
  const b00 = a00 * a11 - a01 * a10, b01 = a00 * a12 - a02 * a10, b02 = a00 * a13 - a03 * a10;
  const b03 = a01 * a12 - a02 * a11, b04 = a01 * a13 - a03 * a11, b05 = a02 * a13 - a03 * a12;
  const b06 = a20 * a31 - a21 * a30, b07 = a20 * a32 - a22 * a30, b08 = a20 * a33 - a23 * a30;
  const b09 = a21 * a32 - a22 * a31, b10 = a21 * a33 - a23 * a31, b11 = a22 * a33 - a23 * a32;
  let det = b00 * b11 - b01 * b10 + b02 * b09 + b03 * b08 - b04 * b07 + b05 * b06;
  if (!det) return null;
  det = 1.0 / det;
  return [
    (a11 * b11 - a12 * b10 + a13 * b09) * det,
    (a02 * b10 - a01 * b11 - a03 * b09) * det,
    (a31 * b05 - a32 * b04 + a33 * b03) * det,
    (a22 * b04 - a21 * b05 - a23 * b03) * det,
    (a12 * b08 - a10 * b11 - a13 * b07) * det,
    (a00 * b11 - a02 * b08 + a03 * b07) * det,
    (a32 * b02 - a30 * b05 - a33 * b01) * det,
    (a20 * b05 - a22 * b02 + a23 * b01) * det,
    (a10 * b10 - a11 * b08 + a13 * b06) * det,
    (a01 * b08 - a00 * b10 - a03 * b06) * det,
    (a30 * b04 - a31 * b02 + a33 * b00) * det,
    (a21 * b02 - a20 * b04 - a23 * b00) * det,
    (a11 * b07 - a10 * b09 - a12 * b06) * det,
    (a00 * b09 - a01 * b07 + a02 * b06) * det,
    (a31 * b01 - a30 * b03 - a32 * b00) * det,
    (a20 * b03 - a21 * b01 + a22 * b00) * det,
  ];
}

function mat4Multiply(a, b) {
  const out = new Float32Array(16);
  for (let c = 0; c < 4; c++) {
    for (let r = 0; r < 4; r++) {
      out[c * 4 + r] =
        a[0 * 4 + r] * b[c * 4 + 0] +
        a[1 * 4 + r] * b[c * 4 + 1] +
        a[2 * 4 + r] * b[c * 4 + 2] +
        a[3 * 4 + r] * b[c * 4 + 3];
    }
  }
  return out;
}

function cross3(a, b) {
  return [
    a[1] * b[2] - a[2] * b[1],
    a[2] * b[0] - a[0] * b[2],
    a[0] * b[1] - a[1] * b[0],
  ];
}

function dot3(a, b) {
  return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
}

function normalize3(v) {
  const l = len3(v) || 1;
  return [v[0] / l, v[1] / l, v[2] / l];
}

function openDrawer(panel = "station") {
  setDrawerPanel(panel);
  els.drawer.classList.add("open");
  els.drawer.setAttribute("aria-hidden", "false");
  els.drawerBackdrop.hidden = false;
}

function closeDrawer() {
  els.drawer.classList.remove("open");
  els.drawer.setAttribute("aria-hidden", "true");
  els.drawerBackdrop.hidden = true;
}

function setDrawerPanel(panel) {
  document.querySelectorAll("[data-drawer-tab]").forEach((btn) => {
    btn.classList.toggle("active", btn.getAttribute("data-drawer-tab") === panel);
  });
  document.querySelectorAll("[data-drawer-panel]").forEach((el) => {
    el.classList.toggle("active", el.getAttribute("data-drawer-panel") === panel);
  });
  if (panel === "device") {
    refreshDevice({ silent: true });
  }
}

function setCloudMode(mode) {
  mode = mode === "fused" ? "fused" : "split";
  app.cloudMode = mode;
  if (mode === "fused") {
    app.activeViewKey = "fused";
    if (app.fusionUnavailable) showToast("当前工位未标定，无法显示融合点云");
  } else if (app.activeViewKey === "fused") {
    app.activeViewKey = "a";
  }
  if (app.controlMode === "roam") prepareRoamViews(true);
  markRenderDirty();
  document.querySelectorAll("[data-cloud-mode]").forEach((btn) => {
    btn.classList.toggle("active", btn.getAttribute("data-cloud-mode") === mode);
  });
  renderScanMeta();
}

function bindEvents() {
  document.querySelectorAll("[data-open-panel]").forEach((btn) => {
    btn.addEventListener("click", () => openDrawer(btn.getAttribute("data-open-panel") || "station"));
  });
  document.querySelectorAll("[data-drawer-tab]").forEach((btn) => {
    btn.addEventListener("click", () => setDrawerPanel(btn.getAttribute("data-drawer-tab") || "station"));
  });
  document.querySelectorAll("[data-cloud-mode]").forEach((btn) => {
    btn.addEventListener("click", () => setCloudMode(btn.getAttribute("data-cloud-mode") || "split"));
  });
  document.querySelectorAll("[data-control-mode]").forEach((btn) => {
    btn.addEventListener("click", () => setControlMode(btn.getAttribute("data-control-mode") || "orbit"));
  });
  els.closeConfig.addEventListener("click", closeDrawer);
  els.drawerBackdrop.addEventListener("click", closeDrawer);
  window.addEventListener("keydown", (ev) => {
    if (ev.key === "Escape") closeDrawer();
    handleCalibrationKey(ev);
    handleRoamKey(ev, true);
  });
  window.addEventListener("keyup", (ev) => handleRoamKey(ev, false));
  window.addEventListener("blur", () => app.keys.clear());
  els.saveConnection.addEventListener("click", () => {
    syncConnectionFromInputs(true);
    showToast("连接配置已保存");
  });
  els.connectWs.addEventListener("click", () => {
    connectWs().catch((err) => showToast(err.message));
  });
  els.stationSelect.addEventListener("change", () => {
    app.activeStationId = els.stationSelect.value;
    app.selectedCameraId = station().cameras[0]?.id || "";
    app.deviceStatuses = { a: null, b: null };
    app.deviceInfos = { a: null, b: null };
    app.liveAngles = { a: null, b: null };
    saveState();
    renderStations();
    refreshStationDeviceStatuses({ silent: true }).catch((err) => console.warn("设备状态刷新失败", err));
    restoreActiveScan({ clearInactive: true })
      .catch((err) => showToast(`恢复扫描失败：${err.message}`))
      .finally(() => {
        restoreCalibrationState();
        restoreRegionState();
      });
  });
  els.stationName.addEventListener("change", () => {
    station().name = els.stationName.value.trim() || "未命名工位";
    saveState();
    renderStations();
  });
  els.addStation.addEventListener("click", () => {
    const id = makeId("station");
    app.stations.push({ id, name: `扫描工位 ${app.stations.length + 1}`, cameras: [] });
    app.activeStationId = id;
    app.selectedCameraId = "";
    saveState();
    renderStations();
    restoreCalibrationState();
    restoreRegionState();
  });
  els.deleteStation.addEventListener("click", () => {
    if (app.stations.length <= 1) return showToast("至少保留一个工位");
    app.stations = app.stations.filter((s) => s.id !== app.activeStationId);
    app.activeStationId = app.stations[0].id;
    app.selectedCameraId = app.stations[0].cameras[0]?.id || "";
    saveState();
    renderStations();
    restoreCalibrationState();
    restoreRegionState();
  });
  els.addCamera.addEventListener("click", () => {
    const ip = els.cameraIp.value.trim();
    if (!/^\d{1,3}(\.\d{1,3}){3}$/.test(ip)) return showToast("请输入相机 IP");
    station().cameras.push({
      id: makeId("cam"),
      name: els.cameraName.value.trim() || `相机 ${station().cameras.length + 1}`,
      ip,
      role: els.cameraRole.value,
    });
    els.cameraName.value = "";
    els.cameraIp.value = "";
    saveState();
    renderStations();
  });
  els.deviceSelect.addEventListener("change", () => {
    app.selectedCameraId = els.deviceSelect.value;
    saveState();
    renderStations();
    refreshDevice({ silent: true });
  });
  els.startScan.addEventListener("click", toggleScan);
  els.refreshDevice.addEventListener("click", refreshDevice);
  els.applySettings.addEventListener("click", applySettings);
  els.scanStart.addEventListener("input", updateScanAngleHint);
  els.scanAngle.addEventListener("input", updateScanAngleHint);
  document.querySelectorAll("[data-command]").forEach((btn) => {
    btn.addEventListener("click", () => sendCommand(btn.getAttribute("data-command")));
  });
  document.querySelectorAll("[data-view]").forEach((btn) => {
    btn.addEventListener("click", () => {
      setControlMode("orbit");
      setViewPreset(btn.getAttribute("data-view"));
    });
  });
  els.resetView.addEventListener("click", () => {
    setControlMode("orbit");
    setViewPreset("free", true);
  });
  updateViewButtons(); // 初始高亮「自由」
  els.pointBudget.addEventListener("change", () => {
    app.pointBudget = Number(els.pointBudget.value) || 1_200_000;
    saveState(); // 持久化容量/镜头，刷新后保留
    if (!resetClouds()) showToast("标注中点云已冻结，暂停标注后再调整点数上限");
  });
  els.keepRatio.addEventListener("change", () => {
    app.keepRatio = Number(els.keepRatio.value) || 1;
    saveState(); // 持久化保留率
  });
  els.pointSize.addEventListener("input", () => {
    markRenderDirty();
    markInteraction(80);
  });
  els.startCalib.addEventListener("click", () => {
    app.calibration.enabled = !app.calibration.enabled;
    if (app.calibration.enabled) {
      app.region.enabled = false;
      setCloudMode("split");
      setControlMode("orbit");
      showToast("标注中点云已冻结");
    } else {
      resumeDeferredCloudRefresh();
    }
    els.startCalib.textContent = app.calibration.enabled ? "暂停标注" : "开始标注";
    renderCalibration();
    renderRegionCalibration();
  });
  els.undoCalib.addEventListener("click", undoCalibrationPoint);
  els.undoCalibView.addEventListener("click", undoCalibrationPoint);
  els.clearCalib.addEventListener("click", clearCalibrationPoints);
  els.clearCalibView.addEventListener("click", clearCalibrationPoints);
  els.solveCalib.addEventListener("click", solveCalibration);
  els.autoCalibBtn.addEventListener("click", runAutoCalibration);
  els.openFraming?.addEventListener("click", openFramingPage);
  els.closeFraming?.addEventListener("click", closeFramingPage);
  els.runFraming?.addEventListener("click", runFraming);
  els.framingStop?.addEventListener("click", stopFraming);
  enableDragScroll(els.framingStripA);
  enableDragScroll(els.framingStripB);
  for (const role of ["a", "b"]) {
    els[`camToggle${role.toUpperCase()}`]?.addEventListener("click", () => {
      app.camPreview[role].collapsed = !app.camPreview[role].collapsed;
      layoutCamPreviews(app.renderPanes);
    });
  }
  els.startRegion.addEventListener("click", () => {
    app.region.enabled = !app.region.enabled;
    if (app.region.enabled) {
      app.calibration.enabled = false;
      app.region.points = []; // 开始即清空上一次的描点/多边形，从空白重画
      app.region.closed = false;
      app.region.clipEnabled = false;
      saveRegionState();
      // 优先在【融合点云】上画一次：区域是世界系多边形，服务端按标定 b_to_a 自动套用到 A/B
      // （point_filter.filterXYZByRegion 按 unit 变换判定），不需分别在 A/B 上画。
      const fusedReady = !app.fusionUnavailable && app.fusedCloud && app.fusedCloud.count > 0;
      if (fusedReady) setCloudMode("fused");
      setControlMode("orbit");
      setViewPreset("top", true); // 直接进顶视并固化，顶视下点击描点
      showToast(fusedReady
        ? "区域标定：在融合点云顶视下描点，A/B 区域自动按标定换算"
        : "区域标定：顶视下描点（暂无融合点云，先在分镜画，A/B 会按标定自动换算）");
    } else {
      resumeDeferredCloudRefresh();
    }
    renderCalibration();
    renderRegionCalibration();
    renderMarkers();
  });
  els.finishRegion.addEventListener("click", finishRegionCalibration);
  els.undoRegion.addEventListener("click", undoRegionPoint);
  els.clearRegion.addEventListener("click", clearRegionCalibration);
  els.toggleRegionClip.addEventListener("click", toggleRegionClip);
  els.canvas.addEventListener("click", handleCanvasClick);
  els.canvas.addEventListener("contextmenu", (ev) => ev.preventDefault());
  els.canvas.addEventListener("pointerdown", (ev) => {
    ev.preventDefault();
    markInteraction();
    const [x, y, w, h] = canvasPoint(ev);
    const pane = paneAtPoint(x, y, w, h);
    if (pane) {
      app.activeViewKey = pane.key;
      app.dragPaneKey = pane.key;
    }
    app.dragging = true;
    app.dragButton = ev.button;
    app.lastPointer = [ev.clientX, ev.clientY];
    app.clickCandidate = {
      button: ev.button,
      x: ev.clientX,
      y: ev.clientY,
      moved: false,
    };
    app.suppressNextClick = false;
    els.canvas.setPointerCapture(ev.pointerId);
  });
  els.canvas.addEventListener("pointermove", (ev) => {
    if (!app.dragging) return;
    markInteraction();
    if (app.clickCandidate) {
      const totalDx = ev.clientX - app.clickCandidate.x;
      const totalDy = ev.clientY - app.clickCandidate.y;
      if (Math.hypot(totalDx, totalDy) > CALIB_CLICK_MAX_MOVE_PX) {
        app.clickCandidate.moved = true;
      }
    }
    const dx = ev.clientX - app.lastPointer[0];
    const dy = ev.clientY - app.lastPointer[1];
    app.lastPointer = [ev.clientX, ev.clientY];
    const pane = app.renderPanes.find((p) => p.key === app.dragPaneKey) || null;
    const view = viewForPaneKey(app.dragPaneKey);
    if (app.controlMode === "roam") {
      if (app.dragButton !== 0) return;
      view.roamYaw += dx * ROAM_LOOK_SENSITIVITY;
      view.roamPitch = Math.max(-1.4, Math.min(1.4, view.roamPitch - dy * ROAM_LOOK_SENSITIVITY));
    } else if (app.dragButton === 2) {
      panView(dx, dy, view, pane);
    } else if (app.viewPreset === "top" || app.viewPreset === "side") {
      // 顶视/侧视固化：左键拖动只平移，不旋转（不会滑成自由视角）。要旋转先点「自由」。
      panView(dx, dy, view, pane);
    } else {
      view.yaw -= dx * 0.006;
      view.pitch = Math.max(-1.45, Math.min(1.45, view.pitch + dy * 0.006));
    }
    app.renderDirty = true;
  });
  const stopDrag = (ev) => {
    if (app.clickCandidate && ev?.type === "pointerup") {
      const totalDx = ev.clientX - app.clickCandidate.x;
      const totalDy = ev.clientY - app.clickCandidate.y;
      const moved = app.clickCandidate.moved || Math.hypot(totalDx, totalDy) > CALIB_CLICK_MAX_MOVE_PX;
      app.suppressNextClick = annotationActive() && app.clickCandidate.button === 0 && moved;
      if (app.suppressNextClick) {
        window.setTimeout(() => { app.suppressNextClick = false; }, 250);
      }
    }
    app.clickCandidate = null;
    app.dragging = false;
    app.dragButton = 0;
    app.dragPaneKey = "";
  };
  els.canvas.addEventListener("pointerup", stopDrag);
  els.canvas.addEventListener("pointercancel", stopDrag);
  els.canvas.addEventListener("wheel", (ev) => {
    ev.preventDefault();
    markInteraction();
    const [x, y, w, h] = canvasPoint(ev);
    const pane = paneAtPoint(x, y, w, h);
    if (pane) app.activeViewKey = pane.key;
    const view = viewForPaneKey(pane?.key || app.activeViewKey);
    if (app.controlMode === "roam") return;
    view.distance = Math.max(VIEW_MIN_DISTANCE, Math.min(VIEW_MAX_DISTANCE, view.distance * (ev.deltaY > 0 ? 1.08 : 0.92)));
    app.renderDirty = true;
  }, { passive: false });
}

function handleRoamKey(ev, down) {
  if (app.controlMode !== "roam") return;
  const key = ev.key.toLowerCase();
  const mapped = key === "shift" ? "shift" : key;
  if (!["w", "a", "s", "d", "q", "e", "shift"].includes(mapped)) return;
  if (down && shouldIgnoreKeyboard()) return;
  ev.preventDefault();
  if (down) {
    app.keys.add(mapped);
    markInteraction();
  } else {
    app.keys.delete(mapped);
  }
}

function handleCalibrationKey(ev) {
  if (!annotationActive() || shouldIgnoreKeyboard()) return;
  const key = ev.key.toLowerCase();
  const undo = (ev.ctrlKey || ev.metaKey) && key === "z";
  const backspace = key === "backspace";
  if (!undo && !backspace) return;
  ev.preventDefault();
  if (app.region.enabled) undoRegionPoint();
  else undoCalibrationPoint();
}

async function init() {
  loadState();
  els.endpoint.value = app.endpoint;
  els.token.value = app.token;
  if (app.pointBudget) els.pointBudget.value = String(app.pointBudget);
  if (app.keepRatio) els.keepRatio.value = String(app.keepRatio);
  renderStations();
  restoreCalibrationState();
  restoreRegionState();
  renderScanMeta();
  renderCalibration();
  renderRegionCalibration();
  bindEvents();
  initGl();
  renderLoop();
  await bootstrapStationSession();
  renderStations();
  renderScanMeta();
  await refreshStationDeviceStatuses({ silent: true });
  await restoreActiveScan({ silent: true });
  await loadLastScan(); // 无进行中扫描时，默认载入上次扫描结果
  restoreCalibrationState();
  restoreRegionState();
  startDeviceStatusPolling();
  startActiveScanPolling();
  setInterval(() => { if (!isScanTerminalState()) updateScanBanner(); }, 1000); // 已用时每秒刷新
}

init().catch((err) => {
  console.warn("初始化失败", err);
  showToast(`初始化失败：${err.message}`);
});
