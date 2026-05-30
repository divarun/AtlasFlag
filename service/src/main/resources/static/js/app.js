'use strict';
const API_V1 = '/api/v1';
const FLAGS_URL = `${API_V1}/flags`;

const token = localStorage.getItem('token');
const savedUser = localStorage.getItem('username');

let currentEnvironment = 'DEVELOPMENT';
let currentPage = 0;
let totalPages = 1;
let searchDebounce = null;
let targetingRules = [];
let sseConnection = null;
let confirmCallback = null;
let actionSheetCtx = null;
const auditLogChanges = new Map();
const userMap = new Map();

if (!token) window.location.href = '/login';

/* ── Helpers ──────────────────────────────────────────────────────────────── */
function escapeHtml(val) {
    const d = document.createElement('div');
    d.textContent = val == null ? '' : String(val);
    return d.innerHTML;
}

function formatCount(n) {
    if (n == null || n === 0) return '';
    if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M';
    if (n >= 1_000) return (n / 1_000).toFixed(1) + 'k';
    return String(n);
}

/* ── Toast notifications ─────────────────────────────────────────────────── */
function toast(message, type = 'success', duration = 3500) {
    const container = document.getElementById('toastContainer');
    const colors = {
        success: 'bg-emerald-600',
        error:   'bg-red-600',
        warning: 'bg-amber-500',
        info:    'bg-teal-700',
    };
    const icons = {
        success: '<path stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7"/>',
        error:   '<path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12"/>',
        warning: '<path stroke-linecap="round" stroke-linejoin="round" d="M12 9v3m0 4h.01M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/>',
        info:    '<path stroke-linecap="round" stroke-linejoin="round" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"/>',
    };
    const el = document.createElement('div');
    el.className = `toast-el pointer-events-auto flex items-center gap-3 px-4 py-3 rounded-xl shadow-lg text-white text-sm font-medium ${colors[type] || colors.info}`;
    el.innerHTML = `
        <svg class="w-5 h-5 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2">${icons[type] || icons.info}</svg>
        <span class="flex-1">${escapeHtml(message)}</span>
        <button onclick="this.parentElement.remove()" class="opacity-70 hover:opacity-100 flex-shrink-0 ml-1">
            <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12"/></svg>
        </button>`;
    container.appendChild(el);
    setTimeout(() => el.remove(), duration);
}

/* ── Confirm modal ───────────────────────────────────────────────────────── */
function showConfirm(title, message, onConfirm, okLabel = 'Confirm', danger = true) {
    confirmCallback = onConfirm;
    document.getElementById('confirmTitle').textContent = title;
    document.getElementById('confirmMessage').textContent = message;
    const btn = document.getElementById('confirmOkBtn');
    btn.textContent = okLabel;
    btn.className = `btn-primary flex-1 ${danger ? '' : ''}`;
    btn.style.background = danger ? '#DC2626' : 'var(--teal)';
    btn.style.justifyContent = 'center';
    document.getElementById('confirmModal').classList.remove('hidden');
}
function cancelConfirm() {
    confirmCallback = null;
    document.getElementById('confirmModal').classList.add('hidden');
}
function okConfirm() {
    const cb = confirmCallback;
    cancelConfirm();
    if (cb) cb();
}

/* ── User menu ───────────────────────────────────────────────────────────── */
function toggleUserMenu() {
    document.getElementById('userMenu').classList.toggle('hidden');
}
document.addEventListener('click', (e) => {
    if (!document.getElementById('userMenuBtn')?.contains(e.target)) {
        document.getElementById('userMenu')?.classList.add('hidden');
    }
});

/* ── Init ────────────────────────────────────────────────────────────────── */
document.addEventListener('DOMContentLoaded', () => {
    const username = savedUser || 'Admin';
    const el = document.getElementById('usernameDisplay');
    if (el) el.textContent = username;
    const avatar = document.getElementById('userAvatar');
    if (avatar) avatar.textContent = username.charAt(0).toUpperCase();
    const menuName = document.getElementById('userMenuName');
    if (menuName) menuName.textContent = username;

    showTab('flags');
    loadFlags();
    connectSSE();
    document.getElementById('flagForm')?.addEventListener('submit', submitFlagForm);
    document.getElementById('webhookForm')?.addEventListener('submit', submitWebhookForm);
    document.getElementById('userForm')?.addEventListener('submit', submitUserForm);
    document.getElementById('changePasswordForm')?.addEventListener('submit', submitChangePasswordForm);

    const payload = getJwtPayload();
    if (payload.role === 'ADMIN') document.getElementById('tabUsers')?.classList.remove('hidden');
});

/* ── Tabs ────────────────────────────────────────────────────────────────── */
function showTab(tab) {
    const sections = { flags: 'flagsSection', audit: 'auditSection', webhooks: 'webhooksSection', users: 'usersSection' };
    const buttons  = { flags: 'tabFlags',     audit: 'tabAudit',     webhooks: 'tabWebhooks',     users: 'tabUsers' };

    Object.keys(sections).forEach(t => {
        const section = document.getElementById(sections[t]);
        const btn = document.getElementById(buttons[t]);
        if (!section || !btn) return;
        const active = t === tab;
        section.classList.toggle('hidden', !active);
        btn.className = `nav-item${active ? ' nav-active' : ''}`;
    });

    if (tab === 'audit')    { currentPage = 0; loadAuditLogs(); }
    if (tab === 'webhooks') loadWebhooks();
    if (tab === 'users')    loadUsers();
}

/* ── SSE ─────────────────────────────────────────────────────────────────── */
function connectSSE() {
    if (sseConnection) { sseConnection.close(); sseConnection = null; }
    const url = `${FLAGS_URL}/stream?environment=${currentEnvironment}`;
    sseConnection = new EventSource(url);

    sseConnection.addEventListener('connected', () => {
        document.getElementById('sseDot').style.background = '#34D399';
        document.getElementById('sseStatus').textContent = 'Live';
    });
    sseConnection.addEventListener('FLAG_CHANGED', () => loadFlags());
    sseConnection.onerror = () => {
        document.getElementById('sseDot').style.background = 'var(--text-3)';
        document.getElementById('sseStatus').textContent = 'Offline';
    };
}

