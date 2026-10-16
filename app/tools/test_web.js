/*
 * PvtDate web-app test harness.
 *
 * Runs the *actual* scripts embedded in index.html under a minimal DOM/browser
 * shim using QuickJS, then asserts the real Store logic (persistence, legacy
 * migration, validation, backup/restore and version comparison).
 *
 * Usage:  qjs --std tools/test_web.js [path/to/index.html]
 */

const INDEX = (typeof scriptArgs !== "undefined" && scriptArgs[1]) ? scriptArgs[1] : "index.html";

const log = (msg) => std.puts(msg + "\n");
const err = (msg) => std.puts(msg + "\n");

/* ---------- minimal browser shim ---------- */

const store = {};

const localStorageShim = {
    get length() { return Object.keys(store).length; },
    key(i) { return Object.keys(store)[i] ?? null; },
    getItem(k) { return Object.prototype.hasOwnProperty.call(store, k) ? store[k] : null; },
    setItem(k, v) { store[k] = String(v); },
    removeItem(k) { delete store[k]; },
    clear() { for (const k of Object.keys(store)) delete store[k]; },
};

function makeClassList() {
    const set = new Set();
    return {
        add: (...c) => c.forEach(x => set.add(x)),
        remove: (...c) => c.forEach(x => set.delete(x)),
        toggle: (c, on) => { if (on === undefined) { set.has(c) ? set.delete(c) : set.add(c); } else if (on) { set.add(c); } else { set.delete(c); } },
        contains: (c) => set.has(c),
    };
}

function makeEl(id) {
    const el = {
        id: id || "",
        textContent: "",
        innerHTML: "",
        value: "",
        checked: false,
        disabled: false,
        dataset: {},
        style: { setProperty() {}, },
        children: [],
        classList: makeClassList(),
        appendChild(c) { this.children.push(c); return c; },
        removeChild() {},
        remove() {},
        click() {},
        setAttribute() {},
        addEventListener() {},
        removeEventListener() {},
        querySelector() { return null; },
        querySelectorAll() { return []; },
        closest() { return null; },
        focus() {},
        offsetWidth: 1,
    };
    return el;
}

const elements = {};
function getEl(id) {
    if (!elements[id]) elements[id] = makeEl(id);
    return elements[id];
}

const documentShim = {
    createElement: (tag) => makeEl(tag),
    createTextNode: (t) => ({ textContent: t }),
    getElementById: getEl,
    querySelector: () => null,
    querySelectorAll: () => [],
    addEventListener: () => {},
    body: { appendChild() {} },
    documentElement: { dataset: {} },
};

// In a browser `window === globalThis`; mirror that so window.PVTDATE is visible.
globalThis.window = globalThis;
globalThis.matchMedia = () => ({ matches: false });
globalThis.open = () => null;

globalThis.document = documentShim;
globalThis.localStorage = localStorageShim;
globalThis.requestAnimationFrame = (fn) => setTimeout(fn, 0);
globalThis.AbortController = undefined;
globalThis.Blob = function () {};
globalThis.URL = { createObjectURL: () => "blob:x", revokeObjectURL: () => {} };
globalThis.FileReader = function () {};

let fetchMode = "ok";
globalThis.fetch = async (url) => {
    if (fetchMode === "ok") {
        return {
            ok: true,
            json: async () => ({ current_condition: [{ temp_C: "27", weatherDesc: [{ value: "Sunny" }] }] }),
        };
    }
    throw new Error("offline");
};

/* ---------- load the real app code ---------- */

const html = std.loadFile(INDEX);
if (!html) {
    err("could not read " + INDEX);
    std.exit(2);
}

const scripts = [];
const re = /<script>([\s\S]*?)<\/script>/g;
let m;
while ((m = re.exec(html)) !== null) scripts.push(m[1]);

// 1) Syntax check every inline script by compiling it.
for (let i = 0; i < scripts.length; i++) {
    try {
        new Function(scripts[i]);
    } catch (e) {
        err("SYNTAX ERROR in script #" + i + ": " + e.message);
        std.exit(1);
    }
}
log("[ok] syntax: compiled " + scripts.length + " inline scripts");

// 2) Execute them together in one shared scope (classic scripts share globals).
try {
    new Function(scripts.join("\n;\n"))();
} catch (e) {
    err("RUNTIME ERROR while executing app: " + e.message + "\n" + (e.stack || ""));
    std.exit(1);
}

/* ---------- assertions ---------- */

let pass = 0, fail = 0;
function assert(cond, msg) {
    if (cond) { pass++; log("[ok] " + msg); }
    else { fail++; err("[FAIL] " + msg); }
}

const P = globalThis.PVTDATE;
assert(!!P, "PVTDATE API exposed");
assert(P.version === "1.1.0", "version is 1.1.0");
assert(typeof P._store === "object", "store exposed for tests");

const S = P._store;

// save valid record
let r = S.saveAll({ date: "2026-10-17", items: { Mathematics: "present", Biology: "absent" } });
assert(r.ok === true, "saveAll accepts a valid record");
let att = S.loadAtt();
assert(att["2026-10-17"].Mathematics === "present", "saved present persists");
assert(att["2026-10-17"].Biology === "absent", "saved absent persists");

// reject invalid date
r = S.saveAll({ date: "17-10-2026", items: { Biology: "present" } });
assert(r.ok === false && r.error === "invalid date", "rejects invalid date");

// ignore unknown category / status
r = S.saveAll({ date: "2026-10-18", items: { Ghost: "present", Biology: "maybe" } });
assert(r.ok === true, "saveAll tolerates unknown fields");
att = S.loadAtt();
assert(!att["2026-10-18"] || (att["2026-10-18"].Ghost === undefined), "unknown category not stored");

// legacy migration: pvtdate.att.<date> keys are folded in and removed
localStorage.clear();
localStorage.setItem("pvtdate.att.2026-10-16", JSON.stringify({ "2026-10-16": { Chemistry: "not_marked" } }));
localStorage.setItem("pvtdate.att.2026-10-15", JSON.stringify({ "2026-10-15": { School: "present" } }));
localStorage.setItem("pvtdate.att.2026-10-14", JSON.stringify({ "2026-10-14": { Mathematics: "absent" } }));
att = S.loadAtt();
assert(att["2026-10-16"] && att["2026-10-16"].Chemistry === "not_marked", "migrates legacy key 1");
assert(att["2026-10-15"] && att["2026-10-15"].School === "present", "migrates legacy key 2");
assert(att["2026-10-14"] && att["2026-10-14"].Mathematics === "absent", "migrates all legacy keys (no skip bug)");
assert(localStorage.getItem("pvtdate.att.2026-10-16") === null, "legacy keys removed after migration");

// version comparison
assert(P._cmpVersion("v1.2.0", "1.1.0") === 1, "cmpVersion detects newer");
assert(P._cmpVersion("1.1.0", "1.1.0") === 0, "cmpVersion equal");
assert(P._cmpVersion("1.0.9", "1.1.0") === -1, "cmpVersion older");

// corrupt data resilience
localStorage.clear();
localStorage.setItem("pvtdate.att", "{not json");
att = S.loadAtt();
assert(typeof att === "object" && att !== null, "recovers from corrupt stored JSON");

log("\n" + pass + " passed, " + fail + " failed");
std.exit(fail === 0 ? 0 : 1);
