const API_V1 = '/api/v1';
const FLAGS_URL = `${API_V1}/flags`;

const token = localStorage.getItem('token');
const savedUser = localStorage.getItem('username');

let currentEnvironment = 'DEVELOPMENT';

// Redirect to login if no token
if (!token) window.location.href = '/login';

document.addEventListener('DOMContentLoaded', () => {
    document.getElementById('usernameDisplay').textContent = savedUser || 'Admin';
    loadFlags();
    document.getElementById('flagForm').addEventListener('submit', submitFlagForm);
});

/* ---------- API ---------- */
async function apiCall(url, method = 'GET', body) {
    const options = { method, headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' } };
    if (body) options.body = JSON.stringify(body);

    const res = await fetch(url, options);
    if (res.status === 401 || res.status === 403) logout();
    if (!res.ok) throw new Error('API error');

    return res.headers.get('content-type')?.includes('json') ? res.json() : true;
}

/* ---------- FLAGS ---------- */
async function loadFlags() {
    const flags = await apiCall(`${FLAGS_URL}?environment=${currentEnvironment}`);
    renderFlags(flags || []);
}

function renderFlags(flags) {
    const tbody = document.getElementById('flagsTable');
    tbody.innerHTML = flags.map(f => `
        <tr class="hover:bg-gray-50">
            <td class="px-6 py-3 font-mono text-blue-600">${f.flagKey}</td>
            <td class="px-6 py-3">${f.name}</td>
            <td class="px-6 py-3 text-xs font-bold">${f.environment}</td>
            <td class="px-6 py-3">${f.rolloutPercentage}%</td>
            <td class="px-6 py-3">
                <button onclick="toggleFlag('${f.flagKey}')"
                        class="px-2 py-1 rounded text-xs font-medium
                        ${f.enabled ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-600'}">
                    ${f.enabled ? 'Enabled' : 'Disabled'}
                </button>
            </td>
            <td class="px-6 py-3 text-right space-x-3">
                <button onclick="editFlag(${f.id})" class="text-indigo-600 hover:underline">Edit</button>
                <button onclick="deleteFlag(${f.id})" class="text-red-600 hover:underline">Delete</button>
            </td>
        </tr>
    `).join('');
}

/* ---------- CRUD ---------- */
async function submitFlagForm(e) {
    e.preventDefault();
    const id = document.getElementById('flagId').value;
    const payload = {
        flagKey: document.getElementById('flagKey').value.trim(),
        name: document.getElementById('flagName').value.trim(),
        description: document.getElementById('flagDescription').value.trim(),
        environment: document.getElementById('flagEnvironment').value,
        enabled: document.getElementById('flagEnabled').checked,
        defaultValue: document.getElementById('defaultValue').checked,
        rolloutPercentage: Number(document.getElementById('rolloutPercentage').value)
    };

    await apiCall(id ? `${FLAGS_URL}/${id}` : FLAGS_URL, id ? 'PUT' : 'POST', payload);
    closeModal('flagModal');
    loadFlags();
}

async function editFlag(id) {
    const f = await apiCall(`${FLAGS_URL}/${id}`);
    document.getElementById('flagId').value = f.id;
    document.getElementById('flagKey').value = f.flagKey;
    document.getElementById('flagName').value = f.name;
    document.getElementById('flagDescription').value = f.description || '';
    document.getElementById('flagEnvironment').value = f.environment;
    document.getElementById('flagEnabled').checked = f.enabled;
    document.getElementById('defaultValue').checked = f.defaultValue;
    document.getElementById('rolloutPercentage').value = f.rolloutPercentage;
    document.getElementById('rolloutValue').textContent = f.rolloutPercentage;
    document.getElementById('modalTitle').textContent = 'Edit Feature Flag';
    document.getElementById('flagModal').classList.remove('hidden');
}

async function toggleFlag(flagKey) {
    await apiCall(`${FLAGS_URL}/${flagKey}/toggle?environment=${currentEnvironment}`, 'POST');
    loadFlags();
}

async function deleteFlag(id) {
    if (!confirm('Delete this feature flag?')) return;
    await apiCall(`${FLAGS_URL}/${id}`, 'DELETE');
    loadFlags();
}

/* ---------- ENV ---------- */
function changeEnvironment(env) {
    currentEnvironment = env;
    loadFlags();
}

/* ---------- UI ---------- */
function showCreateModal() {
    document.getElementById('flagForm').reset();
    document.getElementById('flagId').value = '';
    document.getElementById('flagEnvironment').value = currentEnvironment;
    document.getElementById('rolloutValue').textContent = '100';
    document.getElementById('modalTitle').textContent = 'Create Feature Flag';
    document.getElementById('flagModal').classList.remove('hidden');
}

function closeModal(id) {
    document.getElementById(id).classList.add('hidden');
}

function logout() {
    localStorage.clear();
    window.location.href = '/login';
}