/* ── Flag loading ────────────────────────────────────────────────────────── */
function debouncedLoadFlags() {
    clearTimeout(searchDebounce);
    searchDebounce = setTimeout(loadFlags, 280);
}

async function loadFlags() {
    renderFlagsSkeleton();
    const search = document.getElementById('flagSearch')?.value.trim();
    const flagsUrl = `${FLAGS_URL}?environment=${currentEnvironment}${search ? '&search=' + encodeURIComponent(search) : ''}`;
    const analyticsUrl = `${FLAGS_URL}/analytics?environment=${currentEnvironment}&hours=24`;
    try {
        const [flags, analytics] = await Promise.all([
            apiCall(flagsUrl),
            apiCall(analyticsUrl).catch(() => ({}))
        ]);
        renderFlags(flags || [], analytics || {});
    } catch (err) {
        document.getElementById('flagsList').innerHTML =
            `<div class="bg-white rounded-2xl border border-red-100 p-6 text-center text-sm text-red-500">Failed to load flags. Check your connection.</div>`;
    }
}

function renderFlagsSkeleton() {
    document.getElementById('flagsList').innerHTML = Array(3).fill(`
        <div style="background:#fff;border:1px solid var(--border);border-radius:12px;padding:16px 18px">
            <div class="flex items-start gap-3">
                <div class="flex-1 space-y-2.5">
                    <div class="flex gap-2"><div class="sk h-5 w-36"></div><div class="sk h-5 w-16"></div></div>
                    <div class="sk h-4 w-48"></div>
                    <div class="sk h-3 w-28"></div>
                </div>
                <div class="sk h-6 w-11 rounded-full"></div>
            </div>
        </div>`).join('');
}

/* ── Flag rendering ──────────────────────────────────────────────────────── */
const ENV_BADGE = {
    DEVELOPMENT: 'bg-sky-50 text-sky-700',
    STAGING:     'bg-amber-50 text-amber-700',
    PRODUCTION:  'bg-rose-50 text-rose-700',
};
const TYPE_BADGE = {
    BOOLEAN: 'bg-teal-50 text-teal-700',
    STRING:  'bg-violet-50 text-violet-700',
    NUMBER:  'bg-amber-50 text-amber-700',
    JSON:    'bg-slate-100 text-slate-600',
};

