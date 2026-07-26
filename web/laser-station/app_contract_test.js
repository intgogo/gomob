const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const MEASURED_XYZ_SHA256 = "a".repeat(64);
const MEASURED_FINAL_B_TO_A_SHA256 = "b".repeat(64);

function makeElement(id = "") {
  const attributes = new Map();
  return {
    id,
    value: id === "pointBudgetInput" ? "1200000" : "",
    textContent: "",
    innerHTML: "",
    hidden: id === "framingOverlay",
    disabled: false,
    width: 800,
    height: 600,
    clientWidth: 800,
    clientHeight: 600,
    style: {},
    dataset: {},
    className: "",
    classList: {
      add() {},
      remove() {},
      toggle() {},
      contains() { return false; },
    },
    addEventListener() {},
    removeEventListener() {},
    appendChild() {},
    append() {},
    remove() {},
    focus() {},
    setPointerCapture() {},
    getBoundingClientRect() { return { left: 0, top: 0, width: 800, height: 600 }; },
    getContext() {
      return {
        clearRect() {},
        drawImage() {},
        save() {},
        restore() {},
        translate() {},
        rotate() {},
        scale() {},
        beginPath() {},
        moveTo() {},
        lineTo() {},
        stroke() {},
        fill() {},
        arc() {},
        fillText() {},
        strokeRect() {},
      };
    },
    setAttribute(name, value) { attributes.set(name, String(value)); },
    getAttribute(name) { return attributes.get(name) || ""; },
  };
}

function loadApp() {
  const elements = new Map();
  const element = (id) => {
    if (!elements.has(id)) elements.set(id, makeElement(id));
    return elements.get(id);
  };
  const storage = new Map();
  const fetchCalls = [];
  const sockets = [];
  let fetchImpl = async () => { throw new Error("未配置 fetch"); };
  const sandbox = {
    __LASER_STATION_TEST__: true,
    console,
    URL,
    URLSearchParams,
    Headers,
    FormData,
    TextDecoder,
    TextEncoder,
    ArrayBuffer,
    DataView,
    Float32Array,
    Uint8Array,
    Uint32Array,
    Int32Array,
    Blob,
    AbortController,
    performance,
    setTimeout,
    clearTimeout,
    setInterval,
    clearInterval,
    requestAnimationFrame() { return 0; },
    cancelAnimationFrame() {},
    async domToCanvas() { throw new Error("契约测试不执行截图链路"); },
    fetch: async (...args) => {
      fetchCalls.push(args);
      return fetchImpl(...args);
    },
    localStorage: {
      getItem(key) { return storage.get(key) || null; },
      setItem(key, value) { storage.set(key, String(value)); },
      removeItem(key) { storage.delete(key); },
    },
    document: {
      getElementById: element,
      querySelector: (selector) => element(selector),
      querySelectorAll: () => [],
      createElement: (tag) => element(`created-${tag}-${elements.size}`),
      body: element("body"),
      documentElement: element("documentElement"),
      addEventListener() {},
    },
    location: { origin: "http://page.local" },
    navigator: {},
    devicePixelRatio: 1,
    addEventListener() {},
    removeEventListener() {},
    confirm() { return false; },
    WebSocket: class {
      static CONNECTING = 0;
      static OPEN = 1;
      constructor(url) {
        this.url = url;
        this.readyState = 0;
        sockets.push(this);
      }
      open() {
        this.readyState = 1;
        this.onopen?.();
      }
      close() {
        this.readyState = 3;
        queueMicrotask(() => this.onclose?.());
      }
    },
  };
  sandbox.window = sandbox;
  sandbox.globalThis = sandbox;
  vm.createContext(sandbox);
  const source = fs.readFileSync(path.join(__dirname, "app.js"), "utf8")
    .replace(/^import\s+\{\s*domToCanvas\s*\}\s+from\s+[^;]+;\s*/u, "");
  vm.runInContext(source, sandbox, { filename: "app.js" });
  return {
    sandbox,
    hooks: sandbox.__LASER_STATION_TEST_HOOKS__,
    fetchCalls,
    sockets,
    setFetch(impl) { fetchImpl = impl; },
  };
}

function jsonResponse(body, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    async text() { return JSON.stringify(body); },
  };
}

function binaryResponse(buffer, headers = {}, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: new Headers(headers),
    async text() { return ""; },
    async arrayBuffer() { return buffer; },
  };
}

function makeBinaryPcd({
  points = [[1, 2, 3], [4, 5, 6]],
  sourcePoints = points.length,
  sizes = "4 4 4",
  types = "F F F",
  counts = "1 1 1",
  extraBodyBytes = 0,
  coordinateSchema = "",
  xyzSha256 = "",
  finalBToASha256 = "",
} = {}) {
  const headerLines = [
    `# GOMOB_SOURCE_POINTS ${sourcePoints}`,
  ];
  if (coordinateSchema) headerLines.push(`# GOMOB_COORDINATE_SCHEMA ${coordinateSchema}`);
  if (xyzSha256) headerLines.push(`# GOMOB_XYZ_SHA256 ${xyzSha256}`);
  if (finalBToASha256) headerLines.push(`# GOMOB_FINAL_B_TO_A_SHA256 ${finalBToASha256}`);
  headerLines.push(
    "# .PCD v0.7 - Point Cloud Data file format",
    "VERSION 0.7",
    "FIELDS x y z",
    `SIZE ${sizes}`,
    `TYPE ${types}`,
    `COUNT ${counts}`,
    `WIDTH ${points.length}`,
    "HEIGHT 1",
    "VIEWPOINT 0 0 0 1 0 0 0",
    `POINTS ${points.length}`,
    "DATA binary",
    "",
  );
  const header = headerLines.join("\n");
  const body = Buffer.alloc(points.length * 12 + extraBodyBytes);
  points.forEach((point, index) => {
    body.writeFloatLE(point[0], index * 12);
    body.writeFloatLE(point[1], index * 12 + 4);
    body.writeFloatLE(point[2], index * 12 + 8);
  });
  const bytes = Buffer.concat([Buffer.from(header, "utf8"), body]);
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
}

function makeBinaryRgbPcd({
  points = [[1, 2, 3], [4, 5, 6]],
  colors = [[12, 34, 56], [78, 90, 123]],
  sourcePoints = points.length,
} = {}) {
  assert.equal(colors.length, points.length);
  const header = [
    `# GOMOB_SOURCE_POINTS ${sourcePoints}`,
    "# .PCD v0.7 - Point Cloud Data file format",
    "VERSION 0.7",
    "FIELDS x y z rgb",
    "SIZE 4 4 4 4",
    "TYPE F F F U",
    "COUNT 1 1 1 1",
    `WIDTH ${points.length}`,
    "HEIGHT 1",
    "VIEWPOINT 0 0 0 1 0 0 0",
    `POINTS ${points.length}`,
    "DATA binary",
    "",
  ].join("\n");
  const body = Buffer.alloc(points.length * 16);
  points.forEach((point, index) => {
    const offset = index * 16;
    body.writeFloatLE(point[0], offset);
    body.writeFloatLE(point[1], offset + 4);
    body.writeFloatLE(point[2], offset + 8);
    const [r, g, b] = colors[index];
    body.writeUInt32LE(((r & 255) << 16) | ((g & 255) << 8) | (b & 255), offset + 12);
  });
  const bytes = Buffer.concat([Buffer.from(header, "utf8"), body]);
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
}