function renderFlags(flags, analytics) {
    const container = document.getElementById('flagsList');

    if (!flags.length) {
        const search = document.getElementById('flagSearch')?.value.trim();
        container.innerHTML = `
            <div style="background:#fff;border:1px solid var(--border);border-radius:12px;padding:64px 24px;text-align:center" class="fade-in">
                <div style="width:52px;height:52px;background:var(--teal-bg);border-radius:14px;display:flex;align-items:center;justify-content:center;margin:0 auto 16px">
                    <svg width="24" height="24" fill="none" stroke="#0B7067" viewBox="0 0 24 24" stroke-width="1.5">
                        <path stroke-linecap="round" stroke-linejoin="round" d="M4 15s1-1 4-1 5 2 8 2 4-1 4-1V3s-1 1-4 1-5-2-8-2-4 1-4 1z"/>
                        <line x1="4" y1="22" x2="4" y2="15" stroke-linecap="round"/>
                    </svg>
                </div>
                <h3 style="font-size:15px;font-weight:600;color:var(--text-1);margin-bottom:6px">${search ? 'No flags match your search' : 'No flags yet'}</h3>
                <p style="font-size:13px;color:var(--text-3);max-width:280px;margin:0 auto 20px;line-height:1.6">
                    ${search ? `No results for "${escapeHtml(search)}" in ${currentEnvironment.toLowerCase()}.`
                             : `Create your first feature flag to start controlling features in ${currentEnvironment.toLowerCase()}.`}
                </p>
                ${!search ? `<button onclick="showCreateModal()" class="btn-primary" style="display:inline-flex">
                    <svg width="14" height="14" fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2.5"><path stroke-linecap="round" stroke-linejoin="round" d="M12 4v16m8-8H4"/></svg>
                    Create your first flag
                </button>` : ''}
            </div>`;
        return;
    }

    container.innerHTML = flags.map((f, i) => {
        const evalCount = analytics[f.flagKey];
        const envCls  = ENV_BADGE[f.environment]  || 'bg-gray-50 text-gray-500';
        const typeCls = TYPE_BADGE[f.flagType]     || 'bg-gray-50 text-gray-500';
        const typeLabel = f.flagType || 'BOOLEAN';
        const evalBadge = evalCount
            ? `<span style="font-size:11px;font-weight:500;color:var(--teal)">${formatCount(evalCount)}/24h</span>`
            : '';
        const targetingBadge = f.targetingRules
            ? `<span title="Has targeting rules" style="font-size:11px;font-weight:500;color:#7C3AED;background:#F5F3FF;padding:2px 6px;border-radius:5px">🎯 Targeted</span>`
            : '';
        const rolloutBadge = f.rolloutPercentage != null
            ? `<span style="font-size:11px;color:var(--text-3)">${f.rolloutPercentage}% rollout</span>`
            : '';

        return `
        <div class="fade-in" style="background:#fff;border:1px solid var(--border);border-radius:12px;padding:14px 18px;transition:border-color .12s,box-shadow .12s;animation-delay:${i * 30}ms" onmouseover="this.style.borderColor='#A7D4D1';this.style.boxShadow='0 2px 8px rgba(11,112,103,.07)'" onmouseout="this.style.borderColor='var(--border)';this.style.boxShadow='none'">
            <div class="flex items-start gap-3">
                <div class="flex-1 min-w-0">
                    <div style="display:flex;flex-wrap:wrap;align-items:center;gap:6px;margin-bottom:4px">
                        <code style="font-family:'DM Mono',monospace;font-size:13px;font-weight:500;color:var(--teal)">${escapeHtml(f.flagKey)}</code>
                        <span class="text-xs font-medium px-1.5 py-0.5 rounded-md ${typeCls}" style="font-size:11px">${escapeHtml(typeLabel)}</span>
                        ${targetingBadge}
                    </div>
                    <p style="font-size:13px;color:var(--text-2);overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${escapeHtml(f.name)}</p>
                    <div style="display:flex;flex-wrap:wrap;align-items:center;gap:8px;margin-top:6px">
                        <span class="text-xs font-medium px-1.5 py-0.5 rounded ${envCls}" style="font-size:11px">${escapeHtml(f.environment)}</span>
                        ${rolloutBadge}
                        ${evalBadge}
                    </div>
                </div>
                <div class="flex items-center gap-2 flex-shrink-0 pt-0.5">
                    <button class="tog" onclick="toggleFlag('${escapeHtml(f.flagKey)}', this)"
                            aria-label="${f.enabled ? 'Disable' : 'Enable'} ${escapeHtml(f.flagKey)}"
                            title="${f.enabled ? 'Click to disable' : 'Click to enable'}">
                        <div class="tog-t${f.enabled ? ' on' : ''}"></div>
                    </button>
                    <div class="hidden sm:flex items-center divide-x" style="font-size:12px;color:var(--text-3);border-color:var(--border)">
                        <button onclick="editFlag(${f.id})" style="padding:4px 10px;background:transparent;border:none;cursor:pointer;color:var(--text-3);font-size:12px;font-family:inherit;transition:color .12s" onmouseover="this.style.color='var(--teal)'" onmouseout="this.style.color='var(--text-3)'">Edit</button>
                        <button onclick="showPromoteModal(${f.id},'${escapeHtml(f.flagKey)}','${escapeHtml(f.environment)}')" style="padding:4px 10px;background:transparent;border:none;cursor:pointer;color:var(--text-3);font-size:12px;font-family:inherit;transition:color .12s" onmouseover="this.style.color='#7C3AED'" onmouseout="this.style.color='var(--text-3)'">Promote</button>
                        <button onclick="showFlagAnalytics('${escapeHtml(f.flagKey)}')" style="padding:4px 10px;background:transparent;border:none;cursor:pointer;color:var(--text-3);font-size:12px;font-family:inherit;transition:color .12s" onmouseover="this.style.color='var(--teal)'" onmouseout="this.style.color='var(--text-3)'">Analytics</button>
                        <button onclick="deleteFlag(${f.id},'${escapeHtml(f.flagKey)}')" style="padding:4px 10px;background:transparent;border:none;cursor:pointer;color:var(--text-3);font-size:12px;font-family:inherit;transition:color .12s" onmouseover="this.style.color='#DC2626'" onmouseout="this.style.color='var(--text-3)'">Delete</button>
                    </div>
                    <button class="sm:hidden" onclick="showActionSheet(${f.id},'${escapeHtml(f.flagKey)}','${escapeHtml(f.environment)}')" aria-label="More actions"
                            style="padding:6px;border-radius:7px;border:none;background:transparent;cursor:pointer;color:var(--text-3)" onmouseover="this.style.background='var(--page)'" onmouseout="this.style.background='transparent'">
                        <svg width="18" height="18" fill="currentColor" viewBox="0 0 24 24"><circle cx="12" cy="5" r="1.5"/><circle cx="12" cy="12" r="1.5"/><circle cx="12" cy="19" r="1.5"/></svg>
                    </button>
                </div>
            </div>
        </div>`;
    }).join('');
}

/* ── Mobile action sheet ─────────────────────────────────────────────────── */
function showActionSheet(id, flagKey, env) {
    actionSheetCtx = { id, flagKey, env };
    document.getElementById('actionSheetTitle').textContent = flagKey;
    document.getElementById('actionSheetButtons').innerHTML = `
        <button onclick="editFlag(${id});closeActionSheet()" style="width:100%;text-align:left;padding:12px 14px;border-radius:9px;border:none;background:transparent;cursor:pointer;font-size:13.5px;font-weight:500;color:var(--text-1);font-family:inherit;transition:background .12s" onmouseover="this.style.background='var(--page)'" onmouseout="this.style.background='transparent'">
            ✏️  Edit flag
        </button>
        <button onclick="showPromoteModal(${id},'${escapeHtml(flagKey)}','${escapeHtml(env)}');closeActionSheet()" style="width:100%;text-align:left;padding:12px 14px;border-radius:9px;border:none;background:transparent;cursor:pointer;font-size:13.5px;font-weight:500;color:#7C3AED;font-family:inherit;transition:background .12s" onmouseover="this.style.background='var(--page)'" onmouseout="this.style.background='transparent'">
            🚀  Promote to environment
        </button>
        <button onclick="showFlagAnalytics('${escapeHtml(flagKey)}');closeActionSheet()" style="width:100%;text-align:left;padding:12px 14px;border-radius:9px;border:none;background:transparent;cursor:pointer;font-size:13.5px;font-weight:500;color:var(--teal);font-family:inherit;transition:background .12s" onmouseover="this.style.background='var(--page)'" onmouseout="this.style.background='transparent'">
            📊  View analytics
        </button>
        <button onclick="deleteFlag(${id},'${escapeHtml(flagKey)}');closeActionSheet()" style="width:100%;text-align:left;padding:12px 14px;border-radius:9px;border:none;background:transparent;cursor:pointer;font-size:13.5px;font-weight:500;color:#DC2626;font-family:inherit;transition:background .12s" onmouseover="this.style.background='#FEF2F2'" onmouseout="this.style.background='transparent'">
            🗑️  Delete flag
        </button>`;
    document.getElementById('actionSheet').classList.remove('hidden');
}
function closeActionSheet() {
    document.getElementById('actionSheet').classList.add('hidden');
    actionSheetCtx = null;
}

/* ── Flag CRUD ───────────────────────────────────────────────────────────── */
async function submitFlagForm(e) {
    e.preventDefault();
    const id = document.getElementById('flagId').value;
    const flagType = document.getElementById('flagType').value;
    const payload = {
        flagKey:           document.getElementById('flagKey').value.trim(),
        name:              document.getElementById('flagName').value.trim(),
        description:       document.getElementById('flagDescription').value.trim(),
        environment:       document.getElementById('flagEnvironment').value,
        enabled:           document.getElementById('flagEnabled').checked,
        defaultValue:      document.getElementById('defaultValue').checked,
        rolloutPercentage: Number(document.getElementById('rolloutPercentage').value),
        flagType,
        stringValue:       flagType !== 'BOOLEAN' ? (document.getElementById('flagStringValue').value.trim() || null) : null,
        targetingRules:    buildTargetingRulesJson(),
    };
    try {
        await apiCall(id ? `${FLAGS_URL}/${id}` : FLAGS_URL, id ? 'PUT' : 'POST', payload);
        closeModal('flagModal');
        loadFlags();
        toast(id ? 'Flag updated' : 'Flag created', 'success');
    } catch (err) {
        toast(err.message || 'Failed to save flag', 'error');
    }
}

async function editFlag(id) {
    try {
        const f = await apiCall(`${FLAGS_URL}/${id}`);
        document.getElementById('flagId').value          = f.id;
        document.getElementById('flagKey').value         = f.flagKey;
        document.getElementById('flagName').value        = f.name;
        document.getElementById('flagDescription').value = f.description || '';
        document.getElementById('flagEnvironment').value = f.environment;
        document.getElementById('flagEnabled').checked   = f.enabled;
        document.getElementById('defaultValue').checked  = f.defaultValue;
        const rp = f.rolloutPercentage != null ? f.rolloutPercentage : 100;
        document.getElementById('rolloutPercentage').value = rp;
        document.getElementById('rolloutValue').textContent = rp;
        document.getElementById('flagType').value = f.flagType || 'BOOLEAN';
        document.getElementById('flagStringValue').value = f.stringValue || '';
        onFlagTypeChange();

        targetingRules = [];
        if (f.targetingRules) {
            try {
                const parsed = JSON.parse(f.targetingRules);
                document.getElementById('targetingMatch').value = parsed.match || 'all';
                targetingRules = Array.isArray(parsed.rules) ? parsed.rules : [];
            } catch {}
        } else {
            document.getElementById('targetingMatch').value = 'all';
        }
        renderTargetingRules();

        document.getElementById('modalTitle').textContent = 'Edit Feature Flag';
        document.getElementById('flagModal').classList.remove('hidden');
    } catch (err) {
        toast('Failed to load flag details', 'error');
    }
}

async function toggleFlag(flagKey, el) {
    const track = el?.querySelector('.tog-t');
    const wasOn = track?.classList.contains('on');
    if (track) track.classList.toggle('on');        // optimistic update
    try {
        await apiCall(`${FLAGS_URL}/${flagKey}/toggle?environment=${currentEnvironment}`, 'POST');
        toast(`${flagKey} ${wasOn ? 'disabled' : 'enabled'}`, wasOn ? 'info' : 'success');
    } catch (err) {
        if (track) track.classList.toggle('on');    // revert on failure
        toast('Failed to toggle flag', 'error');
    }
}

function deleteFlag(id, flagKey) {
    showConfirm(
        'Delete flag',
        `Delete "${flagKey}"? This cannot be undone.`,
        async () => {
            try {
                await apiCall(`${FLAGS_URL}/${id}`, 'DELETE');
                loadFlags();
                toast(`${flagKey} deleted`, 'info');
            } catch (err) {
                toast('Failed to delete flag', 'error');
            }
        },
        'Delete flag',
        true
    );
}

function showCreateModal() {
    document.getElementById('flagForm').reset();
    document.getElementById('flagId').value = '';
    document.getElementById('flagEnvironment').value = currentEnvironment;
    document.getElementById('rolloutValue').textContent = '100';
    document.getElementById('flagType').value = 'BOOLEAN';
    document.getElementById('flagStringValue').value = '';
    document.getElementById('targetingMatch').value = 'all';
    targetingRules = [];
    renderTargetingRules();
    onFlagTypeChange();
    document.getElementById('modalTitle').textContent = 'Create Feature Flag';
    document.getElementById('flagModal').classList.remove('hidden');
}

/* ── Config type ─────────────────────────────────────────────────────────── */
function onFlagTypeChange() {
    const type = document.getElementById('flagType').value;
    document.getElementById('stringValueSection').classList.toggle('hidden', type === 'BOOLEAN');
}