function resetRuntime(env) {
  const { app, PointCloud, els } = env.hooks;
  app.endpoint = "http://old.example";
  app.token = "old-token";
  app.connectionSerial = 1;
  app.restoreSerial = 0;
  app.cloudPhaseSerial = 0;
  app.finalPhaseScanId = null;
  app.activeScanId = null;
  app.activeSessionKey = "";
  app.scanState = "idle";
  app.scanStartPending = false;
  app.pendingConfigWrites.clear();
  app.stationWriteTails.clear();
  app.stations = [{
    id: "station-a",
    name: "A",
    cameras: [
      { id: "cam-a", role: "a", ip: "192.168.9.101" },
      { id: "cam-b", role: "b", ip: "192.168.9.102" },
    ],
  }];
  app.activeStationId = "station-a";
  app.selectedCameraId = "cam-a";
  app.deviceSettingsCameraId = "";
  app.deviceRefreshSerial = 0;
  app.statusRequestSerial = 0;
  app.statusPollingKey = "";
  app.statusPollingPromise = null;
  app.deviceStatuses = { a: null, b: null };
  app.deviceInfos = { a: null, b: null };
  app.backgroundReadiness = {
    stationKey: "",
    state: "unknown",
    label: "检查中",
    detail: "正在读取服务端背景状态",
    revision: "",
  };
  app.authorityReadiness = {
    siteKey: "",
    regionKey: "",
    site: "unknown",
    region: "unknown",
    siteDetail: "",
    regionDetail: "",
  };
  app.authorityRequestSerial = { site: 0, region: 0 };
  app.backgroundRequestSerial = 0;
  app.stationCalibrationPending = false;
  app.calibrationOperationSerial = 0;
  app.framingGeneration = 0;
  app.framingSessionKey = "";
  app.framingRunning = false;
  app.framingStopping = false;
  app.framingStopRequestPending = false;
  app.framingControlsReady = false;
  app.framingAbortController = null;
  app.framingRequestSent = false;
  app.clouds = [new PointCloud(32), new PointCloud(32)];
  app.fusedCloud = new PointCloud(32);
  app.measure = null;
  app.overlay = null;
  app.finalCloudPayload = null;
  app.finalCloudsLoading = false;
  app.finalCloudsLoaded = false;
  app.finalCloudPlanKey = "";
  app.finalCloudLoadedNames = new Set();
  app.loadedCloudsScanId = null;
  app.scanStatusPolling = false;
  app.scanStatusRequestSerial = 0;
  app.ws = null;
  app.wsConnectionKey = "";
  app.wsConnectPromise = null;
  app.calibration.enabled = false;
  app.calibration.result = null;
  app.region.enabled = false;
  app.region.closed = false;
  app.region.clipEnabled = false;
  app.region.serverSet = false;
  app.region.points = [];
  app.regionEditSerial = 0;
  app.regionDirty = false;
  app.regionDiscardPending = false;
  els.endpoint.value = app.endpoint;
  els.token.value = app.token;
  els.pointBudget.value = "32";
  els.framingOverlay.hidden = true;
  env.fetchCalls.length = 0;
  env.sockets.length = 0;
}

function webParityRecord(app, payload) {
  const measure = app.measure || {};
  return {
    client: "web",
    effective: {
      site_revision: payload.site_revision,
      region_revision: payload.region_revision,
      background_revision: measure.background_revision_id,
    },
    result: {
      session_key: app.activeSessionKey,
      result_object_key: payload.result_object_key,
      unit_a_object_key: payload.unit_a_object_key,
      unit_b_object_key: payload.unit_b_object_key,
      measured_object_key: payload.measured_object_key,
      points: Number(payload.points),
      pts_a: Number(payload.pts_a),
      pts_b: Number(payload.pts_b),
      align_method: payload.align_method,
      site_revision: payload.site_revision,
      region_revision: payload.region_revision,
      measure_mode: measure.measure_mode || measure.meas_mode || "",
      measure_valid: measure.measure_valid === true,
      measure_reason: measure.measure_reason || "",
      background_captured: measure.background_captured === true,
      length_mm: Number(measure.length_mm),
      width_mm: Number(measure.width_mm),
      height_mm: Number(measure.height_mm),
      compliance_determined: measure.compliance_determined === true,
      compliance_reason: measure.compliance_reason || "",
      compliant: measure.compliant === true,
      violations: Array.from(measure.violations || []),
      background_set: measure.background_set === true,
      background_compatible: measure.background_compatible === true,
      background_incompatible: measure.background_incompatible === true,
      background_reason: measure.background_reason || "",
      background_revision_id: Number(measure.background_revision_id),
      background_schema: measure.background_schema || "",
      fg_points: Number(measure.fg_points || measure.foreground_points || 0),
      measured_points: Number(measure.measured_points || 0),
      measured_artifact: payload.measured_artifact,
      axle_valid: measure.axle_valid === true,
      num_axles: Number(measure.num_axles || 0),
      wheelbases_mm: Array.from(measure.wheelbases_mm || []),
      total_wheelbase_mm: Number(measure.total_wheelbase_mm || 0),
      front_overhang_mm: Number(measure.front_overhang_mm || 0),
      rear_overhang_mm: Number(measure.rear_overhang_mm || 0),
      has_cargo_box: measure.has_cargo_box === true,
      box_outer_length_mm: Number(measure.box_outer_length_mm || 0),
      box_outer_width_mm: Number(measure.box_outer_width_mm || 0),
      box_depth_mm: Number(measure.box_depth_mm || 0),
      box_inner_width_mm: Number(measure.box_inner_width_mm || 0),
      overlay: app.overlay,
      ground_nx: Number(measure.ground_nx),
      ground_ny: Number(measure.ground_ny),
      ground_nz: Number(measure.ground_nz),
      ground_d: Number(measure.ground_d),
      ground_valid: measure.ground_valid === true,
    },
  };
}