/* ── Targeting rules ─────────────────────────────────────────────────────── */
function addTargetingRule() {
    targetingRules.push({ attribute: '', operator: 'eq', value: '' });
    renderTargetingRules();
}
function removeTargetingRule(i) {
    targetingRules.splice(i, 1);
    renderTargetingRules();
}
function updateRule(i, field, val) {
    if (targetingRules[i]) targetingRules[i][field] = val;
}
function renderTargetingRules() {
    const container = document.getElementById('targetingRulesList');
    if (!container) return;
    if (!targetingRules.length) {
        container.innerHTML = '<p style="font-size:11px;color:var(--text-3);padding:4px 0">No rules — all users see this flag when enabled.</p>';
        return;
    }
    const OPS = ['eq','neq','contains','startsWith','endsWith','in','notIn','gt','lt','gte','lte'];
    container.innerHTML = targetingRules.map((r, i) => `
        <div style="display:flex;align-items:center;gap:6px;padding:3px 0">
            <input type="text" placeholder="attribute" value="${escapeHtml(r.attribute)}"
                   oninput="updateRule(${i},'attribute',this.value)"
                   style="flex:1;min-width:0;padding:6px 8px;border:1px solid var(--border);border-radius:7px;font-size:12px;font-family:inherit;outline:none;color:var(--text-1);background:#fff" onfocus="this.style.borderColor='var(--teal)'" onblur="this.style.borderColor='var(--border)'">
            <select onchange="updateRule(${i},'operator',this.value)"
                    style="border:1px solid var(--border);border-radius:7px;padding:6px 8px;font-size:12px;background:#fff;color:var(--text-1);outline:none;cursor:pointer;font-family:inherit" onfocus="this.style.borderColor='var(--teal)'" onblur="this.style.borderColor='var(--border)'">
                ${OPS.map(op => `<option value="${op}"${r.operator === op ? ' selected' : ''}>${op}</option>`).join('')}
            </select>
            <input type="text" placeholder="value" value="${escapeHtml(r.value)}"
                   oninput="updateRule(${i},'value',this.value)"
                   style="flex:1;min-width:0;padding:6px 8px;border:1px solid var(--border);border-radius:7px;font-size:12px;font-family:inherit;outline:none;color:var(--text-1);background:#fff" onfocus="this.style.borderColor='var(--teal)'" onblur="this.style.borderColor='var(--border)'">
            <button type="button" onclick="removeTargetingRule(${i})"
                    style="padding:4px;color:var(--text-3);background:transparent;border:none;cursor:pointer;border-radius:5px;flex-shrink:0;transition:color .12s" onmouseover="this.style.color='#DC2626'" onmouseout="this.style.color='var(--text-3)'">
                <svg width="14" height="14" fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12"/></svg>
            </button>
        </div>`).join('');
}
function buildTargetingRulesJson() {
    const valid = targetingRules.filter(r => r.attribute.trim() && r.value.trim());
    if (!valid.length) return null;
    return JSON.stringify({ match: document.getElementById('targetingMatch').value, rules: valid });
}

/* ── Promote ─────────────────────────────────────────────────────────────── */
let promoteSelectedEnv = null;

function showPromoteModal(id, flagKey, currentEnv) {
    document.getElementById('promoteFlagId').value = id;
    document.getElementById('promoteFlagKey').textContent = flagKey;
    promoteSelectedEnv = null;
    const ENVS = ['DEVELOPMENT', 'STAGING', 'PRODUCTION'];
    const targets = ENVS.filter(e => e !== currentEnv);
    const container = document.getElementById('promoteEnvOptions');
    container.innerHTML = targets.map(env => `
        <label class="promote-env-option" data-env="${env}" style="display:flex;align-items:center;gap:12px;padding:12px 14px;border:1.5px solid var(--border);border-radius:10px;cursor:pointer;transition:all .12s">
            <input type="radio" name="promoteEnv" value="${env}" style="accent-color:var(--teal);width:14px;height:14px">
            <div>
                <span style="font-size:13px;font-weight:600;color:var(--text-1)">${env.charAt(0) + env.slice(1).toLowerCase()}</span>
                <p style="font-size:11px;color:var(--text-3);margin-top:2px">${env === 'PRODUCTION' ? 'Live traffic — enable with care' : env === 'STAGING' ? 'Pre-production testing' : 'Local development'}</p>
            </div>
        </label>`).join('');
    container.querySelectorAll('.promote-env-option').forEach(label => {
        label.addEventListener('click', () => {
            container.querySelectorAll('.promote-env-option').forEach(l => {
                l.style.borderColor = 'var(--border)';
                l.style.background = 'transparent';
            });
            label.style.borderColor = 'var(--teal)';
            label.style.background = 'var(--teal-bg)';
            promoteSelectedEnv = label.dataset.env;
        });
    });
    document.getElementById('promoteModal').classList.remove('hidden');
}
async function confirmPromote() {
    if (!promoteSelectedEnv) { toast('Select a target environment', 'warning'); return; }
    const id = document.getElementById('promoteFlagId').value;
    try {
        await apiCall(`${FLAGS_URL}/${id}/promote?targetEnvironment=${promoteSelectedEnv}`, 'POST');
        closeModal('promoteModal');
        loadFlags();
        toast(`Flag promoted to ${promoteSelectedEnv.toLowerCase()}`, 'success');
    } catch (err) {
        toast(err.message || 'Promotion failed — flag may already exist there', 'error');
    }
}

/* ── Analytics ───────────────────────────────────────────────────────────── */
async function showFlagAnalytics(flagKey) {
    try {
        const data = await apiCall(`${FLAGS_URL}/${encodeURIComponent(flagKey)}/analytics?environment=${currentEnvironment}&hours=24`);
        document.getElementById('analyticsTitle').textContent = flagKey;
        document.getElementById('analyticsTotal').textContent = (data.totalEvaluations || 0).toLocaleString();
        const tp = (data.truePercent || 0).toFixed(1);
        const fp = (100 - (data.truePercent || 0)).toFixed(1);
        document.getElementById('analyticsTrue').textContent = `${(data.trueCount || 0).toLocaleString()} (${tp}%)`;
        document.getElementById('analyticsFalse').textContent = `${(data.falseCount || 0).toLocaleString()} (${fp}%)`;

        const hourly = data.hourly || [];
        const maxTotal = Math.max(...hourly.map(h => (h.trueCount || 0) + (h.falseCount || 0)), 1);
        const available = 60 - hourly.length;  // fill empty hours
        const spark = document.getElementById('analyticsSparkline');
        const emptyBars = Array(Math.max(0, available)).fill(`<div style="flex:1;height:2px;background:#F3F4F6;border-radius:2px;align-self:flex-end"></div>`).join('');
        const bars = hourly.map(h => {
            const total = (h.trueCount || 0) + (h.falseCount || 0);
            const heightPct = Math.max(4, Math.round((total / maxTotal) * 100));
            const truePct = total > 0 ? Math.round(((h.trueCount || 0) / total) * 100) : 0;
            const ts = new Date(h.hour).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
            return `<div style="flex:1;height:100%;display:flex;flex-direction:column;justify-content:flex-end;min-width:2px" title="${ts}: ${total} evals">
                <div style="height:${heightPct}%;background:linear-gradient(to top,#10B981 ${truePct}%,#FCA5A5 ${truePct}%);border-radius:2px 2px 0 0;"></div>
            </div>`;
        }).join('');
        spark.innerHTML = `<div style="display:flex;align-items:flex-end;gap:1px;width:100%;height:100%">${emptyBars}${bars}</div>`;
        document.getElementById('analyticsModal').classList.remove('hidden');
    } catch (err) {
        toast('Failed to load analytics', 'error');
    }
}