test("生产控制台静态 DOM 与业务脚本锚点完整一致", () => {
  const html = fs.readFileSync(path.join(__dirname, "index.html"), "utf8");
  const appSource = fs.readFileSync(path.join(__dirname, "app.js"), "utf8");
  const ids = [...html.matchAll(/\bid="([^"]+)"/g)].map((match) => match[1]);
  const refs = [...new Set([...appSource.matchAll(/\$\("([^"]+)"\)/g)].map((match) => match[1]))]
    .filter((id) => id !== "effectiveKeepRatioInput");
  const duplicates = ids.filter((id, index) => ids.indexOf(id) !== index);
  const missing = refs.filter((id) => !ids.includes(id));
  assert.deepEqual(duplicates, []);
  assert.deepEqual(missing, []);
  for (const id of [
    "cloudCanvas",
    "markerLayer",
    "stationSelect",
    "startScanBtn",
    "configDrawer",
    "readinessSummary",
    "readinessDeviceA",
    "readinessDeviceB",
    "readinessSite",
    "readinessRegion",
    "readinessBackground",
  ]) {
    assert.match(html, new RegExp(`\\bid="${id}"`));
  }
});

test("维护导航只映射现有真实抽屉面板且危险操作分层", () => {
  const html = fs.readFileSync(path.join(__dirname, "index.html"), "utf8");
  const panels = ["station", "scan", "device", "calib"];
  for (const panel of panels) {
    assert.match(html, new RegExp(`class="workspace-nav-button"[^>]+data-open-panel="${panel}"`));
    assert.match(html, new RegExp(`data-drawer-tab="${panel}"`));
    assert.match(html, new RegExp(`data-drawer-panel="${panel}"`));
  }
  const openedPanels = [...html.matchAll(/data-open-panel="([^"]+)"/g)].map((match) => match[1]);
  assert.equal(openedPanels.every((panel) => panels.includes(panel)), true);
  assert.match(html, /data-command="SOFT_REBOOT" class="danger"/);
  assert.match(html, /id="deleteStationBtn" class="danger-soft"/);
  assert.match(html, /id="startScanBtn" class="primary scan-action"/);
  const topbarActions = html.match(/<div class="topbar-actions">([\s\S]*?)<\/div>/)?.[1] || "";
  assert.match(topbarActions, /id="feedbackBtn"/);
  assert.match(topbarActions, /id="startScanBtn"/);
  assert.doesNotMatch(topbarActions, /captureBgBtn|data-open-panel="station"/);
  assert.match(html, /id="captureBgDrawerBtn"/);
  assert.doesNotMatch(html, /保存并用于融合|⚡|📷/u);
  assert.match(html, /id="keepRatioInput" type="text"[^>]+readonly/);
});

test("毛玻璃视觉具备语义 token、性能降级与窄屏布局", () => {
  const css = fs.readFileSync(path.join(__dirname, "styles.css"), "utf8");
  for (const token of [
    "--bg-0: #f6f7f9",
    "--accent: #0e8a75",
    "--font-ui:",
    "--glass-chrome:",
    "--glass-panel:",
    "--glass-modal:",
    "--canvas: #0b0e13",
  ]) {
    assert.equal(css.includes(token), true, `缺少视觉 token ${token}`);
  }
  assert.match(css, /backdrop-filter:\s*blur\(/);
  assert.match(css, /--font-ui:[^;]+"Microsoft YaHei UI"[^;]+"PingFang SC"[^;]+"Source Han Sans SC"/s);
  assert.match(css, /font-family:\s*var\(--font-ui\)/);
  assert.match(css, /\.config-drawer\s*\{[^}]*right:\s*auto;[^}]*left:\s*12px;[^}]*transform:\s*translateX\(calc\(-100% - 28px\)\)/s);
  assert.match(css, /@media \(max-width: 760px\)[\s\S]*?\.config-drawer\s*\{[^}]*left:\s*8px;/);
  assert.doesNotMatch(css, /\.config-drawer\s*\{[^}]*transform:\s*translateX\(calc\(100%/s);
  assert.match(css, /@supports not \(\(-webkit-backdrop-filter:/);
  assert.match(css, /@media \(max-width: 760px\)/);
  assert.match(css, /grid-template-rows:\s*minmax\(0, 1fr\) 58px/);
  assert.match(css, /@media \(prefers-reduced-motion: reduce\)/);
  assert.doesNotMatch(css, /\.topbar-actions \.icon-action:first-of-type/);
});

test("生产控制台具备移动缩放、模态语义与缓存代际", () => {
  const html = fs.readFileSync(path.join(__dirname, "index.html"), "utf8");
  const css = fs.readFileSync(path.join(__dirname, "styles.css"), "utf8");
  const appSource = fs.readFileSync(path.join(__dirname, "app.js"), "utf8");

  assert.match(html, /id="framingOverlay"[^>]+role="dialog"[^>]+aria-modal="true"/);
  assert.match(html, /id="scanBanner"[^>]+aria-live="off"/);
  assert.match(html, /id="scanBannerAnnouncement"[^>]+role="status"[^>]+aria-live="polite"/);
  assert.match(html, /id="scanBannerDetail"[^>]+aria-hidden="true"/);
  assert.match(html, /id="toast"[^>]+role="status"[^>]+aria-live="polite"/);
  assert.match(html, /data-cloud-mode="split"[^>]+aria-pressed="true"/);
  assert.match(html, /styles\.css\?v=20260715-glass-console7/);
  assert.match(html, /app\.js\?v=20260715-glass-console7/);
  assert.match(appSource, /cloudTouchPointers/);
  assert.match(appSource, /cloudPinch\.distance \* cloudPinch\.span \/ span/);
  assert.match(appSource, /activatePageModal\(els\.framingOverlay\)/);
  assert.match(appSource, /mask\.setAttribute\("aria-modal", "true"\)/);
  assert.match(appSource, /q\.set\("session_key", context\.sessionKey\)/);
  assert.match(appSource, /payload\.session_key === app\.framingSessionKey/);
  assert.match(css, /#cloudCanvas\s*\{[^}]*touch-action:\s*none/s);
  assert.match(css, /\.feedback-mask\s*\{\s*background:\s*rgb\(11 15 22 \/ 88%\)/s);
  assert.match(css, /@media \(prefers-reduced-motion: reduce\)[\s\S]*?\.scan-spinner\s*\{\s*animation:\s*none/);
});

test("旧工位重复 A/B 角色会归一化，运行时重复角色会阻塞起扫", () => {
  const isolated = loadApp();
  const { app, normalizeStationCameraRoles, stationTopologyIssue, scanSetupBlocker } = isolated.hooks;
  const stations = [{
    id: "duplicate-role",
    cameras: [
      { id: "a-1", role: "a", ip: "192.168.9.101" },
      { id: "a-2", role: "a", ip: "192.168.9.111" },
      { id: "b-1", role: "b", ip: "192.168.9.102" },
    ],
  }];
  normalizeStationCameraRoles(stations);
  assert.deepEqual(stations[0].cameras.map((cam) => cam.role), ["a", "aux", "b"]);

  app.stations = stations;
  app.activeStationId = "duplicate-role";
  stations[0].cameras[1].role = "a";
  assert.match(stationTopologyIssue(), /角色存在重复/);
  assert.match(scanSetupBlocker(), /角色存在重复/);
});

test("问题反馈截图会临时遮罩管理令牌并完整恢复", () => {
  const isolated = loadApp();
  isolated.hooks.els.token.value = "admin.jwt.secret";
  const restore = isolated.hooks.maskSensitiveFeedbackFields();
  assert.equal(isolated.hooks.els.token.value, "••••••••••••");
  restore();
  assert.equal(isolated.hooks.els.token.value, "admin.jwt.secret");
});

test("工位就绪链区分设备告警、联调外参与服务端几何状态", () => {
  const isolated = loadApp();
  const {
    app,
    deviceReadiness,
    siteReadiness,
    regionReadiness,
    backgroundReadiness,
    updateBackgroundReadiness,
    updateAuthorityReadiness,
    scanSetupBlocker,
  } = isolated.hooks;
  app.stations = [{
    id: "station-ready",
    cameras: [
      { id: "a", name: "A", ip: "192.168.9.101", role: "a" },
      { id: "b", name: "B", ip: "192.168.9.102", role: "b" },
    ],
  }];
  app.activeStationId = "station-ready";
  app.deviceStatuses.a = { online: true, state: "READY", errorCode: 32 };
  app.deviceStatuses.b = { online: true, state: "READY", errorCode: 0 };
  app.calibration.result = {
    matrix: [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1],
    serverPersisted: true,
    siteQualityOverride: true,
    siteQualityVerified: false,
    scanEligible: true,
    revision: "site-revision",
  };
  app.region.points = [[0, 0, 0], [1, 0, 0], [0, 1, 0]];
  app.region.serverSet = true;
  app.region.clipEnabled = true;
  updateAuthorityReadiness("site", "verified", "服务端工位外参已确认");
  updateAuthorityReadiness("region", "verified", "服务端扫描区域已确认");
  updateBackgroundReadiness("ready", "可用", "revision 2", "2");

  assert.equal(deviceReadiness("a").state, "warning");
  assert.equal(deviceReadiness("a").label, "存在告警");
  assert.equal(deviceReadiness("b").state, "ready");
  assert.equal(siteReadiness().state, "warning");
  assert.equal(siteReadiness().label, "联调放行");
  assert.equal(regionReadiness().state, "ready");
  assert.equal(backgroundReadiness().state, "ready");
  assert.equal(scanSetupBlocker(), "");

  app.deviceStatuses.a = { online: true, state: "SCAN", errorCode: 32 };
  assert.equal(deviceReadiness("a").state, "busy");
  assert.match(scanSetupBlocker(), /设备 A 尚未就绪/);
  app.deviceStatuses.a = { online: true, state: "BOOTING", errorCode: 0 };
  assert.equal(deviceReadiness("a").state, "unknown");
  app.deviceStatuses.a = { online: true, state: "READY", errorCode: 32 };
  assert.equal(scanSetupBlocker(), "");

  app.region.serverSet = false;
  assert.equal(scanSetupBlocker(), "当前工位尚未保存扫描区域");
  assert.equal(scanSetupBlocker({ background: true }), "当前工位尚未保存扫描区域");

  app.region.serverSet = true;
  app.region.clipEnabled = true;
  updateAuthorityReadiness("site", "error", "工位外参读取失败：数据库不可用");
  app.calibration.result = null;
  assert.equal(scanSetupBlocker(), "工位外参读取失败：数据库不可用");
  assert.doesNotMatch(scanSetupBlocker(), /尚未保存 A\/B 外参/);

  updateAuthorityReadiness("site", "verified", "服务端工位外参已确认");
  app.calibration.result = {
    matrix: [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1],
    serverPersisted: true,
    siteQualityOverride: true,
    scanEligible: true,
  };
  updateAuthorityReadiness("region", "error", "扫描区域读取失败：网络中断");
  app.region.serverSet = false;
  assert.equal(scanSetupBlocker(), "扫描区域读取失败：网络中断");
  assert.doesNotMatch(scanSetupBlocker(), /尚未保存扫描区域/);
});

const env = loadApp();

test.beforeEach(() => resetRuntime(env));

test("连接与工位代际会使旧响应失效", () => {
  const { app, scanContextSnapshot, isScanContextCurrent, applyConnection } = env.hooks;
  const oldContext = scanContextSnapshot();
  assert.equal(isScanContextCurrent(oldContext), true);

  applyConnection("http://new.example", "new-token", { invalidate: true });
  assert.equal(isScanContextCurrent(oldContext), false);
  assert.equal(oldContext.connection.base, "http://old.example");
  assert.equal(oldContext.connection.token, "old-token");

  const stationContext = scanContextSnapshot();
  app.activeStationId = "station-b";
  assert.equal(isScanContextCurrent(stationContext), false);
});

test("区域权威慢响应不能覆盖请求期间产生的新描点", async () => {
  const { app, syncStationRegionCalibration } = env.hooks;
  let resolveRegion;
  env.setFetch(async (url) => {
    assert.match(url, /region-calibration/);
    return new Promise((resolve) => { resolveRegion = resolve; });
  });

  const syncing = syncStationRegionCalibration();
  app.regionEditSerial++;
  app.region.enabled = true;
  app.region.points = [[9, 9, 0]];
  app.region.closed = false;
  app.region.clipEnabled = false;
  resolveRegion(jsonResponse({
    set: true,
    enabled: true,
    points: [[0, 0, 0], [10, 0, 0], [0, 10, 0]],
    source: "server",
  }));
  await syncing;

  assert.equal(app.region.enabled, true);
  assert.deepEqual(Array.from(app.region.points[0]), [9, 9, 0]);
  assert.equal(app.authorityReadiness.region, "verified");
  assert.match(app.authorityReadiness.regionDetail, /保留本地编辑草稿/);
});

test("已有未保存区域草稿时权威 GET 不覆盖且起扫保持阻塞", async () => {
  const {
    app,
    syncStationRegionCalibration,
    updateAuthorityReadiness,
    updateBackgroundReadiness,
    scanSetupBlocker,
  } = env.hooks;
  app.deviceStatuses.a = { ip: "192.168.9.101", online: true, state: "READY", errorCode: 0 };
  app.deviceStatuses.b = { ip: "192.168.9.102", online: true, state: "READY", errorCode: 0 };
  app.calibration.result = {
    matrix: [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1],
    serverPersisted: true,
    siteQualityOverride: true,
    scanEligible: true,
  };
  app.region.enabled = false;
  app.region.points = [[9, 9, 0], [19, 9, 0]];
  app.region.closed = false;
  app.region.clipEnabled = false;
  app.region.serverSet = false;
  app.regionDirty = true;
  updateAuthorityReadiness("site", "verified", "服务端工位外参已确认");
  updateBackgroundReadiness("ready", "可用", "背景已确认", "bg-1");
  env.setFetch(async (url) => {
    assert.match(url, /region-calibration/);
    return jsonResponse({
      set: true,
      enabled: true,
      points: [[0, 0, 0], [10, 0, 0], [0, 10, 0]],
      source: "server",
    });
  });

  await syncStationRegionCalibration();

  assert.deepEqual(app.region.points.map((point) => Array.from(point)), [[9, 9, 0], [19, 9, 0]]);
  assert.equal(app.regionDirty, true);
  assert.equal(app.authorityReadiness.region, "verified");
  assert.equal(scanSetupBlocker(), "当前区域草稿尚未写入服务端");
});

test("放弃区域草稿只回读服务端，绝不会把本地草稿迁移写入", async () => {
  const { app, syncStationRegionCalibration } = env.hooks;
  app.region.points = [[0, 0, 0], [10, 0, 0], [0, 10, 0]];
  app.region.closed = true;
  app.region.clipEnabled = true;
  app.region.serverSet = false;
  app.regionDirty = true;
  env.setFetch(async (url, options = {}) => {
    assert.match(url, /region-calibration/);
    assert.equal(options.method, undefined);
    return jsonResponse({ set: false });
  });

  await syncStationRegionCalibration({ discardDraft: true });

  assert.equal(env.fetchCalls.length, 1);
  assert.equal(app.region.points.length, 0);
  assert.equal(app.regionDirty, false);
  assert.equal(app.stations[0].regionCalibrationMigrationDone, true);
});

test("活动扫描的区域快照不能覆盖当前区域编辑器草稿", () => {
  const { app, applyActiveScanState } = env.hooks;
  app.region.points = [[7, 8, 0], [9, 10, 0]];
  app.region.closed = false;
  app.regionDirty = true;

  applyActiveScanState({
    scan_id: 81,
    session_key: "active-region-snapshot",
    status: "capturing",
    region_filter: {
      enabled: true,
      points: [[0, 0, 0], [10, 0, 0], [0, 10, 0]],
    },
  });

  assert.deepEqual(app.region.points.map((point) => Array.from(point)), [[7, 8, 0], [9, 10, 0]]);
  assert.equal(app.regionDirty, true);
});

test("取景扫程空值在置 busy 与发请求之前即被拒绝", async () => {
  const { app, els, runFraming } = env.hooks;
  els.framingOverlay.hidden = false;
  app.framingControlsReady = true;
  els.framingAStart.value = "";
  els.framingAStop.value = "";
  els.framingBStart.value = "-170";
  els.framingBStop.value = "-10";
  els.framingSpeed.value = "1";
  els.framingMarkerLen.value = "150";

  await runFraming();

  assert.equal(env.fetchCalls.length, 0);
  assert.equal(app.framingRunning, false);
  assert.equal(app.stationCalibrationPending, false);
});

test("取景 POST 尚未发出时停止会使旧 run 失效且绝不补发起扫", async () => {
  const { app, els, runFraming, stopFraming } = env.hooks;
  els.framingOverlay.hidden = false;
  app.framingControlsReady = true;
  els.framingAStart.value = "0";
  els.framingAStop.value = "90";
  els.framingBStart.value = "-170";
  els.framingBStop.value = "-10";
  els.framingSpeed.value = "1";
  els.framingMarkerLen.value = "150";

  const running = runFraming();
  assert.equal(env.sockets.length, 1);
  await stopFraming();
  env.sockets[0].open();
  await running;

  assert.equal(env.fetchCalls.some(([url]) => String(url).includes("site-framing")), false);
  assert.equal(app.framingRunning, false);
  assert.equal(app.stationCalibrationPending, false);
});

test("服务端仍在清理取景会话时保持锁定，不重新开放 Run", async () => {
  const { app, els, stopFraming } = env.hooks;
  els.framingOverlay.hidden = false;
  app.framingControlsReady = true;
  app.framingRunning = true;
  app.stationCalibrationPending = true;
  app.framingRequestSent = true;
  app.framingSessionKey = "site-framing-stopping";
  env.setFetch(async () => jsonResponse({
    ok: true,
    active: true,
    stopping: true,
    cancel_won: true,
    commit_started: false,
  }, 202));

  await stopFraming();

  assert.equal(app.framingRunning, true);
  assert.equal(app.stationCalibrationPending, true);
  assert.equal(els.runFraming.hidden, true);
  assert.equal(els.framingStop.hidden, false);
});

test("只有会话取消、未提交且 A/B READY 全部确认后才解锁取景", async () => {
  const { app, els, stopFraming } = env.hooks;
  els.framingOverlay.hidden = false;
  app.framingControlsReady = true;
  app.framingRunning = true;
  app.stationCalibrationPending = true;
  app.framingRequestSent = true;
  app.framingSessionKey = "site-framing-stopped";
  env.setFetch(async () => jsonResponse({
    ok: true,
    active: false,
    cancel_won: true,
    server_persisted: false,
    devices_ready: true,
  }));

  await stopFraming();

  assert.equal(app.framingRunning, false);
  assert.equal(app.stationCalibrationPending, false);
  assert.equal(app.framingRequestSent, false);
  assert.equal(els.runFraming.hidden, false);
  assert.equal(els.framingStop.hidden, true);
});

test("旧工位设备轮询悬挂不阻塞新连接刷新，也不能回写旧状态", async () => {
  const { app, applyConnection, refreshStationDeviceStatuses } = env.hooks;
  const oldRejects = [];
  env.setFetch(async (url) => {
    if (url.startsWith("http://old.example")) {
      return new Promise((_, reject) => oldRejects.push(reject));
    }
    return jsonResponse({ online: true, state: "READY", error_code: 0, ip: new URL(url).searchParams.get("ip") });
  });

  const oldPoll = refreshStationDeviceStatuses({ silent: true });
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(env.fetchCalls.length, 2);

  applyConnection("http://new.example", "new-token", { invalidate: true });
  await refreshStationDeviceStatuses({ silent: true });
  assert.equal(env.fetchCalls.length, 4);
  assert.equal(app.deviceStatuses.a.ip, "192.168.9.101");
  assert.equal(app.deviceStatuses.b.ip, "192.168.9.102");

  oldRejects.forEach((reject) => reject(new Error("旧连接已失效")));
  await oldPoll;
  assert.equal(app.deviceStatuses.a.ip, "192.168.9.101");
  assert.equal(app.deviceStatuses.b.ip, "192.168.9.102");
});

test("旧连接起扫补偿停止始终打回原 endpoint 和 token", async () => {
  const { scanContextSnapshot, applyConnection, stopStaleStartIfActive } = env.hooks;
  const oldContext = scanContextSnapshot();
  applyConnection("http://new.example", "new-token", { invalidate: true });
  let call = 0;
  env.setFetch(async (_url, options = {}) => {
    call++;
    if (call === 1) return jsonResponse({ active: true, scan_id: 77 });
    assert.equal(options.method, "POST");
    return jsonResponse({ status: "cancelled" });
  });

  assert.equal(await stopStaleStartIfActive(oldContext), true);
  assert.equal(env.fetchCalls.length, 2);
  assert.equal(env.fetchCalls[0][0], "http://old.example/v1/scans/laser/active?unit_a_ip=192.168.9.101&unit_b_ip=192.168.9.102");
  assert.equal(env.fetchCalls[1][0], "http://old.example/v1/scans/laser/77/stop");
  assert.equal(env.fetchCalls[0][1].headers.get("Authorization"), "Bearer old-token");
});

test("并发 WS 连接复用同一 Promise，连接替换不会永久挂起", async () => {
  const { connectionSnapshot, connectWs, applyConnection } = env.hooks;
  const oldConnection = connectionSnapshot();
  const first = connectWs(oldConnection);
  const second = connectWs(oldConnection);
  assert.equal(first, second);
  assert.equal(env.sockets.length, 1);
  env.sockets[0].open();
  await Promise.all([first, second]);

  const pendingOld = connectWs(oldConnection);
  // 已经 OPEN 时会立即完成；先模拟断开再建立真正 pending 的旧连接。
  env.hooks.app.ws.readyState = 3;
  const replacing = connectWs(oldConnection);
  const rejected = assert.rejects(replacing, /替代|失效|断开/);
  applyConnection("http://new.example", "new-token", { invalidate: true });
  await rejected;
  await pendingOld;

  const current = env.hooks.connectionSnapshot();
  const next = connectWs(current);
  env.sockets.at(-1).open();
  await next;
});

test("active 查询失败时绝不误查 latest", async () => {
  const { restoreActiveScan } = env.hooks;
  env.setFetch(async () => { throw new Error("network down"); });
  assert.equal(await restoreActiveScan({ clearInactive: true, loadLatestWhenInactive: true, silent: true }), "error");
  assert.equal(env.fetchCalls.length, 1);
  assert.match(env.fetchCalls[0][0], /\/active\?/);
});

test("starting_unknown 与点云加载期间拒绝二次起扫", async () => {
  const { app, startScan, scanActionMode } = env.hooks;
  app.scanState = "starting_unknown";
  assert.equal(scanActionMode(), "busy");
  await startScan({ markAsBackground: true });
  assert.equal(env.fetchCalls.length, 0);
  assert.equal(app.scanState, "starting_unknown");

  app.scanState = "loading_clouds";
  await startScan();
  assert.equal(env.fetchCalls.length, 0);
  assert.equal(app.scanState, "loading_clouds");
});

test("起扫前置检查原子加锁，双击不会发出第二组请求", async () => {
  const { app, startScan, scanActionMode } = env.hooks;
  const pendingRejects = [];
  env.setFetch(async () => new Promise((_, reject) => pendingRejects.push(reject)));

  const first = startScan();
  assert.equal(app.scanStartPending, true);
  assert.equal(scanActionMode(), "busy");
  await startScan();
  await new Promise((resolve) => setTimeout(resolve, 0));

  assert.equal(env.fetchCalls.length, 2);
  pendingRejects.forEach((reject) => reject(new Error("测试中断前置读取")));
  await first;
  assert.equal(app.scanStartPending, false);
  assert.equal(env.fetchCalls.length, 2);
});

test("设备参数慢响应不能跨设备覆盖当前表单", async () => {
  const { app, els, refreshDevice } = env.hooks;
  const resolvers = new Map();
  env.setFetch(async (url) => new Promise((resolve) => resolvers.set(url, resolve)));

  app.selectedCameraId = "cam-a";
  const refreshA = refreshDevice({ silent: true });
  app.selectedCameraId = "cam-b";
  const refreshB = refreshDevice({ silent: true });

  const statusB = [...resolvers.entries()].find(([url]) => url.includes("device-status") && url.includes("192.168.9.102"));
  const infoB = [...resolvers.entries()].find(([url]) => url.includes("device-info") && url.includes("192.168.9.102"));
  assert.ok(statusB && infoB);
  statusB[1](jsonResponse({ online: true, state: "READY", error_code: 0 }));
  infoB[1](jsonResponse({ control: { scan_speed: 22, scan_start_angle: 5, scan_angle: 40 } }));
  await refreshB;

  assert.equal(app.deviceSettingsCameraId, "cam-b");
  assert.equal(els.scanSpeed.value, 22);

  const statusA = [...resolvers.entries()].find(([url]) => url.includes("device-status") && url.includes("192.168.9.101"));
  const infoA = [...resolvers.entries()].find(([url]) => url.includes("device-info") && url.includes("192.168.9.101"));
  assert.ok(statusA && infoA);
  statusA[1](jsonResponse({ online: true, state: "READY", error_code: 0 }));
  infoA[1](jsonResponse({ control: { scan_speed: 99, scan_start_angle: 1, scan_angle: 20 } }));
  await refreshA;

  assert.equal(app.deviceSettingsCameraId, "cam-b");
  assert.equal(els.scanSpeed.value, 22);
  assert.equal(app.deviceStatuses.a, null);
});

test("POST/active 的 403 是确定性归属拒绝，不得永久锁在 unknown", () => {
  const { isDefinitiveStartOwnershipRejection } = env.hooks;
  assert.equal(isDefinitiveStartOwnershipRejection({ status: 403 }, new Error("network")), true);
  assert.equal(isDefinitiveStartOwnershipRejection({ status: 409 }, { status: 403 }), true);
  assert.equal(isDefinitiveStartOwnershipRejection(new Error("network"), new Error("network")), false);
});

test("工位外参质量错误不得误报成未保存外参", () => {
  const { scanConfigurationErrorMessage } = env.hooks;
  assert.equal(
    scanConfigurationErrorMessage(
      "工位外参质量未达生产要求: 缺少 rms_error_mm/common_markers 质量证据；请重新执行 ArUco 工位标定",
    ),
    "工位外参仍在，但当前未启用受控联调放行；请联系管理员核对当前 revision，生产使用再补齐标定证据",
  );
  assert.equal(
    scanConfigurationErrorMessage(
      "工位外参质量未达生产要求: RMS 6.20mm 超过生产上限 5.00mm；请重新执行 ArUco 工位标定",
    ),
    "工位外参已存在，但生产质量证据未满足要求，请重新执行 ArUco 标定",
  );
  assert.equal(
    scanConfigurationErrorMessage(
      "工位外参质量未达生产要求: 公共标记 3 个，少于生产下限 4 个；请重新执行 ArUco 工位标定",
    ),
    "工位外参已存在，但生产质量证据未满足要求，请重新执行 ArUco 标定",
  );
});

test("旧 worker 未返回质量契约时不得宣称生产验证或外参丢失", () => {
  const { calibrationResultFromServer, calibrationResultText } = env.hooks;
  const result = calibrationResultFromServer({
    set: true,
    site_json: { b_to_a: [1, 0, 0, 0.1, 0, 1, 0, 0.2, 0, 0, 1, 0.3, 0, 0, 0, 1] },
    source: "legacy_browser",
    rms_error_mm: null,
    common_markers: null,
  });
  assert.equal(result.siteQualityState, "unknown");
  assert.equal(result.scanEligible, null);
  assert.equal(result.productionEligible, false);
  const text = calibrationResultText(result);
  assert.match(text, /质量状态待服务端起扫接口确认/);
  assert.doesNotMatch(text, /生产质量已验证|外参丢失|重新.*标定/);
});

test("精确 revision 联调放行应显示外参可用且不提示重新标定", () => {
  const { app, calibrationResultFromServer, calibrationResultText, scanConfigurationErrorMessage } = env.hooks;
  const result = calibrationResultFromServer({
    site_json: { b_to_a: [1, 0, 0, 0.1, 0, 1, 0, 0.2, 0, 0, 1, 0.3, 0, 0, 0, 1] },
    source: "legacy_browser",
    rms_error_mm: null,
    common_markers: null,
    site_quality_state: "override",
    site_quality_verified: false,
    site_quality_override: true,
    site_quality_override_reason: "legacy_missing_evidence",
    scan_eligible: true,
    production_eligible: false,
  });
  assert.equal(result.serverPersisted, true);
  assert.equal(result.siteQualityOverride, true);
  assert.equal(result.scanEligible, true);
  assert.equal(result.productionEligible, false);
  const text = calibrationResultText(result);
  assert.match(text, /当前 revision 已受控放行/);
  assert.doesNotMatch(text, /重新.*标定/);

  app.calibration.result = result;
  const errorText = scanConfigurationErrorMessage(
    "工位外参质量未达生产要求: 缺少 rms_error_mm/common_markers 质量证据；请重新执行 ArUco 工位标定",
  );
  assert.match(errorText, /联调放行未生效/);
  assert.match(errorText, /无需重新标定/);
});

test("服务端暂未返回外参时保留浏览器历史副本", async () => {
  const { app, syncStationCalibration } = env.hooks;
  const matrix = [1, 0, 0, 100, 0, 1, 0, 200, 0, 0, 1, 300, 0, 0, 0, 1];
  app.calibration.result = {
    matrix,
    source: "aruco",
    serverPersisted: true,
    siteQualityVerified: true,
    scanEligible: true,
    productionEligible: true,
  };
  env.setFetch(async () => jsonResponse({
    set: false,
    unit_a_ip: "192.168.9.101",
    unit_b_ip: "192.168.9.102",
  }));

  await syncStationCalibration();

  assert.deepEqual(Array.from(app.calibration.result.matrix), matrix);
  assert.equal(app.calibration.result.serverPersisted, false);
  assert.equal(app.calibration.result.siteQualityState, "local_backup");
  assert.equal(app.calibration.result.scanEligible, false);
  assert.equal(app.calibration.result.productionEligible, false);
});

test("只有明确未找到工位外参时才提示尚未保存", () => {
  const { scanConfigurationErrorMessage } = env.hooks;
  assert.equal(
    scanConfigurationErrorMessage("当前双单元工位尚未保存外参，请先在 3D 工位管理台完成标定"),
    "当前双单元工位尚未保存外参，请先在管理台完成 A/B 标定",
  );
  assert.equal(
    scanConfigurationErrorMessage("site calibration not found"),
    "当前双单元工位尚未保存外参，请先在管理台完成 A/B 标定",
  );
  assert.equal(
    scanConfigurationErrorMessage("读取工位外参失败: database unavailable"),
    "读取工位外参失败: database unavailable",
  );
  assert.equal(
    scanConfigurationErrorMessage("服务端工位外参损坏: rotation invalid"),
    "服务端工位外参损坏: rotation invalid",
  );
});

test("背景不兼容原因区分区域变化与旧版融合背景", () => {
  const { backgroundCompatibilityReasonText } = env.hooks;
  assert.equal(
    backgroundCompatibilityReasonText("region_calibration_changed"),
    "扫描区域版本已变化，请重新采集背景",
  );
  assert.equal(
    backgroundCompatibilityReasonText("legacy_fused_requires_recapture"),
    "旧版融合背景不再兼容，请重新采集空工位背景",
  );
  assert.equal(
    backgroundCompatibilityReasonText("legacy_fused_unverified"),
    "旧背景仍在，但尚未完成兼容验证，请联系管理员",
  );
  assert.equal(
    backgroundCompatibilityReasonText("legacy_fused_checksum_mismatch"),
    "旧背景完整性校验失败，请联系管理员恢复",
  );
});

test("明确 inactive 后才允许查询 latest", async () => {
  const { restoreActiveScan } = env.hooks;
  let call = 0;
  env.setFetch(async () => {
    call++;
    return call === 1 ? jsonResponse({ active: false }) : jsonResponse({ found: false });
  });
  assert.equal(await restoreActiveScan({ clearInactive: true, loadLatestWhenInactive: true, silent: true }), "inactive");
  assert.equal(env.fetchCalls.length, 2);
  assert.match(env.fetchCalls[0][0], /\/active\?/);
  assert.match(env.fetchCalls[1][0], /\/latest\?/);
});

test("新 session 会清除旧任务的点云和外廓", async () => {
  const { app, refreshActiveScanStatus } = env.hooks;
  app.activeScanId = 10;
  app.activeSessionKey = "old-session";
  app.scanState = "done";
  app.measure = { length_mm: 9999 };
  app.overlay = { valid: true };
  app.clouds[0].append([1, 2, 3]);
  env.setFetch(async () => jsonResponse({
    active: true,
    scan_id: 11,
    session_key: "new-session",
    status: "failed",
  }));

  assert.equal(await refreshActiveScanStatus({ silent: true }), "active");
  assert.equal(app.activeScanId, 11);
  assert.equal(app.activeSessionKey, "new-session");
  assert.equal(app.measure, null);
  assert.equal(app.overlay, null);
  assert.equal(app.clouds[0].count, 0);
});

test("迟到 active 快照不能覆盖已进入 final phase 的终态", async () => {
  const { app, refreshActiveScanStatus } = env.hooks;
  app.activeScanId = 21;
  app.activeSessionKey = "session-21";
  app.scanState = "done";
  app.finalPhaseScanId = 21;
  app.measure = { length_mm: 1768 };
  env.setFetch(async () => jsonResponse({
    active: true,
    scan_id: 21,
    session_key: "session-21",
    status: "capturing",
  }));

  await refreshActiveScanStatus({ silent: true });
  assert.equal(app.scanState, "done");
  assert.equal(app.measure.length_mm, 1768);
});

test("WS 必须匹配当前非空 session，final 后拒绝迟到点", () => {
  const { app, handleRealtime } = env.hooks;
  app.activeScanId = 31;
  app.activeSessionKey = "session-31";
  handleRealtime({ type: "laser.points", payload: { session_key: "old", unit: 0, points: [1, 2, 3] } });
  assert.equal(app.clouds[0].count, 0);
  handleRealtime({ type: "laser.points", payload: { session_key: "session-31", unit: 0, points: [1, 2, 3] } });
  assert.equal(app.clouds[0].count, 1);
  app.finalPhaseScanId = 31;
  handleRealtime({ type: "laser.points", payload: { session_key: "session-31", unit: 0, points: [4, 5, 6] } });
  assert.equal(app.clouds[0].count, 1);
});

test("stop 与 done 竞态只在 cancelled 时清理", () => {
  const { stopResponseAction } = env.hooks;
  assert.equal(JSON.stringify(stopResponseAction({ status: "cancelled" })), JSON.stringify({ action: "clear", status: "cancelled" }));
  assert.equal(JSON.stringify(stopResponseAction({ status: "done" })), JSON.stringify({ action: "restore", status: "done" }));
  assert.equal(JSON.stringify(stopResponseAction({ status: "fusing" })), JSON.stringify({ action: "refresh", status: "fusing" }));
});

test("PCD 解析严格拒绝截断正文和非法字段元数据", async () => {
  const { parsePcdAsync } = env.hooks;
  const valid = makeBinaryPcd({ sourcePoints: 9 });
  const parsed = await parsePcdAsync(valid, 32);
  assert.equal(parsed.sourcePoints, 9);
  assert.equal(parsed.renderPoints, 2);
  assert.deepEqual(Array.from(parsed.points), [1, 2, 3, 4, 5, 6]);

  await assert.rejects(
    parsePcdAsync(valid.slice(0, valid.byteLength - 1), 32),
    /正文长度.*不一致/,
  );
  await assert.rejects(
    parsePcdAsync(makeBinaryPcd({ sizes: "0 4 4" }), 32),
    /SIZE 必须为正整数/,
  );
});

test("点云下载交叉校验 X-Gomob 点数响应头", async () => {
  const { app, cloudContextSnapshot, downloadCloudFromPath } = env.hooks;
  const pcd = makeBinaryPcd({ sourcePoints: 9 });
  app.fusedCloud.reset(2);
  env.setFetch(async () => binaryResponse(pcd, {
    "X-Gomob-Source-Points": "9",
    "X-Gomob-Render-Points": "2",
  }));
  assert.equal(await downloadCloudFromPath("/cloud", app.fusedCloud, cloudContextSnapshot()), true);
  assert.equal(app.fusedCloud.count, 2);

  env.setFetch(async () => binaryResponse(pcd, {
    "X-Gomob-Source-Points": "9",
    "X-Gomob-Render-Points": "1",
  }));
  await assert.rejects(
    downloadCloudFromPath("/cloud", app.fusedCloud, cloudContextSnapshot()),
    /返回点数 1 与预算派生值 2 不一致/,
  );

  env.setFetch(async () => binaryResponse(pcd, {
    "X-Gomob-Source-Points": "9",
    "X-Gomob-Render-Points": "2",
  }));
  assert.equal(
    await downloadCloudFromPath(
      "/v1/scans/laser/active/cloud/unit_a?max_points=32",
      app.clouds[0],
      cloudContextSnapshot(),
    ),
    true,
  );
});

test("measured 下载把 WS/REST manifest 与响应头和 PCD 注释逐项核对", async () => {
  const { app, cloudContextSnapshot, downloadCloudFromPath, finalCloudPlan } = env.hooks;
  const payload = {
    measure_valid: true,
    measured_object_key: "laser-scans/session-207/measured.pcd",
    measured_points: 2,
    site_revision: "site-207",
    region_revision: "region-207",
    background_revision_id: 301,
    measured_artifact: {
      xyz_sha256: MEASURED_XYZ_SHA256,
      coordinate_schema: "unit_a_world_mm_v1",
      source_points: 2,
      site_revision: "site-207",
      region_revision: "region-207",
      background_revision: 301,
      final_b_to_a_sha256: MEASURED_FINAL_B_TO_A_SHA256,
    },
  };
  const plan = finalCloudPlan(payload, false);
  assert.equal(plan.contractError, "");
  const pcd = makeBinaryPcd({
    coordinateSchema: "unit_a_world_mm_v1",
    xyzSha256: MEASURED_XYZ_SHA256,
    finalBToASha256: MEASURED_FINAL_B_TO_A_SHA256,
  });
  const headers = {
    "X-Gomob-Source-Points": "2",
    "X-Gomob-Render-Points": "2",
    "X-Gomob-Coordinate-Schema": "unit_a_world_mm_v1",
    "X-Gomob-XYZ-SHA256": MEASURED_XYZ_SHA256,
    "X-Gomob-Final-B-To-A-SHA256": MEASURED_FINAL_B_TO_A_SHA256,
  };
  env.setFetch(async () => binaryResponse(pcd, headers));
  assert.equal(
    await downloadCloudFromPath("/measured", app.fusedCloud, cloudContextSnapshot(), plan.measuredArtifact),
    true,
  );

  env.setFetch(async () => binaryResponse(pcd, {
    ...headers,
    "X-Gomob-XYZ-SHA256": "c".repeat(64),
  }));
  await assert.rejects(
    downloadCloudFromPath("/measured", app.fusedCloud, cloudContextSnapshot(), plan.measuredArtifact),
    /响应头与任务 measured_artifact 不一致/,
  );
});

test("新区域不得原地裁掉已完成任务的 canonical 点云", () => {
  const { app, applyRegionClipToLoadedClouds, hasCanonicalFinalCloud } = env.hooks;
  app.region.closed = true;
  app.region.clipEnabled = true;
  app.region.points = [[-10, -10, 0], [10, -10, 0], [10, 10, 0], [-10, 10, 0]];
  app.clouds[0].append([0, 0, 0, 100, 100, 0]);
  app.clouds[1].append([0, 0, 0, 100, 100, 0]);
  app.fusedCloud.append([0, 0, 0, 100, 100, 0]);
  const fusedBefore = Array.from(app.fusedCloud.data.slice(0, app.fusedCloud.count * 3));
  app.activeScanId = 207;
  app.finalPhaseScanId = 207;
  app.loadedCloudsScanId = 207;
  app.scanState = "done";

  assert.equal(hasCanonicalFinalCloud(), true);
  assert.equal(applyRegionClipToLoadedClouds(), false);
  assert.equal(app.clouds[0].count, 2);
  assert.equal(app.clouds[1].count, 2);
  assert.equal(app.fusedCloud.count, 2);
  assert.deepEqual(Array.from(app.fusedCloud.data.slice(0, app.fusedCloud.count * 3)), fusedBefore);
});

test("canonical manifest 到达后先等待 measured PCD 内容校验", () => {
  const { app, applyCompletedScanPayload } = env.hooks;
  applyCompletedScanPayload({
    scan_id: 207,
    session_key: "session-207",
    status: "done",
    measured_object_key: "laser-scans/session-207/measured.pcd",
    result_object_key: "laser-scans/session-207/fused.pcd",
    measure_valid: true,
    compliance_determined: true,
    compliance_reason: "fixture_rule",
    measure_mode: "bg_subtract",
    length_mm: 1768,
    width_mm: 531,
    height_mm: 763,
    measured_points: 548996,
    background_revision_id: 301,
    site_revision: "site-207",
    region_revision: "region-207",
    measured_artifact: {
      xyz_sha256: MEASURED_XYZ_SHA256,
      coordinate_schema: "unit_a_world_mm_v1",
      source_points: 548996,
      site_revision: "site-207",
      region_revision: "region-207",
      background_revision: 301,
      final_b_to_a_sha256: MEASURED_FINAL_B_TO_A_SHA256,
    },
    ground_valid: true,
    overlay: { valid: true },
  });
  assert.equal(app.activeScanId, 207);
  assert.equal(app.activeSessionKey, "session-207");
  assert.equal(app.primaryCloudName, "fused");
  assert.equal(app.measure.measure_valid, false);
  assert.equal(app.measure.measure_reason, "measured_cloud_unverified");
  assert.equal(app.measure.compliance_determined, false);
  assert.equal(app.measure.compliance_reason, "measured_cloud_unverified");
  assert.equal(app.measure.length_mm, 0);
  assert.equal(app.measure.measured_points, 548996);
  assert.equal(app.overlay, null);
});

test("measured 下载失败时清空外廓结论并仅显示 fused 诊断，重试校验成功后恢复", async () => {
  const { app, applyCompletedScanPayload, downloadFinalClouds, scanContextSnapshot } = env.hooks;
  const payload = {
    scan_id: 207,
    session_key: "session-207",
    status: "done",
    measured_object_key: "laser-scans/session-207/measured.pcd",
    result_object_key: "laser-scans/session-207/fused.pcd",
    unit_a_object_key: "laser-scans/session-207/unit_a.pcd",
    unit_b_object_key: "laser-scans/session-207/unit_b.pcd",
    points: 2,
    pts_a: 2,
    pts_b: 2,
    measure_valid: true,
    compliant: true,
    length_mm: 1768,
    width_mm: 531,
    height_mm: 763,
    axle_valid: true,
    num_axles: 2,
    wheelbases_mm: [1000],
    has_cargo_box: true,
    box_outer_length_mm: 900,
    measured_points: 2,
    background_revision_id: 301,
    site_revision: "site-207",
    region_revision: "region-207",
    measured_artifact: {
      xyz_sha256: MEASURED_XYZ_SHA256,
      coordinate_schema: "unit_a_world_mm_v1",
      source_points: 2,
      site_revision: "site-207",
      region_revision: "region-207",
      background_revision: 301,
      final_b_to_a_sha256: MEASURED_FINAL_B_TO_A_SHA256,
    },
    overlay: { valid: true },
  };
  const regularPcd = makeBinaryPcd();
  const fusedRgbPcd = makeBinaryRgbPcd();
  const regularHeaders = {
    "X-Gomob-Source-Points": "2",
    "X-Gomob-Render-Points": "2",
  };
  applyCompletedScanPayload(payload);
  env.setFetch(async (url) => {
    if (String(url).includes("/cloud/measured")) throw new Error("measured checksum mismatch");
    if (String(url).includes("/cloud/fused")) return binaryResponse(fusedRgbPcd, regularHeaders);
    return binaryResponse(regularPcd, regularHeaders);
  });

  assert.equal(await downloadFinalClouds(payload, scanContextSnapshot()), false);
  assert.equal(app.primaryCloudName, "fused");
  assert.equal(app.measure.measure_valid, false);
  assert.equal(app.measure.measure_reason, "measured_cloud_unverified");
  assert.equal(app.measure.length_mm, 0);
  assert.equal(app.measure.axle_valid, false);
  assert.equal(app.measure.has_cargo_box, false);
  assert.equal(app.overlay, null);
  assert.equal(app.fusedCloud.count, 2);

  const measuredPcd = makeBinaryPcd({
    coordinateSchema: "unit_a_world_mm_v1",
    xyzSha256: MEASURED_XYZ_SHA256,
    finalBToASha256: MEASURED_FINAL_B_TO_A_SHA256,
  });
  env.setFetch(async (url) => {
    if (String(url).includes("/cloud/measured")) {
      return binaryResponse(measuredPcd, {
        ...regularHeaders,
        "X-Gomob-Coordinate-Schema": "unit_a_world_mm_v1",
        "X-Gomob-XYZ-SHA256": MEASURED_XYZ_SHA256,
        "X-Gomob-Final-B-To-A-SHA256": MEASURED_FINAL_B_TO_A_SHA256,
      });
    }
    if (String(url).includes("/cloud/fused")) return binaryResponse(fusedRgbPcd, regularHeaders);
    return binaryResponse(regularPcd, regularHeaders);
  });

  assert.equal(await downloadFinalClouds(payload, scanContextSnapshot()), true);
  assert.equal(app.primaryCloudName, "fused");
  assert.equal(app.fusedCloud.hasColor, true);
  assert.deepEqual(Array.from(app.fusedCloud.colors.slice(0, 6)), [12, 34, 56, 78, 90, 123]);
  assert.equal(app.measure.measure_valid, true);
  assert.equal(app.measure.length_mm, 1768);
  assert.equal(app.measure.axle_valid, true);
  assert.equal(app.measure.has_cargo_box, true);
  assert.equal(app.overlay.valid, true);
  assert.equal(env.hooks.els.measureCompliance.textContent, "法规未判定");
  assert.equal(env.hooks.els.measureCompliance.className, "measure-badge warn");

  const fetchCount = env.fetchCalls.length;
  applyCompletedScanPayload(payload);
  assert.equal(app.measure.measure_valid, true);
  assert.equal(app.measure.length_mm, 1768);
  assert.equal(app.overlay.valid, true);
  assert.equal(await downloadFinalClouds(payload, scanContextSnapshot()), true);
  assert.equal(env.fetchCalls.length, fetchCount);
});

test("受控启用 site 保留联调尺寸但不误报生产验证失败", async () => {
  const { app, applyCompletedScanPayload, downloadFinalClouds, scanContextSnapshot, els } = env.hooks;
  const notice = "当前外参已受控启用，历史质量字段未留存";
  const payload = {
    scan_id: 212,
    session_key: "session-212",
    status: "done",
    measured_object_key: "laser-scans/session-212/measured.pcd",
    result_object_key: "laser-scans/session-212/fused.pcd",
    unit_a_object_key: "laser-scans/session-212/unit_a.pcd",
    unit_b_object_key: "laser-scans/session-212/unit_b.pcd",
    points: 2,
    pts_a: 2,
    pts_b: 2,
    measure_valid: true,
    length_mm: 1768,
    width_mm: 531,
    height_mm: 763,
    measured_points: 2,
    site_quality_verified: false,
    site_quality_override: true,
    production_eligible: false,
    compliance_determined: true,
    compliant: true,
    violations: ["fixture"],
    compliance: { determined: true, compliant: true, violations: ["fixture"] },
    background_revision_id: 301,
    site_revision: "site-212",
    region_revision: "region-212",
    measured_artifact: {
      xyz_sha256: MEASURED_XYZ_SHA256,
      coordinate_schema: "unit_a_world_mm_v1",
      source_points: 2,
      site_revision: "site-212",
      region_revision: "region-212",
      background_revision: 301,
      final_b_to_a_sha256: MEASURED_FINAL_B_TO_A_SHA256,
    },
    overlay: { valid: true },
  };
  const regularPcd = makeBinaryPcd();
  const regularHeaders = {
    "X-Gomob-Source-Points": "2",
    "X-Gomob-Render-Points": "2",
  };
  const measuredPcd = makeBinaryPcd({
    coordinateSchema: "unit_a_world_mm_v1",
    xyzSha256: MEASURED_XYZ_SHA256,
    finalBToASha256: MEASURED_FINAL_B_TO_A_SHA256,
  });
  applyCompletedScanPayload(payload);
  env.setFetch(async (url) => {
    if (String(url).includes("/cloud/measured")) {
      return binaryResponse(measuredPcd, {
        ...regularHeaders,
        "X-Gomob-Coordinate-Schema": "unit_a_world_mm_v1",
        "X-Gomob-XYZ-SHA256": MEASURED_XYZ_SHA256,
        "X-Gomob-Final-B-To-A-SHA256": MEASURED_FINAL_B_TO_A_SHA256,
      });
    }
    return binaryResponse(regularPcd, regularHeaders);
  });

  assert.equal(await downloadFinalClouds(payload, scanContextSnapshot()), true);
  assert.equal(app.measure.measure_valid, true);
  assert.equal(app.measure.length_mm, 1768);
  assert.equal(app.measure.width_mm, 531);
  assert.equal(app.measure.height_mm, 763);
  assert.equal(app.measure.site_quality_verified, false);
  assert.equal(app.measure.site_quality_override, true);
  assert.equal(app.measure.production_eligible, false);
  assert.equal(app.measure.compliance_determined, false);
  assert.equal(app.measure.compliant, false);
  assert.deepEqual(Array.from(app.measure.violations), []);
  assert.equal(app.measure.compliance.determined, false);
  assert.match(els.measureBody.innerHTML, new RegExp(notice));
  assert.doesNotMatch(els.measureBody.innerHTML, /未通过生产验证/);
  assert.match(els.measureBody.innerHTML, /1[,]?768 mm/);
  assert.equal(els.measureCompliance.textContent, "法规未判定");
  assert.equal(els.measureCompliance.className, "measure-badge warn");
  assert.equal(els.scanBanner.hidden, true);
});

test("真实未验证 site 仍保留生产失败提示", () => {
  const { siteQualityResultMode, siteQualityResultText } = env.hooks;
  const payload = {
    site_quality_verified: false,
    site_quality_override: false,
    production_eligible: false,
  };
  assert.equal(siteQualityResultMode(payload), "unverified");
  assert.match(siteQualityResultText(payload), /未通过生产验证/);
});

test("measure_valid 有 measured key 但 manifest 缺失时仍 fail closed", () => {
  const { app, applyCompletedScanPayload, finalCloudPlan } = env.hooks;
  const payload = {
    scan_id: 209,
    session_key: "session-209",
    status: "done",
    measured_object_key: "laser-scans/session-209/measured.pcd",
    result_object_key: "laser-scans/session-209/fused.pcd",
    measure_valid: true,
    measured_points: 100,
    length_mm: 1768,
    width_mm: 531,
    height_mm: 763,
  };
  const plan = finalCloudPlan(payload, false);
  assert.equal(plan.contractError, "measured_artifact_missing");
  assert.equal(plan.primaryCloudName, "fused");
  assert.deepEqual(Array.from(plan.requiredCloudNames), ["unit_a", "unit_b", "fused"]);
  applyCompletedScanPayload(payload);
  assert.equal(app.measure.measure_valid, false);
  assert.equal(app.measure.measure_reason, "measured_artifact_missing");
  assert.equal(app.overlay, null);
  assert.equal(app.fusionUnavailable, false);
  assert.equal(app.primaryCloudName, "fused");
});

test("无效测量的 measured key 缺 manifest 时只回退 fused 诊断", () => {
  const { app, applyCompletedScanPayload, finalCloudPlan } = env.hooks;
  const payload = {
    scan_id: 210,
    session_key: "session-210",
    status: "done",
    measured_object_key: "laser-scans/session-210/measured.pcd",
    result_object_key: "laser-scans/session-210/fused.pcd",
    unit_a_object_key: "laser-scans/session-210/unit_a.pcd",
    unit_b_object_key: "laser-scans/session-210/unit_b.pcd",
    measure_valid: false,
    measure_reason: "measurement_invalid",
    overlay: { valid: true },
  };
  const plan = finalCloudPlan(payload, false);
  assert.equal(plan.contractError, "");
  assert.equal(plan.primaryCloudName, "fused");
  assert.deepEqual(Array.from(plan.requiredCloudNames), ["unit_a", "unit_b", "fused"]);
  assert.equal(plan.measuredArtifact, null);

  applyCompletedScanPayload(payload);
  assert.equal(app.primaryCloudName, "fused");
  assert.equal(app.measure.measure_valid, false);
  assert.equal(app.overlay, null);
});

test("measure_valid 缺 measured key 时仅显示无外廓的 fused 诊断", () => {
  const { app, applyCompletedScanPayload, finalCloudPlan } = env.hooks;
  const payload = {
    scan_id: 208,
    session_key: "session-208",
    status: "done",
    result_object_key: "laser-scans/session-208/fused.pcd",
    measure_valid: true,
    length_mm: 3558,
    width_mm: 144,
    height_mm: 3107,
    overlay: { valid: true },
  };
  const plan = finalCloudPlan(payload, false);
  assert.equal(plan.contractError, "measure_valid_without_measured_cloud");
  assert.equal(plan.primaryCloudName, "fused");
  assert.deepEqual(Array.from(plan.requiredCloudNames), ["unit_a", "unit_b", "fused"]);

  applyCompletedScanPayload(payload);
  assert.equal(app.measure.measure_valid, false);
  assert.equal(app.measure.measure_reason, "measure_valid_without_measured_cloud");
  assert.equal(app.overlay, null);
  assert.equal(app.primaryCloudName, "fused");
});

test("harness 从真实 Web 状态机输出 canonical 结果", {
  skip: !process.env.GOMOB_PARITY_FIXTURE || !process.env.GOMOB_PARITY_OUTPUT,
}, async () => {
  const fixturePath = process.env.GOMOB_PARITY_FIXTURE;
  const outputPath = process.env.GOMOB_PARITY_OUTPUT;
  const payload = JSON.parse(fs.readFileSync(fixturePath, "utf8"));
  const { app, applyCompletedScanPayload, downloadFinalClouds, scanContextSnapshot } = env.hooks;
  const artifact = payload.measured_artifact;
  const sources = {
    measured: Number(payload.measured_points),
    unit_a: Number(payload.pts_a),
    unit_b: Number(payload.pts_b),
    fused: Number(payload.points),
  };
  const points = Array.from({ length: 32 }, (_, index) => [index + 1, index + 2, index + 3]);

  applyCompletedScanPayload(payload);
  env.setFetch(async (url) => {
    const match = String(url).match(/\/cloud\/(measured|unit_a|unit_b|fused)\?/);
    assert.ok(match, `无法识别点云 URL: ${url}`);
    const name = match[1];
    const sourcePoints = sources[name];
    const measured = name === "measured";
    const pcd = makeBinaryPcd({
      points,
      sourcePoints,
      coordinateSchema: measured ? artifact.coordinate_schema : "",
      xyzSha256: measured ? artifact.xyz_sha256 : "",
      finalBToASha256: measured ? artifact.final_b_to_a_sha256 : "",
    });
    const headers = {
      "X-Gomob-Source-Points": String(sourcePoints),
      "X-Gomob-Render-Points": String(points.length),
    };
    if (measured) {
      headers["X-Gomob-Coordinate-Schema"] = artifact.coordinate_schema;
      headers["X-Gomob-XYZ-SHA256"] = artifact.xyz_sha256;
      headers["X-Gomob-Final-B-To-A-SHA256"] = artifact.final_b_to_a_sha256;
    }
    return binaryResponse(pcd, headers);
  });

  assert.equal(await downloadFinalClouds(payload, scanContextSnapshot()), true);
  assert.equal(app.measure.measure_valid, true);
  fs.writeFileSync(outputPath, JSON.stringify(webParityRecord(app, payload)), "utf8");
});