/* ── Environment ─────────────────────────────────────────────────────────── */
function changeEnvironment(env) {
    currentEnvironment = env;
    document.querySelectorAll('.env-btn').forEach(btn => {
        const active = btn.dataset.env === env;
        btn.className = `env-btn${active ? ' env-active' : ''}`;
    });
    loadFlags();
    connectSSE();
}

/* ── Audit logs ──────────────────────────────────────────────────────────── */
async function loadAuditLogs() {
    const entityType = document.getElementById('entityTypeFilter')?.value.trim();
    const entityId   = document.getElementById('entityIdFilter')?.value.trim();
    const userId     = document.getElementById('userIdFilter')?.value.trim();
    const body = document.getElementById('auditTable');
    if (!body) return;

    let url;
    if (userId) {
        url = `${API_V1}/audit/user/${encodeURIComponent(userId)}?page=${currentPage}&size=20`;
    } else if (entityType && entityId) {
        url = `${API_V1}/audit/entity/${encodeURIComponent(entityType)}/${encodeURIComponent(entityId)}?page=${currentPage}&size=20`;
    } else {
        body.innerHTML = `<tr><td colspan="6" class="px-5 py-10 text-center text-gray-400 text-sm">${
            entityType ? 'Enter an Entity ID to filter, or filter by Username' : 'Apply a filter above to load audit logs'
        }</td></tr>`;
        return;
    }

    body.innerHTML = `<tr><td colspan="6" style="text-align:center;padding:32px"><div style="display:inline-block;width:18px;height:18px;border:2px solid #A7D4D1;border-top-color:var(--teal);border-radius:50%;animation:spin .7s linear infinite"></div></td></tr>`;

    try {
        const res = await apiCall(url);
        renderAuditLogs(res.content || []);
        totalPages = res.totalPages || 1;
        document.getElementById('pageInfo').textContent = `Page ${currentPage + 1} of ${totalPages}`;
    } catch {
        body.innerHTML = `<tr><td colspan="6" class="px-5 py-6 text-center text-red-500 text-sm">Failed to load logs</td></tr>`;
    }
}

function renderAuditLogs(logs) {
    const body = document.getElementById('auditTable');
    auditLogChanges.clear();
    if (!logs.length) {
        body.innerHTML = `<tr><td colspan="6" class="px-5 py-10 text-center text-gray-400 text-sm">No logs found for this filter</td></tr>`;
        return;
    }
    logs.forEach(log => { if (log.changes) auditLogChanges.set(log.id, log.changes); });
    const actionColors = {
        CREATE: 'bg-emerald-50 text-emerald-700', ENABLE: 'bg-emerald-50 text-emerald-700',
        DELETE: 'bg-red-50 text-red-600', DISABLE: 'bg-gray-100 text-gray-600',
        UPDATE: 'bg-blue-50 text-blue-700', PROMOTE: 'bg-violet-50 text-violet-700',
    };
    body.innerHTML = logs.map(log => {
        const ac = actionColors[log.action] || 'bg-gray-100 text-gray-600';
        return `
        <tr class="hover:bg-gray-50/60 transition-colors">
            <td class="px-5 py-3.5 text-sm text-gray-500 whitespace-nowrap">${escapeHtml(new Date(log.timestamp).toLocaleString())}</td>
            <td class="px-5 py-3.5 text-sm">
                <span class="font-medium text-gray-700">${escapeHtml(log.entityType)}</span>
                <span class="text-gray-400 ml-1 text-xs">#${escapeHtml(String(log.entityId))}</span>
            </td>
            <td class="px-5 py-3.5">
                <span class="text-xs font-semibold px-2 py-0.5 rounded-lg ${ac}">${escapeHtml(log.action)}</span>
            </td>
            <td class="px-5 py-3.5 text-sm text-gray-600 hidden sm:table-cell">${escapeHtml(log.userId)}</td>
            <td class="px-5 py-3.5 text-xs text-gray-400 font-mono hidden md:table-cell">${escapeHtml(log.ipAddress || '—')}</td>
            <td class="px-5 py-3.5 text-right">${log.changes
                ? `<button onclick="showChanges(${log.id})" style="font-size:12px;font-weight:600;color:var(--teal);background:transparent;border:none;cursor:pointer;text-decoration:underline;font-family:inherit">View</button>`
                : `<span style="color:var(--text-3)">—</span>`}</td>
        </tr>`;
    }).join('');
}

function showChanges(logId) {
    const changes = auditLogChanges.get(logId);
    if (!changes) return;
    const content = document.getElementById('changesContent');
    try { content.textContent = JSON.stringify(JSON.parse(changes), null, 2); }
    catch { content.textContent = changes; }
    document.getElementById('changesModal').classList.remove('hidden');
}

function changePage(delta) {
    const next = currentPage + delta;
    if (next >= 0 && next < totalPages) { currentPage = next; loadAuditLogs(); }
}

/* ── Webhooks ────────────────────────────────────────────────────────────── */
async function loadWebhooks() {
    document.getElementById('webhooksTable').innerHTML = `<tr><td colspan="4" style="text-align:center;padding:32px"><div style="display:inline-block;width:18px;height:18px;border:2px solid #A7D4D1;border-top-color:var(--teal);border-radius:50%;animation:spin .7s linear infinite"></div></td></tr>`;
    try {
        const webhooks = await apiCall(`${API_V1}/webhooks`);
        renderWebhooks(webhooks || []);
    } catch {
        document.getElementById('webhooksTable').innerHTML = `<tr><td colspan="4" class="px-5 py-6 text-center text-red-500 text-sm">Failed to load webhooks</td></tr>`;
    }
}

function renderWebhooks(webhooks) {
    const body = document.getElementById('webhooksTable');
    if (!webhooks.length) {
        body.innerHTML = `<tr><td colspan="4" class="px-5 py-12 text-center">
            <div class="w-10 h-10 bg-gray-100 rounded-xl flex items-center justify-center mx-auto mb-3">
                <svg class="w-5 h-5 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24" stroke-width="1.5"><path stroke-linecap="round" stroke-linejoin="round" d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1"/></svg>
            </div>
            <p class="text-sm text-gray-400">No webhooks yet. Add one to receive notifications.</p>
        </td></tr>`;
        return;
    }
    body.innerHTML = webhooks.map(w => `
        <tr class="hover:bg-gray-50/60 transition-colors">
            <td class="px-5 py-4 text-sm font-mono text-gray-700 break-all max-w-xs">${escapeHtml(w.url)}</td>
            <td class="px-5 py-4">
                <button onclick="toggleWebhook(${w.id})"
                        class="text-xs font-semibold px-2.5 py-1 rounded-lg transition-colors ${w.enabled ? 'bg-emerald-50 text-emerald-700 hover:bg-emerald-100' : 'bg-gray-100 text-gray-500 hover:bg-gray-200'}">
                    ${w.enabled ? 'Active' : 'Disabled'}
                </button>
            </td>
            <td class="px-5 py-4 text-sm text-gray-400 hidden sm:table-cell">${escapeHtml(new Date(w.createdAt).toLocaleDateString())}</td>
            <td class="px-5 py-4 text-right">
                <button onclick="deleteWebhook(${w.id})" class="text-sm text-gray-400 hover:text-red-500 transition-colors px-2 py-1 rounded hover:bg-red-50">Delete</button>
            </td>
        </tr>`).join('');
}

function showWebhookModal() {
    document.getElementById('webhookForm').reset();
    document.getElementById('webhookModal').classList.remove('hidden');
}

async function submitWebhookForm(e) {
    e.preventDefault();
    const payload = {
        url:    document.getElementById('webhookUrl').value.trim(),
        secret: document.getElementById('webhookSecret').value.trim()
    };
    try {
        const created = await apiCall(`${API_V1}/webhooks`, 'POST', payload);
        closeModal('webhookModal');
        if (created?.secret && !created.secret.startsWith('•')) {
            toast('Webhook created! Secret shown in clipboard prompt.', 'success', 2000);
            setTimeout(() => alert(`Save this secret — it won't be shown again:\n\n${created.secret}`), 300);
        }
        loadWebhooks();
    } catch (err) {
        toast(err.message || 'Failed to create webhook', 'error');
    }
}

async function toggleWebhook(id) {
    try {
        await apiCall(`${API_V1}/webhooks/${id}/toggle`, 'POST');
        loadWebhooks();
    } catch { toast('Failed to toggle webhook', 'error'); }
}

async function deleteWebhook(id) {
    showConfirm('Delete webhook', 'This webhook will stop receiving notifications.', async () => {
        try {
            await apiCall(`${API_V1}/webhooks/${id}`, 'DELETE');
            loadWebhooks();
            toast('Webhook deleted', 'info');
        } catch { toast('Failed to delete webhook', 'error'); }
    }, 'Delete', true);
}

function generateSecret() {
    const a = new Uint8Array(32);
    crypto.getRandomValues(a);
    document.getElementById('webhookSecret').value = Array.from(a).map(b => b.toString(16).padStart(2, '0')).join('');
}

/* ── Users ───────────────────────────────────────────────────────────────── */
function getJwtPayload() {
    try { return JSON.parse(atob(token.split('.')[1])); } catch { return {}; }
}

async function loadUsers() {
    document.getElementById('usersTable').innerHTML = `<tr><td colspan="5" style="text-align:center;padding:32px"><div style="display:inline-block;width:18px;height:18px;border:2px solid #A7D4D1;border-top-color:var(--teal);border-radius:50%;animation:spin .7s linear infinite"></div></td></tr>`;
    try {
        const users = await apiCall(`${API_V1}/users`);
        renderUsers(users || []);
    } catch {
        document.getElementById('usersTable').innerHTML = `<tr><td colspan="5" style="text-align:center;padding:24px;color:#DC2626;font-size:13px">Failed to load users</td></tr>`;
    }
}

const ROLE_STYLE = {
    ADMIN:  'background:#FEF2F2;color:#991B1B',
    USER:   'background:#EFF6FF;color:#1D4ED8',
    VIEWER: 'background:#F1F5F9;color:#475569',
};

function renderUsers(users) {
    userMap.clear();
    users.forEach(u => userMap.set(u.id, u));
    const body = document.getElementById('usersTable');
    if (!users.length) {
        body.innerHTML = `<tr><td colspan="5" style="text-align:center;padding:40px;color:var(--text-3);font-size:13px">No users found</td></tr>`;
        return;
    }
    body.innerHTML = users.map(u => {
        const roleStyle = ROLE_STYLE[u.role] || ROLE_STYLE.VIEWER;
        const isSelf = u.username === savedUser;
        return `
        <tr>
            <td style="padding:13px 16px;font-size:13px">
                <span style="font-weight:500;color:var(--text-1)">${escapeHtml(u.username)}</span>
                ${isSelf ? `<span style="margin-left:6px;font-size:11px;font-weight:500;color:var(--teal);background:var(--teal-bg);padding:1px 6px;border-radius:4px">you</span>` : ''}
            </td>
            <td style="padding:13px 16px;font-size:13px;color:var(--text-2)" class="hidden sm:table-cell">${escapeHtml(u.email || '—')}</td>
            <td style="padding:13px 16px">
                <span style="font-size:11px;font-weight:600;padding:3px 8px;border-radius:6px;${roleStyle}">${escapeHtml(u.role)}</span>
            </td>
            <td style="padding:13px 16px;font-size:13px;color:var(--text-3)" class="hidden sm:table-cell">${escapeHtml(new Date(u.createdAt).toLocaleDateString())}</td>
            <td style="padding:13px 16px;text-align:right;white-space:nowrap">
                <button onclick="editUser(${u.id})"
                        style="font-size:12px;font-weight:500;color:var(--text-2);background:transparent;border:1px solid var(--border);padding:4px 10px;border-radius:7px;cursor:pointer;transition:all .12s;margin-right:4px;font-family:inherit"
                        onmouseover="this.style.borderColor='var(--teal)';this.style.color='var(--teal)'" onmouseout="this.style.borderColor='var(--border)';this.style.color='var(--text-2)'">
                    Edit
                </button>
                <button onclick="showChangePasswordModal(${u.id})"
                        style="font-size:12px;font-weight:500;color:var(--text-2);background:transparent;border:1px solid var(--border);padding:4px 10px;border-radius:7px;cursor:pointer;transition:all .12s;margin-right:4px;font-family:inherit"
                        onmouseover="this.style.borderColor='var(--teal)';this.style.color='var(--teal)'" onmouseout="this.style.borderColor='var(--border)';this.style.color='var(--text-2)'">
                    Password
                </button>
                ${!isSelf ? `<button onclick="deleteUser(${u.id})"
                        style="font-size:12px;font-weight:500;color:var(--text-3);background:transparent;border:none;padding:4px 8px;border-radius:7px;cursor:pointer;transition:color .12s;font-family:inherit"
                        onmouseover="this.style.color='#DC2626'" onmouseout="this.style.color='var(--text-3)'">Delete</button>` : ''}
            </td>
        </tr>`;
    }).join('');
}

function showCreateUserModal() {
    document.getElementById('userForm').reset();
    document.getElementById('editUserId').value = '';
    document.getElementById('userPasswordSection').classList.remove('hidden');
    document.getElementById('newUserPassword').required = true;
    document.getElementById('userModalTitle').textContent = 'Add User';
    document.getElementById('userSubmitBtn').textContent = 'Create User';
    document.getElementById('userModal').classList.remove('hidden');
}

function editUser(id) {
    const u = userMap.get(id);
    if (!u) return;
    document.getElementById('userForm').reset();
    document.getElementById('editUserId').value = id;
    document.getElementById('newUsername').value = u.username;
    document.getElementById('newEmail').value = u.email || '';
    document.getElementById('newRole').value = u.role;
    document.getElementById('userPasswordSection').classList.add('hidden');
    document.getElementById('newUserPassword').required = false;
    document.getElementById('userModalTitle').textContent = 'Edit User';
    document.getElementById('userSubmitBtn').textContent = 'Save Changes';
    document.getElementById('userModal').classList.remove('hidden');
}

async function submitUserForm(e) {
    e.preventDefault();
    const editId = document.getElementById('editUserId').value;
    const payload = {
        username: document.getElementById('newUsername').value.trim(),
        email:    document.getElementById('newEmail').value.trim(),
        role:     document.getElementById('newRole').value,
    };
    if (!editId) payload.password = document.getElementById('newUserPassword').value;
    try {
        await apiCall(editId ? `${API_V1}/users/${editId}` : `${API_V1}/users`, editId ? 'PUT' : 'POST', payload);
        closeModal('userModal');
        loadUsers();
        toast(editId ? `${payload.username} updated` : `User ${payload.username} created`, 'success');
    } catch (err) {
        toast(err.message || (editId ? 'Failed to update user' : 'Failed to create user'), 'error');
    }
}

function deleteUser(id) {
    const u = userMap.get(id);
    if (!u) return;
    showConfirm(
        'Delete user',
        `Delete "${u.username}"? This cannot be undone.`,
        async () => {
            try {
                await apiCall(`${API_V1}/users/${id}`, 'DELETE');
                loadUsers();
                toast(`${u.username} deleted`, 'info');
            } catch (err) {
                toast(err.message || 'Failed to delete user', 'error');
            }
        },
        'Delete user',
        true
    );
}

function showChangePasswordModal(id) {
    const u = userMap.get(id);
    if (!u) return;
    document.getElementById('changePasswordForm').reset();
    document.getElementById('changePasswordUserId').value = id;
    document.getElementById('changePasswordFor').textContent = `for ${u.username}`;
    document.getElementById('changePasswordModal').classList.remove('hidden');
}

async function submitChangePasswordForm(e) {
    e.preventDefault();
    const id = document.getElementById('changePasswordUserId').value;
    const newPass     = document.getElementById('newPasswordField').value;
    const confirmPass = document.getElementById('confirmPasswordField').value;
    if (newPass !== confirmPass) { toast('Passwords do not match', 'error'); return; }
    try {
        await apiCall(`${API_V1}/users/${id}/password`, 'PUT', { password: newPass });
        closeModal('changePasswordModal');
        toast('Password updated', 'success');
    } catch (err) {
        toast(err.message || 'Failed to update password', 'error');
    }
}

/* ── API ─────────────────────────────────────────────────────────────────── */
async function apiCall(url, method = 'GET', body) {
    const options = {
        method,
        headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' }
    };
    if (body) options.body = JSON.stringify(body);
    const res = await fetch(url, options);
    if (res.status === 401 || res.status === 403) logout();
    if (!res.ok) {
        let msg = `Request failed (${res.status})`;
        try { const d = await res.json(); msg = d.message || d.error || msg; } catch {}
        throw new Error(msg);
    }
    return res.headers.get('content-type')?.includes('json') ? res.json() : null;
}

function closeModal(id) { document.getElementById(id).classList.add('hidden'); }
function logout() { localStorage.clear(); window.location.href = '/login'; }
